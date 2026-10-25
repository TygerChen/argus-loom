package com.argus.rag.embedding;

import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证 Spring 容器注入的 {@link EmbeddingModel} 是本地进程内 Bean，
 * 而非远端 OpenAI 通道——RAG 检索/索引走到这里不依赖任何云或本地服务。
 */
class LocalEmbeddingWiringTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(LocalEmbeddingConfig.class);

    @Test
    void primaryEmbeddingModelIsTheLocalOne() {
        runner.withBean(Boolean.class, () -> Boolean.TRUE)
                .run(ctx -> {
                    EmbeddingModel bean = ctx.getBean(EmbeddingModel.class);
                    assertThat(bean)
                            .as("容器中唯一的 EmbeddingModel 应解析到本地 bge-small-zh")
                            .isInstanceOf(LocalEmbeddingConfig.LocalBgeEmbeddingModel.class);
                    assertThat(bean.dimensions()).isEqualTo(512);
                });
    }
}