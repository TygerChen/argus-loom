package com.argus.rag.prompt;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Prompt 模板渲染 + 管理器契约（纯 JVM，零文件/零 Spring）。
 * - {@link PromptTemplate#renderStatic} 占位符替换：单/多变量、变字面量不受影响、
 *   未匹配占位符保留原样、空变量不变；
 * - record {@link PromptTemplate#render} 委托静态渲染；
 * - 未加载模板的 {@link PromptTemplateManager} 默认空态：getTemplate→null、
 *   render→""、hasTemplate→false、templateCount→0。
 * loadTemplates 的真实 classpath 扫描（PathMatching/真实 YAML）属启动装配，不做单测。
 */
@DisplayName("Prompt 模板渲染（PromptTemplate + PromptTemplateManager）")
class PromptTemplateRenderTest {

    // ---------- renderStatic ----------

    @Test
    void renderStaticReplacesSingleVariable() {
        String out = PromptTemplate.renderStatic("你是{role}助手", Map.of("role", "问答"));
        assertEquals("你是问答助手", out);
    }

    @Test
    void renderStaticReplacesMultipleVariables() {
        String out = PromptTemplate.renderStatic("{a}-{b}", Map.of("a", "1", "b", "2"));
        assertEquals("1-2", out);
    }

    @Test
    void renderStaticKeepsUnmatchedPlaceholderAsIs() {
        String out = PromptTemplate.renderStatic("你好{name}", Map.of("other", "x"));
        assertEquals("你好{name}", out);
    }

    @Test
    void renderStaticEmptyVariablesLeavesTemplateUntouched() {
        String tpl = "原样 {a} 保留";
        assertEquals(tpl, PromptTemplate.renderStatic(tpl, java.util.Collections.emptyMap()));
    }

    @Test
    void renderStaticEmptyValueReplacesWithEmptyString() {
        String out = PromptTemplate.renderStatic("[{k}]", java.util.Map.of("k", ""));
        assertEquals("[]", out);
    }

    // ---------- PromptTemplate.render ----------

    @Test
    void recordRenderDelegatesToStaticRenderer() {
        PromptTemplate tpl = new PromptTemplate("assistant-base", "1.0", "desc", "按 {topic} 回答");
        assertEquals("按 编码 回答", tpl.render(Map.of("topic", "编码")));
    }

    @Test
    void recordRenderWithoutVariablesReturnsContentUntouched() {
        PromptTemplate tpl = new PromptTemplate("n", "0.0", "", "固定文本，无占位");
        assertEquals("固定文本，无占位", tpl.render(java.util.Collections.emptyMap()));
    }

    // ---------- 管理器默认（未加载）空态 ----------

    @Test
    void managerDefaultStateIsEmpty() {
        PromptTemplateManager mgr = new PromptTemplateManager();
        assertEquals(0, mgr.templateCount());
        assertFalse(mgr.hasTemplate("assistant-base"));
        assertNull(mgr.getTemplate("assistant-base"));
    }

    @Test
    void managerRenderReturnsEmptyForUnknownTemplate() {
        PromptTemplateManager mgr = new PromptTemplateManager();
        assertEquals("", mgr.render("assistant-base", Map.of("k", "v")));
    }
}