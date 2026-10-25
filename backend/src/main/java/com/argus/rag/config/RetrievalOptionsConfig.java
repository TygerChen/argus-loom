package com.argus.rag.config;

import com.argus.rag.qa.rag.RetrievalOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 检索参数 Bean 装配。
 * <p>把 {@code argus.rag.retrieval.*} 配置项绑定为 {@link RetrievalOptions} 单例，
 * 供混合检索服务与诊断端点共享同一份生效参数。</p>
 */
@Configuration
public class RetrievalOptionsConfig {

    @Bean
    public RetrievalOptions retrievalOptions(
            @Value("${argus.rag.retrieval.channel-top-k:50}") int channelTopK,
            @Value("${argus.rag.retrieval.recall-k:50}") int recallK,
            @Value("${argus.rag.retrieval.max-global-documents:50}") int maxGlobalDocuments,
            @Value("${argus.rag.retrieval.max-chunks-per-document:0}") int maxChunksPerDocument) {
        return new RetrievalOptions(channelTopK, recallK, maxGlobalDocuments, maxChunksPerDocument);
    }
}
