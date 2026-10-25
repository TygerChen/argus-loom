package com.argus.rag.assistant.memory;

import com.argus.rag.assistant.model.entity.AssistantMessageEntity;
import com.argus.rag.assistant.model.enums.AssistantToolMode;
import com.argus.rag.common.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.prompt.PromptTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link AssistantMemorySummarizer} 摘要封装契约（mock ChatClient.Builder/templates/ChatClient 全链，
 * 零真实 LLM，纯 JVM）：
 * - formatMessages（同包私有）：null/空→"NONE"、多消息→"[role] content" 按 System.lineSeparator 拼接、
 *   空白/null 内容跳过、全空白→"NONE"、null 角色→"[NONE]"；
 * - 三条公开摘要方法：模型正常返回→归一（去空白）、模型返回空/空白→BusinessException「errorMessage，模型返回为空」、
 *   模型调链抛 RuntimeException→BusinessException(errorMessage) 带 cause、BusinessException 原样透传。
 */
@DisplayName("摘要封装（AssistantMemorySummarizer）")
class AssistantMemorySummarizerTest {

    private AssistantMemorySummarizer svc;
    private ChatClient.CallResponseSpec call;

    @BeforeEach
    void setUp() {
        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        ChatClient chatClient = mock(ChatClient.class);
        PromptTemplate templateS = mock(PromptTemplate.class);
        PromptTemplate templateC = mock(PromptTemplate.class);
        PromptTemplate templateR = mock(PromptTemplate.class);
        ChatClient.ChatClientRequestSpec spec = mock(ChatClient.ChatClientRequestSpec.class);
        call = mock(ChatClient.CallResponseSpec.class);

        when(builder.build()).thenReturn(chatClient);
        when(templateS.create(anyMap())).thenReturn(mock(Prompt.class));
        when(templateC.create(anyMap())).thenReturn(mock(Prompt.class));
        when(templateR.create(anyMap())).thenReturn(mock(Prompt.class));
        when(chatClient.prompt(any(Prompt.class))).thenReturn(spec);
        when(spec.call()).thenReturn(call);

        svc = new AssistantMemorySummarizer(builder, templateS, templateC, templateR);
    }

    // ---------- helpers ----------

    private static AssistantMessageEntity msg(String role, String content) {
        AssistantMessageEntity e = new AssistantMessageEntity();
        e.setRole(role);
        e.setContent(content);
        return e;
    }

    // ---------- formatMessages（纯组装） ----------

    @Test
    void formatMessagesNullOrEmptyIsNone() {
        assertEquals("NONE", svc.formatMessages(null));
        assertEquals("NONE", svc.formatMessages(List.of()));
    }

    @Test
    void formatMessagesAssemblesRoleAndContentLines() {
        List<AssistantMessageEntity> msgs = List.of(
                msg("USER", "你好"),
                msg("ASSISTANT", "好的"));
        String expected = String.join(System.lineSeparator(),
                "[USER] 你好",
                "[ASSISTANT] 好的");
        assertEquals(expected, svc.formatMessages(msgs));
    }

    @Test
    void formatMessagesSkipsBlankContent() {
        List<AssistantMessageEntity> msgs = List.of(
                msg("USER", "  "),
                msg("ASSISTANT", null),
                msg("USER", "有效"));
        assertEquals("[USER] 有效", svc.formatMessages(msgs));
    }

    @Test
    void formatMessagesAllBlankIsNone() {
        assertEquals("NONE", svc.formatMessages(List.of(msg("USER", "   "), msg("ASSISTANT", null))));
    }

    @Test
    void formatMessagesNullRoleUsesNoneLabel() {
        List<AssistantMessageEntity> msgs = List.of(msg(null, "内容"));
        assertEquals("[NONE] 内容", svc.formatMessages(msgs));
    }

    // ---------- 成功路径 ----------

    @Test
    void summarizeSessionMemoryReturnsTrimmedContent() {
        when(call.content()).thenReturn("  摘要文本  ");
        String out = svc.summarizeSessionMemory("OLD", List.of(msg("USER", "x")),
                AssistantToolMode.CHAT, 3L);
        assertEquals("摘要文本", out);
    }

    @Test
    void summarizeCompactSummaryReturnsTrimmedContent() {
        when(call.content()).thenReturn("紧凑\n摘要\r\n");
        String out = svc.summarizeCompactSummary("OLD", "MEM", List.of(msg("USER", "x")));
        assertEquals("紧凑\n摘要", out);
    }

    @Test
    void summarizeRuntimeContextReturnsTrimmedContent() {
        when(call.content()).thenReturn(" 运行时 ");
        String out = svc.summarizeRuntimeContext("C", "M", "R", "Q");
        assertEquals("运行时", out);
    }

    // ---------- 空 / 异常包络 ----------

    @Test
    void summarizeSessionMemoryBlankContentThrows() {
        when(call.content()).thenReturn("   ");
        BusinessException e = assertThrows(BusinessException.class,
                () -> svc.summarizeSessionMemory(null, List.of(msg("USER", "x")), AssistantToolMode.CHAT, null));
        assertEquals("生成 session memory 失败，模型返回为空", e.getMessage());
    }

    @Test
    void summarizeCompactSummaryBlankThrows() {
        when(call.content()).thenReturn("");
        BusinessException e = assertThrows(BusinessException.class,
                () -> svc.summarizeCompactSummary("OLD", "MEM", List.of(msg("USER", "x"))));
        assertEquals("生成 compact summary 失败，模型返回为空", e.getMessage());
    }

    @Test
    void summarizeRuntimeBlankThrows() {
        when(call.content()).thenReturn(" \n ");
        BusinessException e = assertThrows(BusinessException.class,
                () -> svc.summarizeRuntimeContext(null, null, null, "Q"));
        assertEquals("生成运行时压缩上下文失败，模型返回为空", e.getMessage());
    }

    @Test
    void summarizeWrapsModelRuntimeException() {
        when(call.content()).thenThrow(new RuntimeException("llm down"));
        BusinessException e = assertThrows(BusinessException.class,
                () -> svc.summarizeSessionMemory("O", List.of(msg("USER", "x")), AssistantToolMode.CHAT, null));
        assertEquals("生成 session memory 失败", e.getMessage());
        assertEquals(RuntimeException.class, e.getCause().getClass());
    }

    @Test
    void summarizeRethrowsUnderlyingBusinessException() {
        BusinessException orig = new BusinessException("原错误");
        when(call.content()).thenThrow(orig);
        BusinessException e = assertThrows(BusinessException.class,
                () -> svc.summarizeSessionMemory("O", List.of(msg("USER", "x")), AssistantToolMode.CHAT, null));
        assertSame(orig, e);
    }
}