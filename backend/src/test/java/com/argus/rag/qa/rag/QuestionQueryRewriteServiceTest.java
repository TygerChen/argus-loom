package com.argus.rag.qa.rag;

import com.argus.rag.common.exception.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link QuestionQueryRewriteService} 问题重写契约（纯字符串规则，全确定、零 I/O）：
 * - 入参：null/全空白 → {@code BusinessException("问题不能为空")}；
 * - 规范化：连续空白折一个空格 + trim，重写结果原始问题打头；
 * - 简化：去头 (请问|请|帮我|麻烦你|麻烦|想知道|我想知道) + 去尾 (是什么|是啥|有哪些|怎么做|如何处理|如何实现|请说明)，
 *   无变化则不加、去空则不加；
 * - 拆分：按 [？?。；;] 拆子句、仅长度 >=4、与原文/彼此去重；
 * - 边界：以「？」结尾不会触发尾部简化（后缀需 $ 锚点），但拆分仍会产出剔除问号的子句。
 */
@DisplayName("问题查询重写（QuestionQueryRewriteService）")
class QuestionQueryRewriteServiceTest {

    private final QuestionQueryRewriteService svc = new QuestionQueryRewriteService();

    // ---------- 校验 ----------

    @Test
    void rejectsNullOrBlankQuestion() {
        for (String bad : new String[]{null, "", "   ", "\t\r\n"}) {
            BusinessException e = assertThrows(BusinessException.class, () -> svc.rewrite(bad),
                    "should reject: [" + bad + "]");
            assertEquals("问题不能为空", e.getMessage());
        }
    }

    // ---------- 规范化 ----------

    @Test
    void collapsesWhitespaceAndReturnsOriginalFirst() {
        List<String> r = svc.rewrite("  应收账款    期末  是多少 ");
        assertEquals("应收账款 期末 是多少", r.get(0));
        assertTrue(r.get(0).equals("应收账款 期末 是多少"));
        for (String q : r) assertTrue(!q.contains("  "), "不应含连续空格: " + q);
    }

    @Test
    void emptySimplifiedNotEmitted() {
        // 短语只剩词头词尾时，简化结果为空串 → 不应加入，结果仅原文
        assertEquals(List.of("请问是什么"), svc.rewrite("请问是什么"));
    }

    // ---------- 简化：去前缀 ----------

    @Test
    void stripsLeadingGreeting() {
        List<String> r = svc.rewrite("请问利润率是多少");
        assertEquals(List.of("请问利润率是多少", "利润率是多少"), r);
    }

    @Test
    void stripsLeadingAltGreetings() {
        List<String> r = svc.rewrite("帮我优化分词策略");
        assertEquals(List.of("帮我优化分词策略", "优化分词策略"), r);
    }

    // ---------- 简化：去尾部 ----------

    @Test
    void stripsTrailingWhatIs() {
        List<String> r = svc.rewrite("应收账款总余额是什么");
        assertEquals(List.of("应收账款总余额是什么", "应收账款总余额"), r);
    }

    @Test
    void stripsBothPrefixAndSuffix() {
        List<String> r = svc.rewrite("请算一下滞纳金的数额是什么");
        assertEquals(List.of("请算一下滞纳金的数额是什么", "算一下滞纳金的数额"), r);
    }

    @Test
    void trailingQuestionMarkBlocksSuffixSimplification() {
        // 后缀需 $ 锚定：以「？」结尾时不触发去尾，但拆分保留去问号子句
        List<String> r = svc.rewrite("期末余额是什么？");
        assertEquals(List.of("期末余额是什么？", "期末余额是什么"), r);
    }

    // ---------- 拆分：标点 / 去重 / 长度 ----------

    @Test
    void splitsOnPunctuationDropsShortAndDedups() {
        List<String> r = svc.rewrite("应收账款是流动资产吗？应付账款是流动负债吗；存货呢？");
        assertEquals(List.of(
                "应收账款是流动资产吗？应付账款是流动负债吗；存货呢？",
                "应收账款是流动资产吗",
                "应付账款是流动负债吗"), r);   // "存货呢" 长3<4 被丢弃
    }

    @Test
    void noPunctuationNoMarkerYieldsSingleton() {
        List<String> r = svc.rewrite("应收账款");
        assertEquals(List.of("应收账款"), r);   // 无简化前缀/后缀、无标点，短子句也被丢
    }
}