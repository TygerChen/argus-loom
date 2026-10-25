package com.argus.rag.assistant.model.vo.message;

import com.argus.rag.assistant.model.enums.AssistantMessageRole;
import com.argus.rag.assistant.model.enums.AssistantToolMode;
import com.argus.rag.assistant.model.enums.KbRouteStrategy;

import java.time.LocalDateTime;
import java.util.List;

public record AssistantMessageVO(
        /**
         * 消息ID
         */
        Long messageId,
        /**
         * 所属会话ID
         */
        Long sessionId,
        /**
         * 消息角色（USER/ASSISTANT/TOOL）
         */
        AssistantMessageRole role,
        /**
         * 消息产生时的工具模式
         */
        AssistantToolMode toolMode,
        /**
         * 知识库组ID
         */
        Long groupId,
        /**
         * 知识库组ID列表（多库检索时使用）
         */
        List<Long> groupIds,
        /**
         * 知识库路由策略
         */
        KbRouteStrategy routeStrategy,
        /**
         * 消息文本内容
         */
        String content,
        /**
         * 结构化负载数据（JSON格式）
         */
        String structuredPayload,
        /**
         * 消息创建时间
         */
        LocalDateTime createdAt
) {
}
