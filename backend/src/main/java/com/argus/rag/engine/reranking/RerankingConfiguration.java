package com.argus.rag.engine.reranking;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 重排序（Reranking）服务的装配配置。
 *
 * <p>两档实现，由 {@code argus.rag.reranking.provider} 选择：</p>
 * <ul>
 *   <li><b>remote</b>：调用本地网关的 BGE cross-encoder（{@link RemoteHttpRerankingService}），
 *       真正重排。仅在显式配置时生效——上线前必须先完成离线对比评测
 *       （HitRate/MRR/延迟，对照组见 {@code scripts/rag_eval/report.md} 基线）；</li>
 *   <li><b>默认（未配置或缺 bean）</b>：{@code NoOp} 原序放行，链路永不因缺模型中断。</li>
 * </ul>
 *
 * <p>remote bean 声明在 NoOp 之前，{@code @ConditionalOnMissingBean} 据此让位；
 * 两个方向的失败回退分别位于 {@code RemoteHttpRerankingService}（服务内保序回退）
 * 与 {@code HybridChunkRetrievalService.applyReranking}（管线级 try-catch）。</p>
 */
@Configuration
public class RerankingConfiguration {

    @Bean
    @ConditionalOnProperty(name = "argus.rag.reranking.provider", havingValue = "remote")
    public RerankingService remoteHttpRerankingService(
            @Value("${argus.rag.reranking.base-url:http://127.0.0.1:11435}") String baseUrl,
            @Value("${argus.rag.reranking.model:BAAI/bge-reranker-base}") String model,
            @Value("${argus.rag.reranking.timeout-ms:8000}") long timeoutMs) {
        return new RemoteHttpRerankingService(baseUrl, model, timeoutMs);
    }

    @Bean
    @ConditionalOnMissingBean(RerankingService.class)
    public RerankingService rerankingService() {
        return new RerankingService.NoOp();
    }
}
