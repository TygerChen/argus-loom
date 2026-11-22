package com.argus.rag.common.task;

import com.argus.rag.common.logging.TraceIdFilter;
import com.argus.rag.common.security.AuthenticatedUser;
import com.argus.rag.common.security.UserContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.Set;
import java.util.UUID;

/**
 * 入口审计过滤器 —— 挂号台的"发号 + 存根"执行者。
 *
 * <p><b>在过滤器链的位置</b>：SecurityConfig 里 {@code addFilterAfter(JwtAuthenticationFilter)}
 * —— 在认证过滤器<b>之内、授权与业务之前</b>。这个位置是精心挑的：</p>
 * <ul>
 *   <li><b>能拿到系统身份</b>：认证已发生，{@code UserContext} 已写入；
 *       且本过滤器收尾时 JwtAuthenticationFilter 的 finally 还没执行（它在更外层），
 *       身份尚未清理——finally 里读身份是安全的；</li>
 *   <li><b>能捕获所有裁决</b>：授权 403、参数校验 400、限流 429、系统 500 全部发生在
 *       内层链路，响应状态码在本过滤器 finally 时已定稿——<b>被拒请求从此留痕</b>。</li>
 * </ul>
 *
 * <p><b>记录纪律</b>：只覆盖激活通道（当前 {@code KB_QA/ASSISTANT}，EXCEL/FINANCE
 * 待各自步骤接入）；决策语义见 {@link TaskDecision}；输入原文永远不读不记。
 * <b>审计是旁路</b>：finally 里的组装/提交全程 try-catch，任何失败只告警。</p>
 *
 * <p><b>SSE 说明</b>：流式端点（stream-ask / chat stream）的初始派发即视为
 * "入口收下请求"（状态 200 → ACCEPTED），流中途的业务错误由 qa_records 等业务表记录；
 * {@code shouldNotFilterAsyncDispatch=true} 保证异步派发不重复记账。</p>
 */
public class EntryAuditFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(EntryAuditFilter.class);

    /** 链路异常穿透到本过滤器（未被全局异常处理接住）时的兜底拒收编码。 */
    private static final String CHAIN_ERROR_REJECT_CODE = "HTTP_500";

    private final EntryAuditService entryAuditService;
    /** 当前激活的审计通道（v1：KB_QA + ASSISTANT；EXCEL/FINANCE 后续接入） */
    private final Set<TaskChannel> activeChannels;

    public EntryAuditFilter(EntryAuditService entryAuditService, Set<TaskChannel> activeChannels) {
        this.entryAuditService = entryAuditService;
        this.activeChannels = Set.copyOf(activeChannels);
    }

    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        // 流式端点的异步派发沿用初始派发的存根，不重复记账
        return true;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        TaskChannel channel = TaskChannel.fromUri(request.getRequestURI());
        if (channel == null || !activeChannels.contains(channel)) {
            // 非激活通道：只透传，不放便签、不记账
            filterChain.doFilter(request, response);
            return;
        }
        long startNano = System.nanoTime();
        LocalDateTime receivedAt = LocalDateTime.now();
        EntryAuditSupport.TicketNotes notes = new EntryAuditSupport.TicketNotes();
        request.setAttribute(EntryAuditSupport.ATTRIBUTE, notes);
        boolean chainFailed = true;
        try {
            filterChain.doFilter(request, response);
            chainFailed = false;
        } finally {
            long latencyMs = (System.nanoTime() - startNano) / 1_000_000;
            submitAuditQuietly(request, response, channel, receivedAt, latencyMs, notes, chainFailed);
        }
    }

    /**
     * 组装任务单并提交落库。全程 try-catch：审计是旁路，任何异常只告警，
     * 原始异常（若链路抛出）继续向外传播，绝不吞掉业务错误。
     */
    private void submitAuditQuietly(HttpServletRequest request,
                                    HttpServletResponse response,
                                    TaskChannel channel,
                                    LocalDateTime receivedAt,
                                    long latencyMs,
                                    EntryAuditSupport.TicketNotes notes,
                                    boolean chainFailed) {
        try {
            int statusCode = response.getStatus();
            boolean rejected = chainFailed || statusCode >= HttpServletResponse.SC_BAD_REQUEST;
            TaskDecision decision = rejected ? TaskDecision.REJECTED : TaskDecision.ACCEPTED;
            String rejectCode = !rejected ? null
                    : (chainFailed && statusCode < HttpServletResponse.SC_BAD_REQUEST
                            ? CHAIN_ERROR_REJECT_CODE
                            : rejectCodeFor(statusCode));

            AuthenticatedUser user = UserContext.get();
            // 文档通道（写操作入口）的动作面由 HTTP 方法派生，不依赖请求体文本：
            // POST=上传（写知识库，中风险）；DELETE=删除（规则 4，高危+确认门）；GET=读。
            TaskActionType actionType = notes != null && notes.actionType != null ? notes.actionType : TaskActionType.READ;
            TaskRiskLevel riskLevel = notes != null && notes.riskLevel != null ? notes.riskLevel : TaskRiskLevel.LOW;
            boolean needConfirmation = notes != null && notes.needConfirmation;
            if (channel == TaskChannel.KB_DOCUMENT) {
                if ("POST".equals(request.getMethod())) {
                    actionType = TaskActionType.UPLOAD;
                    riskLevel = TaskRiskLevel.MEDIUM;
                } else if ("DELETE".equals(request.getMethod())) {
                    actionType = TaskActionType.DELETE;
                    riskLevel = TaskRiskLevel.HIGH;
                    needConfirmation = true;
                }
            }
            TaskTicket ticket = new TaskTicket(
                    resolveTraceId(),
                    null,
                    null,
                    notes != null ? notes.sessionId : null,
                    channel,
                    request.getMethod() + " " + request.getRequestURI(),
                    user != null ? user.userId() : null,
                    user != null ? user.userCode() : null,
                    user != null ? user.systemRole() : null,
                    user != null && user.mustChangePassword(),
                    notes != null && notes.identityClaimSuspected,
                    notes != null && notes.safetyFlagged,
                    notes != null && notes.duplicateSuspected,
                    notes != null ? notes.inputChars : null,
                    notes != null ? notes.requestedGroups : null,
                    actionType,
                    riskLevel,
                    needConfirmation,
                    decision,
                    statusCode,
                    rejectCode,
                    receivedAt,
                    latencyMs
            );
            entryAuditService.audit(ticket);
        } catch (RuntimeException auditFailure) {
            log.warn("入口审计组装失败(不影响主流程): uri={}, reason={}",
                    request.getRequestURI(), auditFailure.getMessage());
        }
    }

    /**
     * 拒收编码语义化：429 单独标 RATE_LIMITED（频率风险），其余保持 HTTP_xxx。
     * 语义编码让"频率风险被限流"在审计里可直接检索，不必人肉翻译状态码。
     */
    private static String rejectCodeFor(int statusCode) {
        // Servlet API 没有定义 429 常量（历史遗漏），这里用字面量
        return statusCode == 429
                ? "RATE_LIMITED"
                : "HTTP_" + statusCode;
    }

    /** 从 MDC 取 traceId；缺失时自生成（防御：审计绝不因上游缺号而丢存根）。 */
    private static String resolveTraceId() {
        String traceId = MDC.get(TraceIdFilter.MDC_TRACE_ID);
        if (StringUtils.hasText(traceId)) {
            return traceId;
        }
        return UUID.randomUUID().toString().replace("-", "");
    }
}
