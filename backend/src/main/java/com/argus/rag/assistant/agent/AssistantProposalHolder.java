package com.argus.rag.assistant.agent;

/**
 * 写提案持有器（请求级）——与 {@link AssistantKnowledgeBaseToolResultHolder} 同一模式：
 * 写工具在对话循环里登记提案时写入，{@code AssistantAgentFacade} 执行结束后读出，
 * 由 {@code AssistantService} 装配进响应（前端据此渲染确认卡片）。
 *
 * <p>经 ToolContext 传入 Agent 图（服务端会话状态，模型不可伪造）；
 * 单请求作用域，请求结束即弃。</p>
 */
public class AssistantProposalHolder {

    private volatile PendingProposalStore.PendingProposal pending;

    /** 写工具登记提案后调用（请求内最后一次提案生效）。 */
    public void record(PendingProposalStore.PendingProposal proposal) {
        this.pending = proposal;
    }

    /** 请求结束后读取（无提案返回 null）。 */
    public PendingProposalStore.PendingProposal current() {
        return pending;
    }
}
