package com.argus.rag.config;

import com.argus.rag.config.ModelConfig.ModelOption;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ModelConfig#findByModelName(String)} 行为锁
 * （纯 JVM、public static 直调、零生产改动——受限模型目录本就是 public static final catalog）。
 * <p>锁契约：在 ALL_MODELS 里按 id 精确匹配（区分大小写），命中返回该 model；
 * 未知/空/大小写不符 → 回落到默认文本模型 TEXT_MODELS.get(0)（agnes-3.0-flash），绝不抛空。</p>
 */
@DisplayName("AI 模型目录查询（ModelConfig.findByModelName）")
class ModelConfigTest {

    @Test
    void knownTextModelResolvedById() {
        ModelOption found = ModelConfig.findByModelName("agnes-2.5-pro");
        assertEquals("agnes-2.5-pro", found.id());
        assertEquals("text", found.category());
    }

    @Test
    void imageAndVideoModelsResolved() {
        assertEquals("agnes-image-2.5-flash", ModelConfig.findByModelName("agnes-image-2.5-flash").id());
        assertEquals("video", ModelConfig.findByModelName("agnes-video-v2.0").category());
    }

    @Test
    void unknownModelFallsBackToDefaultTextModel() {
        ModelOption found = ModelConfig.findByModelName("no-such-model");
        assertEquals(ModelConfig.DEFAULT_MODEL, found.id());
        // 兜底即 TEXK_MODELS[0]，且是该目录唯一默认标记模型
        assertEquals(ModelConfig.TEXT_MODELS.get(0), found);
        assertTrue(found.isDefault());
    }

    @Test
    void blankModelNameFallsBackToDefault() {
        assertEquals(ModelConfig.DEFAULT_MODEL, ModelConfig.findByModelName("").id());
        assertEquals(ModelConfig.DEFAULT_MODEL, ModelConfig.findByModelName(null).id());
    }

    @Test
    void matchIsCaseSensitive() {
        // 大小写不符不命中 → 回落默认，不抛
        assertEquals(ModelConfig.DEFAULT_MODEL, ModelConfig.findByModelName("AGNES-3.0-flash").id());
    }

    @Test
    void defaultModelIsTheFirstTextModel() {
        assertNotNull(ModelConfig.TEXT_MODELS.get(0));
        assertEquals(ModelConfig.DEFAULT_MODEL, ModelConfig.TEXT_MODELS.get(0).id());
        assertFalse(ModelConfig.TEXT_MODELS.get(1).isDefault());
    }
}