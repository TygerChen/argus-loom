package com.argus.rag.assistant.model.vo.chat;

import com.argus.rag.qa.model.vo.AskQuestionResponse;

import java.util.List;

public record AssistantAgentResult(
        /**
         * 助手回复的文本内容
         */
        String reply,
        /**
         * 知识库引用来源列表
         * <p>当工具模式为 KB_SEARCH 时包含检索到的文档引用信息</p>
         */
        List<AskQuestionResponse.Citation> citations,
        /**
         * 证据文本摘要（第八步独立校验的输入；KB_SEARCH 模式非空）
         */
        List<String> evidenceDigests,
        /**
         * 任务循环状态（第十步收尾留痕的源数据；请求结束即弃的内存快照）
         */
        com.argus.rag.assistant.loop.TaskLoopState loopState,
        /**
         * 待确认写提案（第十一步：写工具在循环里登记；null=本次对话无写提案）
         */
        com.argus.rag.assistant.model.vo.chat.PendingProposalVO pendingProposal
) {

    public AssistantAgentResult {
        citations = citations == null ? List.of() : List.copyOf(citations);
        evidenceDigests = evidenceDigests == null ? List.of() : List.copyOf(evidenceDigests);
    }

    /** 便捷构造（无证据摘要——历史路径/纯 CHAT 模式）。 */
    public AssistantAgentResult(String reply, List<AskQuestionResponse.Citation> citations) {
        this(reply, citations, List.of(), null, null);
    }

    /** 便捷构造（带证据摘要）。 */
    public AssistantAgentResult(String reply, List<AskQuestionResponse.Citation> citations,
                                List<String> evidenceDigests) {
        this(reply, citations, evidenceDigests, null, null);
    }

    public static AssistantAgentResult withoutCitations(String reply) {
        return new AssistantAgentResult(reply, List.of(), List.of(), null, null);
    }
}
