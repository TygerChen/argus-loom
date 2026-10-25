package com.argus.rag.embedding;

import dev.langchain4j.model.embedding.onnx.bgesmallzh.BgeSmallZhEmbeddingModel;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.util.ArrayList;
import java.util.List;

/**
 * 本地进程内语义向量模型（bge-small-zh，512 维）。
 * <p>发布于 JVM 内、用 ONNX Runtime 跑，不做任何外部调用：
 * 免 API key、免云端、免 Ollama 服务、可离线。把文本安全地向量化后，
 * 存入本地 pgvector 向量库。对话模型仍走云（agnes），互不影响。</p>
 * <p>标记 {@code @Primary}，使依赖 {@link EmbeddingModel} 的检索/索引链路
 * 一律使用本 Bean，而不再拉起 OpenAI 通道的远端 embedding。</p>
 */
@Configuration(proxyBeanMethods = false)
public class LocalEmbeddingConfig {

    @Bean
    @Primary
    public EmbeddingModel localEmbeddingModel() {
        return new LocalBgeEmbeddingModel();
    }

    /** Spring AI {@link EmbeddingModel} 的本地实现，内部委托给 langchain4j 的 ONNX 模型。 */
    static class LocalBgeEmbeddingModel implements EmbeddingModel {

        private final BgeSmallZhEmbeddingModel delegate = new BgeSmallZhEmbeddingModel();

        @Override
        public EmbeddingResponse call(EmbeddingRequest request) {
            List<String> inputs = request.getInstructions();
            List<Embedding> embeddings = new ArrayList<>(inputs.size());
            int index = 0;
            for (String text : inputs) {
                float[] vector = delegate.embed(text).content().vector();
                embeddings.add(new Embedding(vector, index++));
            }
            return new EmbeddingResponse(embeddings);
        }

        @Override
        public float[] embed(org.springframework.ai.document.Document document) {
            return delegate.embed(document.getFormattedContent()).content().vector();
        }

        @Override
        public int dimensions() {
            return 512;
        }
    }
}