package com.argus.rag.qa.route;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link KnowledgeGroupRouter} 知识库自动路由的分词/评分静态族行为锁
 * （纯 JVM、static 同包直调、零构造服务、零 IO/DB）。
 * <p>锁契约：normalize 归一小写 + 去全部空白；tokenize 对 CJK 取连续 bigram、对 ASCII 整词
 * （≥4 字符再加首 3 前缀）；normalizedTokenHits 按 query token 击中比归一、空/空白兜底 0；
 * isLatinOrDigit 判断纯 [a-zA-Z0-9]。</p>
 */
@DisplayName("知识库路由分词族（KnowledgeGroupRouter.normalize/tokenize/归一命中/Latin判定）")
class KnowledgeGroupRouterTokenizeTest {

    // ---------- normalize ----------

    @Test
    void normalizeLowercasesAndStripsAllWhitespace() {
        assertEquals("", KnowledgeGroupRouter.normalize(null));
        assertEquals("hello时间", KnowledgeGroupRouter.normalize("   Hello  时间 "));
        assertEquals("杭州银行", KnowledgeGroupRouter.normalize(" 杭州  银行 "));
    }

    // ---------- tokenize：CJK bigram ----------

    @Test
    void tokenizeProducesConsecutiveCjkBigrams() {
        // "研发部" 三字 → 连续二元组 {"研发","发部"}
        assertEquals(Set.of("研发", "发部"), KnowledgeGroupRouter.tokenize("研发部"));
    }

    @Test
    void tokenizeCjkSixCharsYieldsFourBigrams() {
        assertEquals(Set.of("杭州", "州研", "研发", "发部"), KnowledgeGroupRouter.tokenize("杭州研发部"));
    }

    // ---------- tokenize：拉丁整词 + 3 前缀 ----------

    @Test
    void tokenizeLatinWholeWordAndThreePrefixForLongWord() {
        assertEquals(Set.of("agentsasia", "age"), KnowledgeGroupRouter.tokenize("AgentsAsia"));
    }

    @Test
    void tokenizeShortLatinWordOnlyWholeWord() {
        assertEquals(Set.of("rag"), KnowledgeGroupRouter.tokenize("RAG"));
    }

    // ---------- normalizedTokenHits ----------

    @Test
    void hitsScoreIsRelevantTokenRatio() {
        // "杭州银行" 含 "杭州" 不含 "北京" → 1/2
        assertEquals(0.5, KnowledgeGroupRouter.normalizedTokenHits(
                Set.of("杭州", "北京"), "杭州银行"), 1e-9);
        assertEquals(1.0, KnowledgeGroupRouter.normalizedTokenHits(
                Set.of("杭州"), "杭州银行"), 1e-9);
    }

    @Test
    void hitsEmptyQueryOrBlankTextIsZero() {
        assertEquals(0.0, KnowledgeGroupRouter.normalizedTokenHits(Set.of(), "杭州银行"), 1e-9);
        assertEquals(0.0, KnowledgeGroupRouter.normalizedTokenHits(Set.of("杭州"), "   "), 1e-9);
        assertEquals(0.0, KnowledgeGroupRouter.normalizedTokenHits(Set.of("杭州"), null), 1e-9);
    }

    // ---------- isLatinOrDigit ----------

    @Test
    void latinDetection() {
        assertTrue(KnowledgeGroupRouter.isLatinOrDigit("abc12"));
        assertFalse(KnowledgeGroupRouter.isLatinOrDigit("中文"));
        assertFalse(KnowledgeGroupRouter.isLatinOrDigit("abc-de"));
    }

    // ---------- buildScoreReason（占位构造，零服务依赖）----------

    private static final KnowledgeGroupRouter ROUTER =
            new KnowledgeGroupRouter(null, null, true, 3, 10);

    @Test
    void noHitsAnywhereYieldsNoMatch() {
        assertEquals("无匹配", ROUTER.buildScoreReason(0, 0, 0, 0, 0));
    }

    @Test
    void noScoresButDocumentsYieldBodyExists() {
        assertEquals("候选库存在文档", ROUTER.buildScoreReason(0, 0, 0, 0, 3));
    }

    @Test
    void groupNameHitListedFirst() {
        assertEquals("知识库名称命中", ROUTER.buildScoreReason(0, 2.5, 0, 0, 0));
    }

    @Test
    void joinsMultipleHitsInOrder() {
        assertEquals("知识库名称命中、描述命中、文档名命中",
                ROUTER.buildScoreReason(0, 1.5, 2.0, 1.0, 5));
    }
}