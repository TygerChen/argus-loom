package com.argus.rag.assistant.memory;

import com.argus.rag.assistant.model.entity.AssistantMessageEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AssistantSessionSummaryService} 摘要文本格式化纯函数行为锁
 * （buildSummaryText / roleLabel / normalizeContent，提 static 同包直调、零 mapper、纯 JVM）。
 * <p>锁契约：normalizeContent 折叠任意空白为单空格并 trim、超 160 截断加省略号；
 * roleLabel 仅「USER」→「用户」，其余一律「助手」；buildSummaryText 自「历史摘要：」抬头逐条
 * 「- 角色：内容」、累计超 SUMMARY_SOURCE_CHAR_LIMIT(2000) 即断尾收省略行并 trim。</p>
 */
@DisplayName("会话摘要文本格式化（AssistantSessionSummaryService 纯 static 族）")
class AssistantSessionSummaryFormatTest {

    private static AssistantMessageEntity msg(String role, String content) {
        AssistantMessageEntity m = new AssistantMessageEntity();
        m.setRole(role);
        m.setContent(content);
        return m;
    }

    // ---------- normalizeContent ----------

    @Test
    void normalizeNullYieldsEmpty() {
        assertEquals("", AssistantSessionSummaryService.normalizeContent(null));
    }

    @Test
    void normalizeCollapsesWhitespaceTrims() {
        // 多空格折叠为单个空格，两侧空白 trim
        assertEquals("你好 世界", AssistantSessionSummaryService.normalizeContent("  你好  世界  "));
        assertEquals("a b", AssistantSessionSummaryService.normalizeContent("a\n\tb"));
    }

    @Test
    void normalizeTruncatesOver160WithEllipsis() {
        String longText = "中".repeat(180);
        String out = AssistantSessionSummaryService.normalizeContent(longText);
        assertEquals(163, out.length());
        assertTrue(out.endsWith("..."));
        assertEquals("中".repeat(160), out.substring(0, 160));
    }

    @Test
    void normalizeKeepsAtOrUnder160() {
        String exact160 = "中".repeat(160);
        assertEquals(exact160, AssistantSessionSummaryService.normalizeContent(exact160));
        assertEquals("简", AssistantSessionSummaryService.normalizeContent("简"));
    }

    // ---------- roleLabel ----------

    @Test
    void userRoleMapsToUser() {
        assertEquals("用户", AssistantSessionSummaryService.roleLabel("USER"));
    }

    @Test
    void anyOtherRoleMapsToAssistant() {
        assertEquals("助手", AssistantSessionSummaryService.roleLabel("ASSISTANT"));
        assertEquals("助手", AssistantSessionSummaryService.roleLabel(null));
        assertEquals("助手", AssistantSessionSummaryService.roleLabel("SYSTEM"));
    }

    // ---------- buildSummaryText ----------

    @Test
    void summaryRendersUserThenAssistantLines() {
        String text = AssistantSessionSummaryService.buildSummaryText(
                List.of(msg("USER", "你好"), msg("ASSISTANT", "有啥可以帮你")));
        String ls = System.lineSeparator();
        assertEquals("历史摘要:" + ls + "- 用户：你好" + ls + "- 助手：有啥可以帮你", text);
    }

    @Test
    void summaryDropsNullContentToEmpty() {
        String text = AssistantSessionSummaryService.buildSummaryText(List.of(msg("USER", null)));
        assertTrue(text.contains("- 用户："));
        assertFalse(text.contains("null"));
    }

    @Test
    void summaryBreaksAndAppendsOmissionWhenOverLimit() {
        // 积累多行使头+行超 2000 字符上限 → 追加省略行并 break
        List<AssistantMessageEntity> many = new java.util.ArrayList<>();
        for (int i = 0; i < 40; i++) {
            many.add(msg("USER", "字".repeat(120)));
        }
        String text = AssistantSessionSummaryService.buildSummaryText(many);
        assertTrue(text.endsWith("- 其余历史消息已省略"));
        // 未把所有行渲染进去（超限即断）——40 行全量渲染会远大于省略断点
        assertTrue(text.length() < 40 * 120);
    }

    @Test
    void summaryTrimsTrailingLineSeparator() {
        String text = AssistantSessionSummaryService.buildSummaryText(
                List.of(msg("USER", "单行")));
        assertEquals("历史摘要:" + System.lineSeparator() + "- 用户：单行", text);
        assertFalse(text.endsWith(System.lineSeparator()));
    }
}