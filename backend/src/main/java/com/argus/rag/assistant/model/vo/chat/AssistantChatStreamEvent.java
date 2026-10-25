package com.argus.rag.assistant.model.vo.chat;

import com.argus.rag.assistant.model.enums.AssistantToolMode;
import com.argus.rag.assistant.model.enums.KbRouteStrategy;
import com.argus.rag.qa.model.vo.AskQuestionResponse;

import java.util.List;

public record AssistantChatStreamEvent(
        /**
         * 事件类型名称
         * <p>可选值：start（开始）、delta（流式增量）、done（完成）、error（错误）</p>
         */
        String event,
        String toolName,
        String toolStage,
        /**
         * 会话ID
         */
        Long sessionId,
        /**
         * 当前对话的工具模式
         */
        AssistantToolMode toolMode,
        /**
         * 知识库组ID（仅 KB_SEARCH 模式有值）
         */
        Long groupId,
        /**
         * 知识库组ID列表（仅 KB_SEARCH 模式有值，多库检索使用）
         */
        List<Long> groupIds,
        /**
         * 知识库路由策略
         */
        KbRouteStrategy routeStrategy,
        /**
         * 流式增量文本内容，仅在 delta 事件中有值
         */
        String delta,
        /**
         * 完成后的消息ID，仅在 done 事件中有值
         */
        Long messageId,
        /**
         * 完成后的完整回复文本，仅在 done 事件中有值
         */
        String reply,
        /**
         * 知识库引用来源列表，仅在 done 事件中有值
         */
        List<AskQuestionResponse.Citation> citations,
        /**
         * 错误信息，仅在 error 事件中有值
         */
        String error
) {

    public static AssistantChatStreamEvent start(
            Long sessionId,
            AssistantToolMode toolMode,
            Long groupId
    ) {
        return start(sessionId, toolMode, groupId, null, null);
    }

    public static AssistantChatStreamEvent start(
            Long sessionId,
            AssistantToolMode toolMode,
            Long groupId,
            List<Long> groupIds,
            KbRouteStrategy routeStrategy
    ) {
        return new AssistantChatStreamEvent(
                "start",
                null,
                null,
                sessionId,
                toolMode,
                groupId,
                groupIds,
                routeStrategy,
                null,
                null,
                null,
                List.of(),
                null
        );
    }

    public static AssistantChatStreamEvent delta(
            Long sessionId,
            AssistantToolMode toolMode,
            Long groupId,
            String delta
    ) {
        return delta(sessionId, toolMode, groupId, null, null, delta);
    }

    public static AssistantChatStreamEvent delta(
            Long sessionId,
            AssistantToolMode toolMode,
            Long groupId,
            List<Long> groupIds,
            KbRouteStrategy routeStrategy,
            String delta
    ) {
        return new AssistantChatStreamEvent(
                "delta",
                null,
                null,
                sessionId,
                toolMode,
                groupId,
                groupIds,
                routeStrategy,
                delta,
                null,
                null,
                List.of(),
                null
        );
    }

    public static AssistantChatStreamEvent done(
            Long sessionId,
            AssistantToolMode toolMode,
            Long groupId,
            Long messageId,
            String reply,
            List<AskQuestionResponse.Citation> citations
    ) {
        return done(sessionId, toolMode, groupId, null, null, messageId, reply, citations);
    }

    public static AssistantChatStreamEvent done(
            Long sessionId,
            AssistantToolMode toolMode,
            Long groupId,
            List<Long> groupIds,
            KbRouteStrategy routeStrategy,
            Long messageId,
            String reply,
            List<AskQuestionResponse.Citation> citations
    ) {
        return new AssistantChatStreamEvent(
                "done",
                null,
                null,
                sessionId,
                toolMode,
                groupId,
                groupIds,
                routeStrategy,
                null,
                messageId,
                reply,
                citations == null ? List.of() : citations,
                null
        );
    }

    public static AssistantChatStreamEvent error(
            Long sessionId,
            AssistantToolMode toolMode,
            Long groupId,
            String error
    ) {
        return error(sessionId, toolMode, groupId, null, null, error);
    }

    public static AssistantChatStreamEvent error(
            Long sessionId,
            AssistantToolMode toolMode,
            Long groupId,
            List<Long> groupIds,
            KbRouteStrategy routeStrategy,
            String error
    ) {
        return new AssistantChatStreamEvent(
                "error",
                null,
                null,
                sessionId,
                toolMode,
                groupId,
                groupIds,
                routeStrategy,
                null,
                null,
                null,
                List.of(),
                error
        );
    }

    public static AssistantChatStreamEvent toolCall(
            Long sessionId,
            AssistantToolMode toolMode,
            Long groupId,
            String toolName,
            String toolStage
    ) {
        return toolCall(sessionId, toolMode, groupId, null, null, toolName, toolStage);
    }

    public static AssistantChatStreamEvent toolCall(
            Long sessionId,
            AssistantToolMode toolMode,
            Long groupId,
            List<Long> groupIds,
            KbRouteStrategy routeStrategy,
            String toolName,
            String toolStage
    ) {
        return new AssistantChatStreamEvent(
                "tool_call",
                toolName,
                toolStage,
                sessionId,
                toolMode,
                groupId,
                groupIds,
                routeStrategy,
                null,
                null,
                null,
                List.of(),
                null
        );
    }
}
