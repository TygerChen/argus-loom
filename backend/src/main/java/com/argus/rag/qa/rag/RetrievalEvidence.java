package com.argus.rag.qa.rag;

import java.util.Map;

/**
 * 类型化检索证据。
 * <p>
 * 方案要求检索结果统一为强类型结构，而不是只靠 Spring AI Document 的自由 metadata 传值。
 * 字段与 {@code EvidenceDocumentAssembler} 写入 metadata 的键一一对应，可随时从
 * Document 无损重建；同时保留原始 Document 引用，证据文本与 window 信息不丢失。
 * </p>
 * <p>当前消费方：{@link RetrievedEvidenceBundle}（管理员诊断视图）。
 * 后续若要接更严格的引用校验（如 [E1] 编号防伪造），从此处取类型化字段，不再解析字符串 metadata。</p>
 *
 * @param groupId            所属知识库群组 ID
 * @param documentId         所属文档 ID
 * @param chunkId            主切片 ID
 * @param chunkIndex         主切片序号
 * @param textStartIndex     证据窗口起始切片序号（邻居扩展后）
 * @param textEndIndex       证据窗口结束切片序号（邻居扩展后）
 * @param score              检索评分（归一化后）
 * @param retrievalSource    命中来源：VECTOR / KEYWORD / BOTH / GLOBAL / WEB
 * @param permissionSnapshot 证据组装时刻的权限快照标识（当前用户可读文档集合的指纹），权限变化后可据此审计
 * @param document           原始 Spring AI Document（含证据文本与全部 metadata），保持向后兼容
 */
public record RetrievalEvidence(
        Long groupId,
        Long documentId,
        Long chunkId,
        Integer chunkIndex,
        Integer textStartIndex,
        Integer textEndIndex,
        double score,
        String retrievalSource,
        String permissionSnapshot,
        org.springframework.ai.document.Document document) {

    /** 来源常量：全局遍历。 */
    public static final String SOURCE_GLOBAL = "GLOBAL";
    /** 来源常量：网页搜索。 */
    public static final String SOURCE_WEB = "WEB";

    /** 从 metadata 安全读 Long，非 Number 时返回 null。 */
    private static Long longValue(Map<String, Object> metadata, String key) {
        Object value = metadata.get(key);
        return value instanceof Number number ? number.longValue() : null;
    }

    /** 从 metadata 安全读 Integer，非 Number 时返回 null。 */
    private static Integer intValue(Map<String, Object> metadata, String key) {
        Object value = metadata.get(key);
        return value instanceof Number number ? number.intValue() : null;
    }

    /** 从 metadata 安全读 double，缺省 0。 */
    private static double doubleValue(Map<String, Object> metadata, String key) {
        Object value = metadata.get(key);
        return value instanceof Number number ? number.doubleValue() : 0D;
    }

    /** 从 metadata 安全读文本，自动 trim，非 String 时返回 null。 */
    private static String textValue(Map<String, Object> metadata, String key) {
        Object value = metadata.get(key);
        return value instanceof String text ? text.trim() : null;
    }

    /**
     * 从 Spring AI Document 的 metadata 无损重建类型化证据。
     * <p>缺字段时置 null/0，不抛异常——网页搜索等第三方来源的文档字段天然不全。</p>
     */
    public static RetrievalEvidence fromDocument(org.springframework.ai.document.Document document) {
        Map<String, Object> metadata = document.getMetadata();
        return new RetrievalEvidence(
                longValue(metadata, "groupId"),
                longValue(metadata, "documentId"),
                longValue(metadata, "chunkId"),
                intValue(metadata, "chunkIndex"),
                intValue(metadata, "startChunkIndex"),
                intValue(metadata, "endChunkIndex"),
                doubleValue(metadata, "score"),
                textValue(metadata, "retrievalSource"),
                textValue(metadata, "permissionSnapshot"),
                document);
    }
}
