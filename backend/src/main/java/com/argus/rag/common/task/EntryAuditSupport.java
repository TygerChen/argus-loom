package com.argus.rag.common.task;

import com.argus.rag.common.security.InputSanitizer;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.util.StringUtils;

/**
 * 入口审计的"便签"传递工具 —— 业务层向入口过滤器补充票据信息的桥梁。
 *
 * <p><b>为什么需要它</b>：入口过滤器（{@link EntryAuditFilter}）是唯一知道
 * "请求最终被收下还是拒绝"的位置，但它<b>读不到请求体</b>（读体会吞掉输入流，
 * 破坏控制器的 JSON 解析）。而输入长度、身份声明、风险标记只有拿到文本的
 * 业务层才算得出。解法：过滤器在请求属性里放一张可变的"便签"（{@link TicketNotes}），
 * 业务层拿到输入后往便签上补记，过滤器收尾时统一并入任务单。</p>
 *
 * <p><b>使用姿势（每个 LLM 入口一行）</b>：
 * <pre>{@code
 * EntryAuditSupport.inspectUserInput(request, askQuestionRequest.getQuestion());
 * }</pre>
 * 便签上补记四件事：输入长度 / 身份声明标记 / 风险标记（动作类型+级别+确认门）/
 * 安全事件标记（注入模式命中）。全部 fail-safe：过滤器未启用或属性缺失时静默跳过。</p>
 */
public final class EntryAuditSupport {

    /** 请求属性键：当前请求的票据便签。 */
    public static final String ATTRIBUTE = EntryAuditFilter.class.getName() + ".TICKET_NOTES";

    /** 无状态注入检测器（@Component 的 Bean 供注入方使用；这里自持一个纯无状态实例）。 */
    private static final InputSanitizer INPUT_SANITIZER = new InputSanitizer();

    private EntryAuditSupport() {
    }

    /**
     * 一站式输入检查：输入长度 + 身份声明 + 敏感动作风险 + 注入模式安全事件。
     * <p>供 LLM 入口控制器在拿到合法请求体后调用（此时 {@code @Valid} 已通过）。
     * 所有标记<b>只记录不裁决</b>——拦截发生在运行器确认门与现有权限体系。</p>
     *
     * @param request 当前请求（属性缺失时静默跳过）
     * @param text    用户输入文本（null 安全）
     */
    public static void inspectUserInput(HttpServletRequest request, String text) {
        if (request == null || !StringUtils.hasText(text)) {
            return;
        }
        TicketNotes notes = notes(request);
        if (notes == null) {
            return;
        }
        notes.inputChars = text.length();
        if (TaskTicketInspector.flagIdentityClaim(text)) {
            notes.identityClaimSuspected = true;
        }
        TaskRiskClassifier.RiskMark riskMark = TaskRiskClassifier.classify(text);
        notes.actionType = riskMark.actionType();
        notes.riskLevel = riskMark.riskLevel();
        notes.needConfirmation = riskMark.needConfirmation();
        if (INPUT_SANITIZER.isSuspicious(text)) {
            notes.safetyFlagged = true;
        }
        // 重复请求识别（V15）：同用户+同通道+同输入指纹在滑动窗口内再现 → 只标记不拒绝。
        com.argus.rag.common.security.AuthenticatedUser currentUser = com.argus.rag.common.security.UserContext.get();
        if (RepeatRequestTracker.DEFAULT.markAndCheckRepeat(
                currentUser != null ? currentUser.userId() : null,
                TaskChannel.fromUri(request.getRequestURI()),
                text)) {
            notes.duplicateSuspected = true;
        }
    }

    /** 记录用户请求体声明的知识库群组列表（搜索范围声明，非权限授予）。 */
    public static void recordRequestedGroups(HttpServletRequest request, java.util.List<Long> groupIds) {
        TicketNotes notes = notes(request);
        if (notes == null || groupIds == null || groupIds.isEmpty()) {
            return;
        }
        StringBuilder joined = new StringBuilder();
        for (Long groupId : groupIds) {
            if (groupId == null || groupId <= 0) {
                continue;
            }
            if (joined.length() > 0) {
                joined.append(',');
            }
            joined.append(groupId);
            if (joined.length() >= 128) {
                break;
            }
        }
        if (joined.length() > 0) {
            notes.requestedGroups = joined.toString();
        }
    }

    /** 记录会话 ID（助手 sessionId / 问答 conversationId，入口可捕获时记录）。 */
    public static void recordSessionId(HttpServletRequest request, Long sessionId) {
        TicketNotes notes = notes(request);
        if (notes != null) {
            notes.sessionId = sessionId;
        }
    }

    /** 记录用户输入长度（只记数字，不记原文）。 */
    public static void recordInputLength(HttpServletRequest request, int chars) {
        TicketNotes notes = notes(request);
        if (notes != null) {
            notes.inputChars = chars;
        }
    }

    /** 标记本请求疑似包含身份声明（只记录，不裁决）。 */
    public static void markIdentityClaimSuspected(HttpServletRequest request) {
        TicketNotes notes = notes(request);
        if (notes != null) {
            notes.identityClaimSuspected = true;
        }
    }

    /**
     * 票据快照（供运行器贯穿挂号台任务单）：把便签上已补记的入口标记
     * 打包成不可变值对象，传给后续步骤（理解/方案/组装上下文）。
     * 字段全为入口阶段可信信息，不含用户输入原文。
     */
    public record TicketSnapshot(
            Long sessionId,
            TaskActionType actionType,
            TaskRiskLevel riskLevel,
            boolean needConfirmation,
            boolean identityClaimSuspected,
            boolean safetyFlagged,
            boolean duplicateSuspected,
            Integer inputChars,
            String requestedGroups
    ) {
    }

    /** 读取当前请求的票据快照；过滤器未启用时返回 null（fail-safe）。 */
    public static TicketSnapshot snapshot(HttpServletRequest request) {
        TicketNotes notes = notes(request);
        if (notes == null) {
            return null;
        }
        return new TicketSnapshot(
                notes.sessionId,
                notes.actionType,
                notes.riskLevel,
                notes.needConfirmation,
                notes.identityClaimSuspected,
                notes.safetyFlagged,
                notes.duplicateSuspected,
                notes.inputChars,
                notes.requestedGroups
        );
    }

    /** 读取当前请求的便签；过滤器未启用时返回 null。 */
    static TicketNotes notes(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        Object value = request.getAttribute(ATTRIBUTE);
        return value instanceof TicketNotes ticketNotes ? ticketNotes : null;
    }

    /** 票据便签：过滤器创建、业务层补记、过滤器收尾读取。 */
    static final class TicketNotes {

        volatile boolean identityClaimSuspected;
        volatile boolean safetyFlagged;
        volatile boolean needConfirmation;
        volatile boolean duplicateSuspected;
        volatile Integer inputChars;
        volatile Long sessionId;
        volatile String requestedGroups;
        volatile TaskActionType actionType;
        volatile TaskRiskLevel riskLevel;
    }
}
