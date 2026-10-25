package com.argus.rag.assistant.support.config;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.argus.rag.assistant.model.enums.AssistantToolMode;
import com.argus.rag.assistant.model.enums.KbRouteStrategy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AssistantRunnableConfigFactory} 助手 RunnableConfig 工厂契约（Standalone 纯 JVM，无 Spring）。
 * threadId 固化 user:%d:session:%d；metadata 载入 userId/sessionId/toolMode.name()，
 * 有 groupIds 时追加 groupIds 与首元素 groupId、有 routeStrategy 时载入其 name。
 */
@DisplayName("助手 RunnableConfig 工厂（AssistantRunnableConfigFactory）")
class AssistantRunnableConfigFactoryTest {

    private final AssistantRunnableConfigFactory factory = new AssistantRunnableConfigFactory();

    @Test
    void threadIdFormatIsUserSession() {
        RunnableConfig c = factory.create(10L, 20L, AssistantToolMode.CHAT, null, null);
        assertEquals(Optional.of("user:10:session:20"), c.threadId());
    }

    @Test
    void nullToolModeBuildsWithoutError() {
        // 优化：null toolMode 不再让 build() 抛 NPE（此前 addMetadata("toolMode", null) 触发 Map.copyOf 拒空）。
        RunnableConfig c = factory.create(1L, 2L, null, null, null);
        assertTrue(c.metadata("toolMode").isEmpty());
    }

    @Test
    void baseMetadataPresent() {
        RunnableConfig c = factory.create(1L, 2L, AssistantToolMode.KB_SEARCH, null, null);
        assertEquals(1L, c.metadata("userId").orElseThrow());
        assertEquals(2L, c.metadata("sessionId").orElseThrow());
        assertEquals("KB_SEARCH", c.metadata("toolMode").orElseThrow());
        assertTrue(c.metadata("groupId").isEmpty());
    }

    @Test
    void groupIdsAddsListAndFirstAsGroupId() {
        RunnableConfig c = factory.create(1L, 2L, AssistantToolMode.KB_SEARCH, List.of(5L, 7L), null);
        assertEquals(List.of(5L, 7L), c.metadata("groupIds").orElseThrow());
        assertEquals(5L, c.metadata("groupId").orElseThrow());
    }

    @Test
    void emptyGroupIdsOmitsGroupMetadata() {
        RunnableConfig c = factory.create(1L, 2L, AssistantToolMode.CHAT, List.of(), null);
        assertTrue(c.metadata("groupIds").isEmpty());
        assertTrue(c.metadata("groupId").isEmpty());
    }

    @Test
    void routeStrategyRecordedWhenProvided() {
        RunnableConfig c = factory.create(1L, 2L, AssistantToolMode.KB_SEARCH, List.of(3L), KbRouteStrategy.SELECTED);
        assertEquals("SELECTED", c.metadata("routeStrategy").orElseThrow());
    }

    @Test
    void noRouteStrategyOmitsMetadata() {
        RunnableConfig c = factory.create(1L, 2L, AssistantToolMode.CHAT, null, null);
        assertTrue(c.metadata("routeStrategy").isEmpty());
    }
}