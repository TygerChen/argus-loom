package com.argus.rag.embedding;

import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 本地 embedding 冒烟：bge-small-zh（进程内 ONNX，512 维），
 * 不做任何云端调用，验证「文本→本地向量」链路真实可跑。
 */
class LocalEmbeddingSmokeTest {

    @Test
    void embedsTextLocallyTo512DimsWithoutCloud() {
        LocalEmbeddingConfig.LocalBgeEmbeddingModel model =
                new LocalEmbeddingConfig.LocalBgeEmbeddingModel();

        EmbeddingResponse resp = model.call(
                new EmbeddingRequest(List.of("测试一下本地向量"), null));

        assertThat(resp.getResults()).hasSize(1);
        float[] vector = resp.getResult().getOutput();
        // 与 pgvector vector(512) 对齐：维度正好 512
        assertThat(vector).hasSize(512);
        // 非全零——确是真算出的向量，不是空壳
        double energy = 0;
        for (float v : vector) energy += v * v;
        assertThat(energy).isGreaterThan(1e-6);
        // 维度暴露属性一致
        assertThat(model.dimensions()).isEqualTo(512);
    }

    @Test
    void embeddingsAreSemanticallyMeaningful() {
        LocalEmbeddingConfig.LocalBgeEmbeddingModel model =
                new LocalEmbeddingConfig.LocalBgeEmbeddingModel();

        Map<String, float[]> vec = Map.of(
                "请假流程是怎样的", embedOne(model, "请假流程是怎样的"),
                "如何申请年假", embedOne(model, "如何申请年假"),
                "公司食堂几点开饭", embedOne(model, "公司食堂几点开饭"));
        float[] a = vec.get("请假流程是怎样的");
        float[] b = vec.get("如何申请年假");
        float[] c = vec.get("公司食堂几点开饭");

        // 相似语义对（请假/年假）余弦应显著高于相异对（请假/食堂）
        double simSimilar = cosine(a, b);
        double simDifferent = cosine(a, c);
        System.out.println("sim(请假,年假)=" + simSimilar + "  sim(请假,食堂)=" + simDifferent);
        assertThat(simSimilar).isGreaterThan(simDifferent);
        assertThat(simSimilar).isGreaterThan(0.5);
    }

    private static float[] embedOne(LocalEmbeddingConfig.LocalBgeEmbeddingModel model, String text) {
        return model.call(new EmbeddingRequest(List.of(text), null)).getResult().getOutput();
    }

    private static double cosine(float[] x, float[] y) {
        double xx = 0, yy = 0, xy = 0;
        for (int i = 0; i < x.length; i++) {
            xx += x[i] * x[i];
            yy += y[i] * y[i];
            xy += x[i] * y[i];
        }
        return xy / (Math.sqrt(xx) * Math.sqrt(yy));
    }
}