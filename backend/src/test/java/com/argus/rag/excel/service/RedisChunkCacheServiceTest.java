package com.argus.rag.excel.service;

import com.argus.rag.excel.entity.ExcelSheetChunk;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link RedisChunkCacheService} 分块缓存契约（纯 JVM，零依赖）：
 * <ul>
 *   <li>P1-05 修复后为「有界 + TTL + 真实读路径」：键为 (documentId, sheetId)，值为整片 Sheet 的 chunk 列表；</li>
 *   <li>命中返回可安全修改的副本，跨 document / sheet 严格隔离；</li>
 *   <li>invalidateSheet 只失效目标 Sheet，invalidateDocument 失效整篇文档且不误伤其他文档；</li>
 *   <li>容量上限触发 LRU 淘汰、超过 TTL 自动过期、缓存大小不计过期条目；</li>
 *   <li>命中/未命中计数可观测，clear 重置。</li>
 * </ul>
 * 行为一旦回退（键拼接、失效范围、淘汰/过期、副本隔离）即红。
 */
@DisplayName("分块缓存契约（RedisChunkCacheService）")
class RedisChunkCacheServiceTest {

    private RedisChunkCacheService cache;

    @BeforeEach
    void setUp() {
        // 每个用例独立实例，避免跨用例状态污染
        cache = new RedisChunkCacheService();
    }

    private static ExcelSheetChunk chunk(long documentId, long sheetId, int chunkIndex, String celldataJson) {
        ExcelSheetChunk chunk = new ExcelSheetChunk();
        chunk.setId((long) chunkIndex + 1);
        chunk.setDocumentId(documentId);
        chunk.setSheetId(sheetId);
        chunk.setChunkIndex(chunkIndex);
        chunk.setRowStart(chunkIndex * 1000);
        chunk.setRowEnd(chunkIndex * 1000 + 999);
        chunk.setCelldataJson(celldataJson);
        return chunk;
    }

    private static List<ExcelSheetChunk> chunks(ExcelSheetChunk... items) {
        List<ExcelSheetChunk> list = new ArrayList<>();
        for (ExcelSheetChunk item : items) {
            list.add(item);
        }
        return list;
    }

    @Test
    void getOnEmptyCacheReturnsNull() {
        assertNull(cache.getSheetChunks(1L, 2L));
        assertEquals(0, cache.cacheSize());
    }

    @Test
    void putThenGetRoundTripsAllChunkFields() {
        cache.putSheetChunks(1L, 2L, chunks(chunk(1L, 2L, 0, "[{\"r\":0,\"c\":0}]")));

        List<ExcelSheetChunk> hit = cache.getSheetChunks(1L, 2L);
        assertNotNull(hit);
        assertEquals(1, hit.size());
        ExcelSheetChunk got = hit.get(0);
        assertEquals(1L, got.getId());
        assertEquals(1L, got.getDocumentId());
        assertEquals(2L, got.getSheetId());
        assertEquals(0, got.getChunkIndex());
        assertEquals(0, got.getRowStart());
        assertEquals(999, got.getRowEnd());
        assertEquals("[{\"r\":0,\"c\":0}]", got.getCelldataJson());
        assertEquals(1, cache.cacheSize());
    }

    @Test
    void getIsScopedByDocumentId() {
        cache.putSheetChunks(1L, 2L, chunks(chunk(1L, 2L, 0, "[1]")));
        // 同 sheet 但不同 document → 未命中
        assertNull(cache.getSheetChunks(2L, 2L));
    }

    @Test
    void getIsScopedBySheetId() {
        cache.putSheetChunks(1L, 2L, chunks(chunk(1L, 2L, 0, "[1]")));
        assertNull(cache.getSheetChunks(1L, 3L));
    }

    @Test
    void returnedListIsIndependentCopyOfCachedValue() {
        cache.putSheetChunks(1L, 2L, chunks(chunk(1L, 2L, 0, "[1]")));

        List<ExcelSheetChunk> first = cache.getSheetChunks(1L, 2L);
        first.get(0).setCelldataJson("[mutated]");
        first.clear();

        List<ExcelSheetChunk> second = cache.getSheetChunks(1L, 2L);
        assertEquals(1, second.size());
        assertEquals("[1]", second.get(0).getCelldataJson());
    }

    @Test
    void invalidateSheetClearsOnlyThatSheet() {
        cache.putSheetChunks(1L, 2L, chunks(chunk(1L, 2L, 0, "[1]")));
        cache.putSheetChunks(1L, 99L, chunks(chunk(1L, 99L, 0, "[2]")));

        cache.invalidateSheet(1L, 2L);

        assertNull(cache.getSheetChunks(1L, 2L));
        assertNotNull(cache.getSheetChunks(1L, 99L));
    }

