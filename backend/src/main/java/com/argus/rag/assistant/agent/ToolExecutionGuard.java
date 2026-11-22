package com.argus.rag.assistant.agent;

import com.argus.rag.common.exception.BusinessException;
import com.argus.rag.planning.PlanTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;

/**
 * 工具执行门禁 —— 第六步"调用工具"的运行器闸门（在册/风险/预算）。
 *
 * <p><b>方案六道门在本类与既有架构中的分工</b>：</p>
 * <ul>
 *   <li>①在册：本门禁强制（工具名必须命中 {@link PlanTool} 注册表）；</li>
 *   <li>②参数：框架类型系统 + 各工具自身的入参校验（query 非空等已存在）；</li>
 *   <li>③权限：各工具内嵌的资源级校验（ToolContext 服务端锚定 +
 *       ExcelPermissionService）——两道门禁的第二道，保持不变；</li>
 *   <li>④风险：注册表 {@code writeOperation} 声明——写工具在对话循环内只允许
 *       <b>提案模式</b>（登记待确认提案、返回回执，不执行写）；循环内出现幂等键
 *       = 试图把执行 smuggle 进对话 → 直接拒绝。真正的写执行只在确认 API 的
 *       运行器路径（幂等键=proposalId，由 {@code PendingProposalStore} 状态机强制）。</li>
 *   <li>⑤预算：本门禁的<b>硬闸</b>——预算用尽的工具调用直接拒绝执行
 *       （错误信息回给模型，迫使其基于已有信息作答）——比第五步的上下文回压
 *       更硬一层，两层互补；</li>
 *   <li>⑥回执：工具返回的 success 标志（如 KB 工具的 INSUFFICIENT）+ 第四步
 *       Hook 的结果摘要——失败绝不伪装成成功。</li>
 * </ul>
 *
 * <p><b>失败语义</b>：门禁拒绝 = BusinessException → 框架把错误作为工具结果
 * 回给模型继续推理（模型可改用其他工具或直接回答），主链路不崩。</p>
 */
public final class ToolExecutionGuard {

    private static final Logger log = LoggerFactory.getLogger(ToolExecutionGuard.class);

    /** ToolContext 中任务循环状态的键（与工厂 createAgent 注入的键一致）。 */
    public static final String LOOP_STATE_CONTEXT_KEY = "taskLoopState";

    private ToolExecutionGuard() {
    }

    /**
     * 执行前三道门：在册 → 风险（写操作幂等键）→ 预算。
     *
     * @param toolContext 工具上下文（可 null——历史测试/无状态路径，跳过门禁）
     * @param toolName    工具注册名（工具自己传入常量，伪造不了身份）
     * @throws BusinessException 任何一道门不过（框架转错误回执给模型）
     */
    public static void check(ToolContext toolContext, String toolName) {
        PlanTool tool = PlanTool.fromName(toolName);
        if (tool == null) {
            // 工具名是代码常量，走到这里说明注册表与工具脱节——报警性错误
            log.error("tool.gate: 工具未在注册清单: {}", toolName);
            throw new BusinessException("工具不在注册清单：" + toolName);
        }
        if (toolContext == null) {
            return;
        }
        Object stateValue = toolContext.getContext() == null ? null
                : toolContext.getContext().get(LOOP_STATE_CONTEXT_KEY);
        if (!(stateValue instanceof com.argus.rag.assistant.loop.TaskLoopState loopState)) {
            return;
        }
        // ④风险：写操作在对话循环内只允许提案模式（第十一步确认门语义）
        if (tool.writeOperation()) {
            Object idempotencyKey = toolContext.getContext().get("idempotencyKey");
            if (idempotencyKey != null && !String.valueOf(idempotencyKey).isBlank()) {
                // 幂等键属于确认 API 的运行器执行路径——绝不允许出现在对话循环里
                log.warn("tool.gate: 对话循环内携带幂等键调用写工具，拒绝: tool={}", toolName);
                throw new BusinessException("写操作不允许在对话循环内执行：请等待用户确认后由系统执行");
            }
            // 无幂等键 = 提案模式放行：写工具自身只登记提案、返回回执，不执行写
        }
        // ⑤预算：硬闸——第七步裁决为"退出"的工具调用拒绝执行（裁决来源统一）
        if (!loopState.verdict().continueLoop()) {
            log.warn("tool.gate: 循环裁决退出，拒绝执行: tool={}, round={}/{}, reason={}",
                    toolName, loopState.currentRound(), loopState.maxRounds(), loopState.verdict().reason());
            throw new BusinessException("工具调用预算已用尽，请基于已有信息直接回答");
        }
        log.info("tool.gate: tool={} round={}/{} allowed=true",
                toolName, loopState.currentRound(), loopState.maxRounds());
    }

    /**
     * 工具结果注入模型前的"不可信数据"包裹（防提示注入）。
     * <p>外部数据源（网页搜索等）返回的内容必须当成数据而非指令——用
     * {@code ToolSafetyMarkers} 包起来再交给模型，防止恶意网页正文里的
     * "忽略以上指令"类提示注入。内部生成的工具回执（失败消息等）无需包裹。</p>
     */
    public static String wrapUntrusted(String content) {
        return com.argus.rag.assistant.agent.tool.ToolSafetyMarkers.wrap(content);
    }
}
