package com.argus.rag.common.util;

import java.util.Map;

/**
 * Map 元数据按类型取值纯工具（无实例态、无 IO）。
 * <p>
 * 把 {@code AssistantKnowledgeBaseTool}、{@code QaRetrievalService}、
 * {@code QaRecordPersistenceService} 三处重复的 {@code instanceof Number} 取值收拢为单一实现：
 * 键值若为 {@link Number} 则按目标类型取 numberValue，否则返回 null。行为与各分身原实现逐一致。
 * </p>
 * <p>
 * 注意：{@code readScore} 的 0D 兜底、{@code readText} 的 hasText+trim 语义在本域各不相同，
 * 属有意差异，收敛时一律不并入本类，由各调用方自行保留。
 * </p>
 */
public final class MetadataReaders {

    private MetadataReaders() {
    }

    /** 键值存在且为 {@link Number} 时取 {@code longValue}，否则 null。 */
    public static Long longValue(Map<String, Object> metadata, String key) {
        Object value = metadata.get(key);
        return value instanceof Number number ? number.longValue() : null;
    }

    /** 键值存在且为 {@link Number} 时取 {@code intValue}，否则 null。 */
    public static Integer intValue(Map<String, Object> metadata, String key) {
        Object value = metadata.get(key);
        return value instanceof Number number ? number.intValue() : null;
    }

    /** 键值存在且为 {@link Number} 时取 {@code doubleValue}，否则 null。 */
    public static Double doubleValue(Map<String, Object> metadata, String key) {
        Object value = metadata.get(key);
        return value instanceof Number number ? number.doubleValue() : null;
    }

    /**
     * 读取检索评分：键 "score" 为 {@link Number} 时取其 doubleValue，否则默认 0。
     * <p>收拢 {@code CitationAssembler}/{@code EvidenceOverviewAssembler}/{@code AssistantKnowledgeBaseTool}
     * 三处相同的 0D 兜底语义。</p>
     */
    public static double score(Map<String, Object> metadata) {
        Double value = doubleValue(metadata, "score");
        return value == null ? 0D : value;
    }

    /**
     * 键值为字符串时取其 trim 后的值，否则 null。
     * <p>匹配 {@code CitationAssembler}/{@code EvidenceOverviewAssembler} 的 trim 语义；
     * QA 持久化的 hasText+trim、KB 的 raw 取值属有意差异，不并入。</p>
     */
    public static String text(Map<String, Object> metadata, String key) {
        Object value = metadata.get(key);
        return value instanceof String text ? text.trim() : null;
    }
}