    @Test
    void invalidateSheetDoesNotTouchSameSheetOtherDocument() {
        cache.putSheetChunks(1L, 2L, chunks(chunk(1L, 2L, 0, "[1]")));
        cache.putSheetChunks(5L, 2L, chunks(chunk(5L, 2L, 0, "[5]")));

        cache.invalidateSheet(1L, 2L);

        assertNull(cache.getSheetChunks(1L, 2L));
        assertNotNull(cache.getSheetChunks(5L, 2L));
    }

    @Test
    void invalidateDocumentClearsAllSheetsOfDocumentOnly() {
        cache.putSheetChunks(1L, 2L, chunks(chunk(1L, 2L, 0, "[1]")));
        cache.putSheetChunks(1L, 3L, chunks(chunk(1L, 3L, 0, "[2]")));
        cache.putSheetChunks(5L, 2L, chunks(chunk(5L, 2L, 0, "[5]")));

        cache.invalidateDocument(1L);

        assertNull(cache.getSheetChunks(1L, 2L));
        assertNull(cache.getSheetChunks(1L, 3L));
        assertNotNull(cache.getSheetChunks(5L, 2L));
    }

    @Test
    void putOverwritesExistingSheetEntry() {
        cache.putSheetChunks(1L, 2L, chunks(chunk(1L, 2L, 0, "[old]")));
        cache.putSheetChunks(1L, 2L, chunks(chunk(1L, 2L, 0, "[new]")));

        assertEquals("[new]", cache.getSheetChunks(1L, 2L).get(0).getCelldataJson());
        assertEquals(1, cache.cacheSize());
    }

    @Test
    void capacityBoundEvictsLeastRecentlyUsedEntry() {
        RedisChunkCacheService bounded = new RedisChunkCacheService(2, 60_000L);
        bounded.putSheetChunks(1L, 1L, chunks(chunk(1L, 1L, 0, "[1]")));
        bounded.putSheetChunks(1L, 2L, chunks(chunk(1L, 2L, 0, "[2]")));
        // 访问 sheet=1，使其成为最近使用
        assertNotNull(bounded.getSheetChunks(1L, 1L));

        bounded.putSheetChunks(1L, 3L, chunks(chunk(1L, 3L, 0, "[3]")));

        assertEquals(2, bounded.cacheSize());
        assertNotNull(bounded.getSheetChunks(1L, 1L));
        assertNull(bounded.getSheetChunks(1L, 2L), "最久未使用的 sheet=2 应被淘汰");
    }

    @Test
    void entryExpiresAfterTtl() throws Exception {
        RedisChunkCacheService shortLived = new RedisChunkCacheService(8, 80L);
        shortLived.putSheetChunks(1L, 1L, chunks(chunk(1L, 1L, 0, "[1]")));
        assertNotNull(shortLived.getSheetChunks(1L, 1L));

        Thread.sleep(150L);

        assertNull(shortLived.getSheetChunks(1L, 1L), "超过 TTL 应视为未命中");
        assertEquals(0, shortLived.cacheSize(), "过期条目不计入缓存大小");
    }

    @Test
    void hitAndMissCountersTrackLookups() {
        assertNull(cache.getSheetChunks(1L, 2L));
        cache.putSheetChunks(1L, 2L, chunks(chunk(1L, 2L, 0, "[1]")));
        assertNotNull(cache.getSheetChunks(1L, 2L));

        assertEquals(1, cache.missCount());
        assertEquals(1, cache.hitCount());
        assertEquals(2, cache.hitCount() + cache.missCount());
    }

    @Test
    void clearResetsEntriesAndCounters() {
        cache.putSheetChunks(1L, 2L, chunks(chunk(1L, 2L, 0, "[1]")));
        cache.getSheetChunks(1L, 2L);

        cache.clear();

        assertEquals(0, cache.cacheSize());
        assertEquals(0, cache.hitCount());
        assertEquals(0, cache.missCount());
        assertNull(cache.getSheetChunks(1L, 2L));
    }

    @Test
    void putIgnoresNullKeysAndEmptyPayload() {
        cache.putSheetChunks(null, 2L, chunks(chunk(1L, 2L, 0, "[1]")));
        cache.putSheetChunks(1L, null, chunks(chunk(1L, 2L, 0, "[1]")));
        cache.putSheetChunks(1L, 2L, null);
        cache.putSheetChunks(1L, 2L, List.of());

        assertEquals(0, cache.cacheSize());
        assertEquals(0, cache.hitCount());
        assertTrue(cache.missCount() == 0);
    }
}
