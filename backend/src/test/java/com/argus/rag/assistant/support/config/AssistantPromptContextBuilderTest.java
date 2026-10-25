package com.argus.rag.assistant.support.config;

import com.argus.rag.assistant.model.enums.AssistantToolMode;
import com.argus.rag.assistant.model.enums.KbRouteStrategy;
import com.argus.rag.prompt.PromptTemplateManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link AssistantPromptContextBuilder} 助手 Prompt 编排契约（mock 模板管理器 + 真实字符串组装）。
 * 纯 JVM/零 DB/零 LLM。锁骨架：基础层 + 当前上下文（会话ID/模式/日期/知识库范围）+ 按 toolMode
 * 叠加任务层（KB_SEARCH→KB 模板，CHAT→网络检索规则兜底），KB 模式不产生 CHAT 网络规则块。
 */
@DisplayName("助手 Prompt 上下文构建（AssistantPromptContextBuilder）")
class AssistantPromptContextBuilderTest {

    private static final String BASE = "## 用户之后\n你是一个中文助手。";
    private static final String KB = "## 知识库任务\n基于证据回答。";

    private final PromptTemplateManager manager = mock(PromptTemplateManager.class);
    private final AssistantPromptContextBuilder builder = new AssistantPromptContextBuilder(manager);

    private void stubTemplates() {
        when(manager.render(eq("assistant-base"), any())).thenReturn(BASE);
    }

    private void stubKb() {
        when(manager.render(eq("assistant-kb-search"), any())).thenReturn(KB);
    }

    @Test
    void chatModeIncludesBaseSessionAndNetworkRulesNotKb() {
        stubTemplates();
        String out = builder.buildChatInstruction(7L, 42L, AssistantToolMode.CHAT, null, null);
        assertTrue(out.contains("你是一个中文助手。"), "基础层模板应拼入");
        assertTrue(out.contains("当前模式：CHAT"), "应标注 CHAT 模式");
        assertTrue(out.contains("当前日期："), "应注入当前日期");
        assertTrue(out.contains("工具使用规则"), "CHAT 模式应叠加工具使用规则");
        assertFalse(out.contains("知识库路由策略"), "CHAT 模式不应有 KB 路由策略段");
        assertFalse(out.contains("知识库任务"), "CHAT 模式不应叠加 KB 任务模板");
    }

    @Test
    void kbModeAppliesKbPromptInsteadOfNetworkRules() {
        stubTemplates();
        stubKb();
        String out = builder.buildChatInstruction(7L, 42L, AssistantToolMode.KB_SEARCH, null, null);
        assertTrue(out.contains("知识库任务"), "KB 模式应叠加 KB 任务模板");
        assertTrue(out.contains("知识库路由策略：AUTO"), "KB 模式 routeStrategy=null 应回退 AUTO");
        assertFalse(out.contains("工具使用规则"), "KB 模式不应叠加 CHAT 工具使用规则");
    }

    @Test
    void groupIdsAreRenderedCommaJoinedWhenPresent() {
        stubTemplates();
        String out = builder.buildChatInstruction(7L, 3L, AssistantToolMode.CHAT, List.of(10L, 20L), null);
        assertTrue(out.contains("本次知识库范围：10, 20"), "应列出知识库组 id");
    }

    @Test
    void nullOrEmptyGroupIdsOmitScopeLine() {
        stubTemplates();
        String noGroups = builder.buildChatInstruction(7L, 1L, AssistantToolMode.CHAT, null, null);
        String empty = builder.buildChatInstruction(7L, 1L, AssistantToolMode.CHAT, List.of(), null);
        assertFalse(empty.contains("本次知识库范围"));
    }
}