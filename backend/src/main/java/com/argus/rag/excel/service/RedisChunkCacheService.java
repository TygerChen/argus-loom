package com.argus.rag.excel.service;

import com.argus.rag.excel.entity.ExcelSheetChunk;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Sheet 分块缓存（有界 + TTL + 真实读路径）。
 *
 * <p>P1-05 修复前：无界 {@code ConcurrentHashMap}，且 {@code get} 在生产代码里没有调用方
 * （只写不读的“假缓存”），内存只增不减。现在：</p>
 * <ul>
 *   <li><b>真实使用</b>：以 {@code (documentId, sheetId)} 为键缓存整片 Sheet 的 chunk 列表，
 *       {@code ExcelSheetService.listChunksBySheetId} 命中即免一次 chunk 大字段查询；</li>
 *   <li><b>有界</b>：容量上限 {@value #DEFAULT_MAX_ENTRIES} 条，按访问顺序 LRU 淘汰最久未用条目；</li>
 *   <li><b>有 TTL</b>：{@value #DEFAULT_TTL_MILLIS} ms 过期兜底，防漏失效长期驻留；</li>
 *   <li><b>显式失效</b>：所有 chunk/delta 写路径（单元格写入、压缩、替换工作簿、版本回滚、删除、
 *       数字格式修复）调用 {@link #invalidateSheet}/{@link #invalidateDocument}；</li>
 *   <li><b>可观测</b>：{@link #hitCount()}/{@link #missCount()}/{@link #cacheSize()}。</li>
 * </ul>
 *
 * <p>缓存始终是辅助层：数据安全依赖 PostgreSQL 主存储，未命中直接回源查询。
 * 类名沿用历史命名，当前为进程内实现；多实例部署时可在本类内换成 Redis，读路径无需改动。</p>
 */
@Service
public class RedisChunkCacheService {

    private static final Logger log = LoggerFactory.getLogger(RedisChunkCacheService.class);

    /** 默认容量上限（条）。 */
    public static final int DEFAULT_MAX_ENTRIES = 512;

    /** 默认 TTL（毫秒）。 */
    public static final long DEFAULT_TTL_MILLIS = 120_000L;

    /** 键前缀：{@code chunks:v1:{documentId}:{sheetId}}。 */
    private static final String KEY_PREFIX = "chunks:v1:";

    private final int maxEntries;
    private final long ttlMillis;
    private final ObjectMapper objectMapper;

    /** 访问顺序 LinkedHashMap + removeEldestEntry 实现有界 LRU；并发由方法级 synchronized 保证。 */
    private final Map<String, Entry> localCache;

    private final AtomicLong hitCount = new AtomicLong();
    private final AtomicLong missCount = new AtomicLong();

    public RedisChunkCacheService() {
        this(DEFAULT_MAX_ENTRIES, DEFAULT_TTL_MILLIS);
    }

    /** 可自定义容量与 TTL（测试与后续按配置调参用）。 */
    public RedisChunkCacheService(int maxEntries, long ttlMillis) {
        this(maxEntries, ttlMillis, new ObjectMapper());
    }

    public RedisChunkCacheService(int maxEntries, long ttlMillis, ObjectMapper objectMapper) {
        this.maxEntries = Math.max(1, maxEntries);
        this.ttlMillis = Math.max(1L, ttlMillis);
        this.objectMapper = objectMapper;
        this.localCache = new LinkedHashMap<>(64, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Entry> eldest) {
                boolean evict = size() > RedisChunkCacheService.this.maxEntries;
                if (evict) {
                    log.debug("缓存超容量淘汰最久未用条目: key={}", eldest.getKey());
                }
                return evict;
            }
        };
    }

    private String cacheKey(Long documentId, Long sheetId) {
        return KEY_PREFIX + documentId + ":" + sheetId;
    }

    /**
     * 取整片 Sheet 的 chunk 列表（缓存副本）。未命中或已过期返回 {@code null}，由调用方回源 DB。
     * <p>返回的是每次反序列化出的新对象，调用方可安全修改，不会污染缓存内容。</p>
     */
    public synchronized List<ExcelSheetChunk> getSheetChunks(Long documentId, Long sheetId) {
        String json = read(cacheKey(documentId, sheetId));
        if (json == null) {
            missCount.incrementAndGet();
            log.debug("分块缓存未命中: documentId={}, sheetId={}", documentId, sheetId);
            return null;
        }
        hitCount.incrementAndGet();
        List<ExcelSheetChunk> chunks;
        try {
            chunks = objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            return new ArrayList<>();
        }
        return chunks == null ? new ArrayList<>() : new ArrayList<>(chunks);
    }

    /** 写入整片 Sheet 的 chunk 列表（存 JSON 快照，调用方后续修改不影响缓存）。 */
    public synchronized void putSheetChunks(Long documentId, Long sheetId, List<ExcelSheetChunk> chunks) {
        if (documentId == null || sheetId == null || chunks == null || chunks.isEmpty()) {
            return;
        }
        try {
            write(cacheKey(documentId, sheetId), objectMapper.writeValueAsString(chunks));
        } catch (Exception e) {
            log.warn("分块缓存序列化失败: {}", e.getMessage());
            return;
        }
        log.debug("分块缓存已写入: documentId={}, sheetId={}, chunks={}", documentId, sheetId, chunks.size());
    }

    /** 失效指定 Sheet 的缓存。 */
    public synchronized void invalidateSheet(Long documentId, Long sheetId) {
        if (documentId == null || sheetId == null) {
            return;
        }
        localCache.remove(cacheKey(documentId, sheetId));
        log.debug("缓存已失效: documentId={}, sheetId={}", documentId, sheetId);
    }

    /** 失效指定文档下所有 Sheet 的缓存（替换工作簿 / 版本回滚 / 删除等文档级变更）。 */
    public synchronized void invalidateDocument(Long documentId) {
        if (documentId == null) {
            return;
        }
        String prefix = KEY_PREFIX + documentId + ":";
        int before = localCache.size();
        localCache.keySet().removeIf(k -> k.startsWith(prefix));
        log.debug("缓存已按文档失效: documentId={}, removed={}", documentId, before - localCache.size());
    }

    /** 当前有效（未过期）条目数。 */
    public synchronized int cacheSize() {
        purgeExpired();
        return localCache.size();
    }

    /** 累计命中次数（观测用）。 */
    public long hitCount() {
        return hitCount.get();
    }

    /** 累计未命中次数（观测用）。 */
    public long missCount() {
        return missCount.get();
    }

    /** 清空缓存并重置统计（测试/运维用）。 */
    public synchronized void clear() {
        localCache.clear();
        hitCount.set(0);
        missCount.set(0);
    }

    // ── 内部：读写与过期清理 ──

    private String read(String key) {
        Entry entry = localCache.get(key);
        if (entry == null) {
            return null;
        }
        if (entry.expireAtMillis() <= System.currentTimeMillis()) {
            localCache.remove(key);
            return null;
        }
        return entry.json();
    }

    private void write(String key, String json) {
        localCache.put(key, new Entry(json, System.currentTimeMillis() + ttlMillis));
        purgeExpired();
    }

    /** 惰性清理过期条目（容量有界、调用不频繁，全量扫描成本可接受）。 */
    private void purgeExpired() {
        long now = System.currentTimeMillis();
        localCache.values().removeIf(entry -> entry.expireAtMillis() <= now);
    }

    /** 缓存条目：JSON 值 + 过期时间戳。 */
    private record Entry(String json, long expireAtMillis) {
    }
}