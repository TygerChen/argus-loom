package com.argus.rag.assistant.memory;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.hook.messages.AgentCommand;
import com.alibaba.cloud.ai.graph.agent.hook.messages.UpdatePolicy;
import com.argus.rag.assistant.memory.longterm.MemoryContextBuilder;
import com.argus.rag.assistant.memory.longterm.MemoryRetriever;
import com.argus.rag.assistant.model.enums.AssistantMessageRole;
import com.argus.rag.assistant.model.enums.AssistantToolMode;
import com.argus.rag.assistant.model.vo.message.AssistantMessageVO;
import com.argus.rag.assistant.service.AssistantConversationService;
import com.argus.rag.assistant.service.AssistantConversationService.AssistantConversationContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link AssistantShortTermMemoryHook} 短期记忆 Hook 契约（mock conversation/memory 协作方，
 * 纯 JVM/零 DB/零 LLM）。锁纯行为：getName 恒等、runtimeCompact 保末 3 条、空输入→空、
 * shouldRuntimeCompact 以 50000 为阈值；assembleBeforeModelMessages 按
 * [compact summary → session memory → 最近消息 → 当前问题] 骨架重组，最近消息中与当前问题相同的
 * 末条 USER 回显被跳过；beforeModel 对 null/空消息或 null 配置直接透传不组装。
 */
@DisplayName("短期记忆 Hook（AssistantShortTermMemoryHook）")
class AssistantShortTermMemoryHookTest {

    private final AssistantConversationService conversationService =
            mock(AssistantConversationService.class);
    private final MemoryRetriever memoryRetriever = mock(MemoryRetriever.class);
    private final MemoryContextBuilder memoryContextBuilder = mock(MemoryContextBuilder.class);
    private final AssistantShortTermMemoryHook hook =
            new AssistantShortTermMemoryHook(conversationService, memoryRetriever, memoryContextBuilder);

    private static AssistantMessageVO vo(AssistantMessageRole role, String content) {
        return new AssistantMessageVO(
                1L, 9L, role, AssistantToolMode.CHAT, null, null, null, content, null, null);
    }

    @Test
    void nameIsTheConstantIdent() {
        assertEquals("assistant_short_term_memory_hook", hook.getName());
    }

    @Test
    void runtimeCompactKeepsOnlyLastThree() {
        List<Message> five = new ArrayList<>(List.of(
                new UserMessage("m1"), new UserMessage("m2"), new UserMessage("m3"),
                new UserMessage("m4"), new UserMessage("m5")));
        List<Message> compacted = hook.runtimeCompact(five);
        assertEquals(3, compacted.size());
        assertEquals("m3", compacted.get(0).getText());
        assertEquals("m4", compacted.get(1).getText());
        assertEquals("m5", compacted.get(2).getText());
    }

    @Test
    void runtimeCompactKeepsAllWhenUnderLimitAndEmptyOnNull() {
        List<Message> two = new ArrayList<>(List.of(new UserMessage("a"), new UserMessage("b")));
        assertEquals(2, hook.runtimeCompact(two).size());
        assertEquals(0, hook.runtimeCompact(Collections.emptyList()).size());
        assertEquals(0, hook.runtimeCompact(null).size());
    }

    @Test
    void shouldRuntimeCompactAcrossThreshold() {
        assertEquals(false, hook.shouldRuntimeCompact(50000));
        assertEquals(true, hook.shouldRuntimeCompact(50001));
        assertEquals(false, hook.shouldRuntimeCompact(0));
    }

    @Test
    void assembleOrdersMessageSkeleton() {
        whenConversationContextWith("紧凑摘要", "会话记忆", List.of(
                vo(AssistantMessageRole.USER, "历史问题"), vo(AssistantMessageRole.ASSISTANT, "历史回答")));
        List<Message> msgs = hook.assembleBeforeModelMessages(
                7L, 9L, AssistantToolMode.CHAT, null, "现在几点");
        assertEquals(5, msgs.size());
        assertInstanceOf(SystemMessage.class, msgs.get(0));
        assertTrue(msgs.get(0).getText().contains("紧凑摘要"), "首条应为紧凑摘要系统消息");
        assertInstanceOf(SystemMessage.class, msgs.get(1));
        assertTrue(msgs.get(1).getText().contains("会话记忆"), "次条应为会话记忆系统消息");
        assertInstanceOf(UserMessage.class, msgs.get(2));
        assertTrue(msgs.get(2).getText().contains("历史问题"), "第三条应为最近 USER 消息");
        assertInstanceOf(AssistantMessage.class, msgs.get(3));
        assertTrue(msgs.get(3).getText().contains("历史回答"), "第四条应为最近 ASSISTANT 消息");
        assertInstanceOf(UserMessage.class, msgs.get(4));
        assertEquals("现在几点", msgs.get(4).getText(), "末条应重造当前用户问题");
    }

