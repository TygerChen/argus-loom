package com.argus.rag.assistant.support.config;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.argus.rag.assistant.model.enums.AssistantToolMode;
import com.argus.rag.assistant.model.enums.KbRouteStrategy;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 助手 RunnableConfig 工厂。
 * <p>创建 Agent 执行所需的 {@link RunnableConfig}，包含线程ID和用户/会话/工具模式的元数据，
 * 供 Hook 在运行时读取。</p>
 */
@Component
public class AssistantRunnableConfigFactory {

    /**
     * 创建 Agent 执行所需的 {@link RunnableConfig}。
     * <p>构建包含线程 ID 和元数据的配置对象，元数据包括 userId、sessionId、toolMode
     * 和可选的 groupId，供 Hook 在运行时通过 {@code config.metadata(key)} 读取。</p>
     *
     * @param userId    用户 ID
     * @param sessionId 会话 ID
     * @param toolMode  当前工具模式
     * @param groupIds 知识库组 ID 列表，可能为空
     * @param routeStrategy 知识库路由策略
     * @return 填充了元数据的 RunnableConfig 实例
     */
    public RunnableConfig create(
            Long userId,
            Long sessionId,
            AssistantToolMode toolMode,
            List<Long> groupIds,
            KbRouteStrategy routeStrategy
    ) {
        String threadId = "user:%d:session:%d".formatted(userId, sessionId);
        RunnableConfig.Builder builder = RunnableConfig.builder()
                .threadId(threadId)
                .addMetadata("userId", userId)
                .addMetadata("sessionId", sessionId);
        if (toolMode != null) {
            builder.addMetadata("toolMode", toolMode.name());
        }
        if (groupIds != null && !groupIds.isEmpty()) {
            builder.addMetadata("groupIds", groupIds);
            builder.addMetadata("groupId", groupIds.get(0));
        }
        if (routeStrategy != null) {
            builder.addMetadata("routeStrategy", routeStrategy.name());
        }
        return builder.build();
    }
}
