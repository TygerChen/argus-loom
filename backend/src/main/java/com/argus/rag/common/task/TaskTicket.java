package com.argus.rag.common.task;

import com.argus.rag.common.enums.SystemRole;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * 任务单（挂号单）—— 每个进入业务通道的请求在入口处生成的标准存根，
 * 也是交给第二步（理解任务）的"可信任务上下文"的数据本体。
 *
 * <p><b>它解决什么问题</b>：入口收到的不能只是"用户的一句话"，而是一份
 * 可信、结构化、可追踪的票据：谁（系统确认的身份，不是用户声称的身份）、
 * 何时、从哪个通道进来、被收下还是拒收、为什么拒、风险有多高。
 * 后续的运行器（Agent 任务编排）就以这张票为起点——
 * {@link #taskId} 是运行器任务编号，入口阶段恒为 null。</p>
 *
 * <p><b>四条入口总规则在本 record 上的落点</b>：</p>
 * <ul>
 *   <li><b>规则 1（可信绑定）</b>：traceId(=request_id)/userId/systemRole/sessionId
 *       全部来自服务端（JWT/会话解析），tenantId 当前单租户诚实置 null（多租户演进预留）；</li>
 *   <li><b>规则 2（声明≠身份）</b>：用户文本里自称的部门/角色只体现在
 *       {@code identityClaimSuspected} 标记上，绝不进入身份字段；</li>
 *   <li><b>规则 3（两道门禁）</b>：本票只承载"能力级"结果（decision），
 *       资源级校验在工具执行前进行；</li>
 *   <li><b>规则 4（敏感动作入口标记）</b>：actionType/riskLevel/needConfirmation
 *       由 {@link TaskRiskClassifier} 在入口打标——<b>标记不拦截</b>，
 *       拦截发生在运行器确认门。</li>
 * </ul>
 *
 * <p><b>隐私红线（审计 vs 上下文的分野）</b>：落库（entry_log）永不记输入原文，
 * 只记 {@code inputChars} 与各类标记；{@link #formatContext(String)} 交给第二步的
 * 内存上下文可以携带问题文本（下游要理解任务），但那是进程内传递，不是审计存储。</p>
 *
 * @param traceId                 链路追踪号（=request_id，与日志 MDC、响应头 X-Trace-Id 同源）
 * @param taskId                  运行器任务编号（预留，入口阶段恒为 null）
 * @param tenantId                租户 ID（当前单租户阶段恒为 null，多租户演进预留）
 * @param sessionId               会话 ID（助手会话/问答会话，入口可捕获时记录）
 * @param channel                 业务通道（挂号科室）
 * @param endpoint                请求形态（如 {@code POST /api/qa/ask}）
 * @param userId                  系统确认的用户 ID（未认证为 null）
 * @param userCode                用户业务编号（未认证为 null）
 * @param systemRole              系统角色（未认证为 null）
 * @param mustChangePassword      是否处于"必须改密"状态
 * @param identityClaimSuspected  用户文本疑似包含身份声明（只标记，不裁决）
 * @param safetyFlagged           用户文本命中注入模式（InputSanitizer 判定，安全事件标记）
 * @param duplicateSuspected      疑似滑动窗口内重复请求（同用户+同通道+同输入指纹，只标记不拒绝）
 * @param inputChars              用户输入长度（审计不记原文）
 * @param requestedGroups         用户请求体声明的知识库群组（搜索范围声明，非权限授予）
 * @param actionType              动作类型（READ/DELETE/EXPORT/EXTERNAL_SEND/BATCH_MODIFY）
 * @param riskLevel               风险级别（LOW/HIGH，敏感动作入口即标 HIGH）
 * @param needConfirmation        是否需要人工确认门（HIGH 请求恒 true，由运行器执行）
 * @param decision                入口裁决：收下 / 拒收
 * @param statusCode              本次请求的 HTTP 状态码
 * @param rejectCode              拒收原因编码（RATE_LIMITED / HTTP_403 等）；收下时为 null
 * @param receivedAt              请求到达时刻
 * @param latencyMs               入口处理耗时（含整条调用链）
 */
public record TaskTicket(
        String traceId,
        Long taskId,
        String tenantId,
        Long sessionId,
        TaskChannel channel,
        String endpoint,
        Long userId,
        String userCode,
        SystemRole systemRole,
        boolean mustChangePassword,
        boolean identityClaimSuspected,
        boolean safetyFlagged,
        boolean duplicateSuspected,
        Integer inputChars,
        String requestedGroups,
        TaskActionType actionType,
        TaskRiskLevel riskLevel,
        boolean needConfirmation,
        TaskDecision decision,
        Integer statusCode,
        String rejectCode,
        LocalDateTime receivedAt,
        long latencyMs
) {

    private static final DateTimeFormatter CONTEXT_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    /** 交给第二步的上下文中，问题文本的截断长度（防长输入撑爆提示词）。 */
    private static final int CONTEXT_QUESTION_PREVIEW_CHARS = 200;

    /**
     * 生成交给第二步的"可信任务上下文"文本块。
     * <p>格式对齐入口规格：任务编号 / 可信用户身份 / 请求来源 / 用户原始问题 /
     * 基础权限 / 风险标记 / 入口状态。第二步从这里开始理解"用户究竟想解决什么"，
     * 第一步到此为止——不解释语义，不判断业务。</p>
     *
     * @param question 用户原始问题文本（仅用于内存上下文展示，审计落库不携带；
     *                 null 时显示占位说明）
     * @return 结构化上下文文本
     */
    public String formatContext(String question) {
        StringBuilder sb = new StringBuilder();
        sb.append("任务编号：").append(entryNumber()).append('\n');
        sb.append('\n');
        sb.append("可信用户身份：\n");
        sb.append("- 用户：").append(userId != null ? "U" + userId : "未认证").append('\n');
        sb.append("- 编号：").append(userCode != null ? userCode : "-").append('\n');
        sb.append("- 角色：").append(systemRole != null ? systemRole.name() : "-").append('\n');
        sb.append("- 租户：").append(tenantId != null ? tenantId : "单租户(默认)").append('\n');
        sb.append("- 登录状态：").append(userId != null ? "有效（JWT 有效期+账号状态已复检）" : "未认证").append('\n');
        sb.append('\n');
        sb.append("请求来源：\n");
        sb.append("- 模块：").append(channel != null ? channel.displayName() : "-").append('\n');
        sb.append("- 入口：").append(endpoint != null ? endpoint : "-").append('\n');
        sb.append("- 会话：").append(sessionId != null ? "C-" + sessionId : "-").append('\n');
        sb.append("- 选择群组：").append(requestedGroups != null && !requestedGroups.isBlank() ? requestedGroups : "-").append('\n');
        sb.append("- 时间：").append(receivedAt != null ? receivedAt.format(CONTEXT_TIME_FORMAT) : "-").append('\n');
        sb.append('\n');
        sb.append("用户原始问题：\n");
        sb.append("- ").append(previewOf(question)).append('\n');
        sb.append('\n');
        sb.append("基础权限：\n");
        sb.append("- 能力级校验：").append(decision == TaskDecision.ACCEPTED ? "允许使用" + (channel != null ? channel.displayName() : "本通道") + "功能" : "未通过(" + rejectCode + ")").append('\n');
        sb.append("- 资源级校验：群组选择只是搜索范围声明；具体文档/表格/财务数据由工具执行前强制过滤（两道门禁）").append('\n');
        sb.append('\n');
        sb.append("风险标记：\n");
        sb.append("- 动作类型：").append(actionType != null ? actionType.name() : "READ").append('\n');
        sb.append("- 风险级别：").append(riskLevel != null ? riskLevel.name() : "LOW").append('\n');
        sb.append("- 需要确认门：").append(needConfirmation ? "是（运行器执行前必须人工确认）" : "否").append('\n');
        if (duplicateSuspected) {
            sb.append("- 重复请求：疑似（滑动窗口内同用户同通道同输入再现，待运行器/业务层裁决）").append('\n');
        }
        if (identityClaimSuspected) {
            sb.append("- 身份声明：疑似（用户文本自称了某种身份，待第二步与系统身份比对）").append('\n');
        }
        if (safetyFlagged) {
            sb.append("- 输入安全：命中注入模式（安全事件已标记）").append('\n');
        }
        sb.append('\n');
        sb.append("入口状态：").append(decision != null ? decision.name().toLowerCase(Locale.ROOT) : "-").append('\n');
        return sb.toString();
    }

    /**
     * 入口编号：TASK-yyyyMMdd-xxxxxxxx（日期 + traceId 前 8 位）。
     * <p>对齐方案示例的 {@code TASK-20261121-001} 形态；用 traceId 派生而不用
     * 自增计数器——编号确定性可复现、天然分布式安全，且与全链路日志同源。</p>
     */
    private String entryNumber() {
        if (taskId != null) {
            return "TASK-" + taskId;
        }
        String day = receivedAt != null
                ? receivedAt.format(DateTimeFormatter.ofPattern("yyyyMMdd"))
                : "00000000";
        String shortTrace = traceId != null && traceId.length() >= 8 ? traceId.substring(0, 8) : String.valueOf(traceId);
        return "TASK-" + day + "-" + shortTrace;
    }

    /** 问题预览：截断 200 字符并去掉换行（上下文是给下游看的摘要，不是全文存档）。 */
    private static String previewOf(String question) {
        if (question == null || question.isBlank()) {
            return "(未捕获——请求在到达业务层前即被拒绝，或文本未传入)";
        }
        String normalized = question.replaceAll("\\s+", " ").trim();
        return normalized.length() <= CONTEXT_QUESTION_PREVIEW_CHARS
                ? normalized
                : normalized.substring(0, CONTEXT_QUESTION_PREVIEW_CHARS) + "…(截断)";
    }
}
