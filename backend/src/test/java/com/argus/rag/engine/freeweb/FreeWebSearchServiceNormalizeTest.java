package com.argus.rag.engine.freeweb;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link FreeWebSearchService#normalizeQuery(String)} 与 {@link FreeWebSearchService#normalizeUrl(String)}
 * 查询规整行为锁（纯 JVM、静态同包直调、零构造、零 IO）。
 * <p>锁契约：去掉生成关键词里的绝对日期（中文年月日 / 数字三种分隔）→ trim →
 * 含时效词（新闻|资讯|热点|动态|行情）且无「最新」时前置「最新 」；
 * 去日期后空白则回退原文 trim。normalizeUrl 仅去尾斜杠（一次）。</p>
 */
@DisplayName("免费搜索查询规整（FreeWebSearchService.normalizeQuery/normalizeUrl）")
class FreeWebSearchServiceNormalizeTest {

    // ---------- normalizeQuery：绝对日期剔除与时效前缀 ----------

    @Test
    void stripsChineseAbsoluteDateAndPrefixesLatest() {
        assertEquals("最新 科技 新闻",
                FreeWebSearchService.normalizeQuery("2026年9月17日 科技 新闻"));
    }

    @Test
    void blankAfterStripFallsBackToOriginalTrim() {
        assertEquals("2026年9月17日",
                FreeWebSearchService.normalizeQuery("2026年9月17日"));
    }

    @Test
    void stripsDashDateAndKeepsPlainTerm() {
        assertEquals("经济",
                FreeWebSearchService.normalizeQuery("经济 2026-09-17"));
    }

    @Test
    void stripsSlashDateAndPrefixesOnTimelinessWord() {
        // 注：日期剔除不折叠内部空白，故用无空格输入避免多余空格干扰前缀断言
        assertEquals("最新 金融 行情",
                FreeWebSearchService.normalizeQuery("金融2026/09/17行情"));
    }

    @Test
    void stripsSlashDateKeepingSurroundingSpaces() {
        // 行为锁：去除绝对日期不对内部空白做折叠（"产品" 前日期两侧的空格各保留）
        assertEquals("金融   产品",
                FreeWebSearchService.normalizeQuery("金融 2026/09/17 产品"));
    }

    @Test
    void keepsExistingLatestWithoutDuplicate() {
        assertEquals("最新 知识 新闻",
                FreeWebSearchService.normalizeQuery("最新 知识 新闻"));
    }

    @Test
    void nonTimelinessTermIsNotPrefixed() {
        assertEquals("产品 说明书",
                FreeWebSearchService.normalizeQuery("产品 说明书 2026年9月17日"));
    }

    // ---------- normalizeUrl 尾斜杠去除 ----------

    @Test
    void stripsSingleTrailingSlash() {
        assertEquals("https://a.com", FreeWebSearchService.normalizeUrl("https://a.com/"));
    }

    @Test
    void keepsRootPathWithoutSlash() {
        assertEquals("https://a.com/path", FreeWebSearchService.normalizeUrl(" https://a.com/path "));
    }

    @Test
    void removesOnlyOneTrailingSlash() {
        assertEquals("https://a.com/", FreeWebSearchService.normalizeUrl("https://a.com//"));
    }
}