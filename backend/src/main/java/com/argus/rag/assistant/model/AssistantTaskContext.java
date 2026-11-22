package com.argus.rag.assistant.model;

import com.argus.rag.common.task.EntryAuditSupport;
import com.argus.rag.planning.ExecutionPlan;
import com.argus.rag.understanding.TaskUnderstanding;

/**
 * 助手单轮任务的上下文载体 —— 第二步（理解）+ 第三步（方案）产出的打包。
 *
 * <p><b>为什么要有这个载体</b>：Agent 门面（AssistantAgentFacade）与 Prompt 构建
 * 需要消费入口段的多份产出；如果没有统一载体，每加一步（第四步、第五步…）
 * 都要再改一遍门面/构建器签名。收口成一个 record，后续步骤只加字段不改签名。</p>
 *
 * @param understanding 第二步理解结果（永不为 null，失败为 empty）
 * @param plan          第三步执行方案（永不为 null，LLM 失败为代码兜底方案）
 * @param ticket        挂号台任务单快照（入口可信信息：风险/动作/身份声明标记等；可为 null）
 */
public record AssistantTaskContext(
        TaskUnderstanding understanding,
        ExecutionPlan plan,
        EntryAuditSupport.TicketSnapshot ticket
) {

    /** 兼容旧构造（无票据快照）。 */
    public AssistantTaskContext(TaskUnderstanding understanding, ExecutionPlan plan) {
        this(understanding, plan, null);
    }

    /** 空上下文（fail-soft 时的缺省值：无理解 + 无方案）。 */
    public static AssistantTaskContext blank() {
        return new AssistantTaskContext(TaskUnderstanding.empty(), null, null);
    }

    /** 是否携带有效内容（决定是否注入指令块）。 */
    public boolean isEmpty() {
        return (understanding == null || understanding.isEmpty())
                && (plan == null || plan.isEmpty());
    }
}