    @Test
    void assembleSkipsBlankSummaryAndSkipsTrailingUserEcho() {
        // compact/session 为空白 → 不产生系统消息；末条 USER 即当前问题 → 跳过回放
        whenConversationContextWith("   ", null, List.of(vo(AssistantMessageRole.USER, "现在几点")));
        List<Message> msgs = hook.assembleBeforeModelMessages(
                7L, 9L, AssistantToolMode.CHAT, null, "现在几点");
        // 仅剩：最近消息被跳过的回显，因此只剩当前问题一条
        assertEquals(1, msgs.size());
        assertInstanceOf(UserMessage.class, msgs.get(0));
        assertEquals("现在几点", msgs.get(0).getText());
    }

    @Test
    void beforeModelWithNullOrEmptyMessagesPassesThrough() throws Exception {
        AgentCommand nullCmd = hook.beforeModel(null, null);
        assertNull(messagesOf(nullCmd));
        assertEquals(UpdatePolicy.REPLACE, policyOf(nullCmd));

        List<Message> empty = Collections.emptyList();
        AgentCommand emptyCmd = hook.beforeModel(empty, null);
        assertTrue(messagesOf(emptyCmd).isEmpty());
        assertEquals(UpdatePolicy.REPLACE, policyOf(emptyCmd));
    }

    @Test
    void metadataAsLongReadsNumberOrPlainNumericString() throws Exception {
        // Number 任意符号（含 0/负）→ longValue；非空纯数字字符串 → 原样 parse（不 trim）
        assertEquals(Optional.of(7L), invokeMetadataAsLong(configWith("userId", 7L), "userId"));
        assertEquals(Optional.of(0L), invokeMetadataAsLong(configWith("userId", 0L), "userId"));
        assertEquals(Optional.of(-3L), invokeMetadataAsLong(configWith("userId", -3L), "userId"));
        assertEquals(Optional.of(42L), invokeMetadataAsLong(configWith("userId", "42"), "userId"));
    }

    @Test
    void metadataAsLongEmptyForBlankNonNumericOrMissing() throws Exception {
        // 空白/非数字（含带两侧空格、parse 不 trim 即失败）/缺键 → Optional.empty；负号数值仍在 Number 分支
        assertEquals(Optional.empty(), invokeMetadataAsLong(configWith("userId", "abc"), "userId"));
        assertEquals(Optional.empty(), invokeMetadataAsLong(configWith("userId", ""), "userId"));
        assertEquals(Optional.empty(), invokeMetadataAsLong(configWith("userId", "  "), "userId"));
        assertEquals(Optional.empty(), invokeMetadataAsLong(configWith("userId", " 7 "), "userId"));
        assertEquals(Optional.empty(), invokeMetadataAsLong(configWith("userId", "24L"), "userId"));
        assertEquals(Optional.empty(), invokeMetadataAsLong(RunnableConfig.builder().build(), "absent"));
    }

    @SuppressWarnings("unchecked")
    private Optional<Long> invokeMetadataAsLong(RunnableConfig config, String key) throws Exception {
        Method m = AssistantShortTermMemoryHook.class.getDeclaredMethod(
                "metadataAsLong", RunnableConfig.class, String.class);
        m.setAccessible(true);
        return (Optional<Long>) m.invoke(hook, config, key);
    }

    private static RunnableConfig configWith(String key, Object value) {
        return RunnableConfig.builder().addMetadata(key, value).build();
    }

    @SuppressWarnings("unchecked")
    private static List<Message> messagesOf(AgentCommand command) throws Exception {
        Field f = AgentCommand.class.getDeclaredField("messages");
        f.setAccessible(true);
        return (List<Message>) f.get(command);
    }

    private static UpdatePolicy policyOf(AgentCommand command) throws Exception {
        Field f = AgentCommand.class.getDeclaredField("updatePolicy");
        f.setAccessible(true);
        return (UpdatePolicy) f.get(command);
    }

    private void whenConversationContextWith(
            String compactSummary, String sessionMemory, List<AssistantMessageVO> recent) {
        AssistantConversationContext ctx =
                new AssistantConversationContext(null, compactSummary, sessionMemory, recent);
        when(conversationService.loadConversationContext(anyLong(), anyLong(), anyInt())).thenReturn(ctx);
    }
}