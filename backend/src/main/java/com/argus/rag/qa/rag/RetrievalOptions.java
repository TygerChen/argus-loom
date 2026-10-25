package com.argus.rag.qa.rag;

import org.springframework.beans.factory.annotation.Value;

/**
 * 混合检索的可配置参数集。
 * <p>
 * 把原先散落在 {@link HybridChunkRetrievalService} 里的硬编码常量收拢为一张参数表，
 * 挂在配置项 {@code argus.rag.retrieval.*} 下（见 application.yml），运维无需改代码即可调检索行为。
 * 所有数值型参数在构造时做下限收敛：非法配置自动落到安全值，不会拖垮检索链路。
 * </p>
 *
 * @param channelTopK           单个检索通道（向量 / 关键词）单次查询返回的最大候选数
 * @param recallK               进入重排序的最大候选数（Recall K）
 * @param maxGlobalDocuments    全局遍历模式最多拉取的文档数，防范上下文超长
 * @param maxChunksPerDocument  同一文档允许进入最终上下文的最大切片数；0 表示不限制（历史行为）
 */
public record RetrievalOptions(
        int channelTopK,
        int recallK,
        int maxGlobalDocuments,
        int maxChunksPerDocument) {

    /** 参数安全下限：再小也没有意义，直接收敛。 */
    private static final int MIN_TOP_K = 1;
    /** 每文档上限的下限：0 = 关闭上限，正数最小为 1。 */
    private static final int MIN_PER_DOCUMENT_CAP = 0;

    public RetrievalOptions {
        channelTopK = clamp(channelTopK, MIN_TOP_K);
        recallK = clamp(recallK, MIN_TOP_K);
        maxGlobalDocuments = clamp(maxGlobalDocuments, MIN_TOP_K);
        maxChunksPerDocument = clamp(maxChunksPerDocument, MIN_PER_DOCUMENT_CAP);
    }

    /** 与历史硬编码完全一致的默认参数，保证升级零回归。 */
    public static RetrievalOptions defaults() {
        return new RetrievalOptions(50, 50, 50, 0);
    }

    /**
     * 从 Spring 配置绑定构造（{@code argus.rag.retrieval.*}），未配置的项取默认值。
     */
    public static RetrievalOptions fromProperties(
            @Value("${argus.rag.retrieval.channel-top-k:50}") int channelTopK,
            @Value("${argus.rag.retrieval.recall-k:50}") int recallK,
            @Value("${argus.rag.retrieval.max-global-documents:50}") int maxGlobalDocuments,
            @Value("${argus.rag.retrieval.max-chunks-per-document:0}") int maxChunksPerDocument) {
        return new RetrievalOptions(channelTopK, recallK, maxGlobalDocuments, maxChunksPerDocument);
    }

    /** 把非正数收敛到下限；下限为 0 时仅收敛负数。 */
    private static int clamp(int value, int floor) {
        return value < floor ? floor : value;
    }
}
