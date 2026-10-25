package com.argus.rag.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ProductionConfigGuard} 生产配置守卫契约（直接构造，MockEnvironment）：
 * - 非 prod profile：任何缺失都不拦（本地/开发/测试零影响）；
 * - prod + 全量缺省：抛 IllegalStateException，且一次列出全部缺失键（不是只报第一个）；
 * - prod + 示例值（changeme/minioadmin 等）：拒绝以弱凭据启动；
 * - prod + 短 JWT 密钥（<32 字符）：单独提示 HS256 密钥长度要求；
 * - prod + 合法配置：放行；
 * - 占位符无法解析（环境变量缺失）：按"缺失"归入问题清单，而不是让占位符异常打断汇总。
 */
@DisplayName("生产配置守卫（ProductionConfigGuard）")
class ProductionConfigGuardTest {

    private final ProductionConfigGuard guard = new ProductionConfigGuard();
    private final SpringApplication application = new SpringApplication(Object.class); // 仅占位，不真正运行

    private MockEnvironment prodEnv() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("prod");
        return env;
    }

    private MockEnvironment completeEnv() {
        MockEnvironment env = prodEnv();
        env.setProperty("spring.datasource.url", "jdbc:postgresql://db:5432/argus");
        env.setProperty("spring.datasource.username", "argus");
        env.setProperty("spring.datasource.password", "s7rong-D\\b-pass");
        env.setProperty("spring.ai.openai.api-key", "sk-real-production-key");
        env.setProperty("storage.minio.endpoint", "http://minio:9000");
        env.setProperty("storage.minio.access-key", "argus-prod");
        env.setProperty("storage.minio.secret-key", "m1n10-s3cret-value");
        env.setProperty("argus.rag.auth.jwt-secret", "0123456789abcdef0123456789abcdef-PROD");
        env.setProperty("elasticsearch.host", "es:9200");
        return env;
    }

    @Test
    void nonProdProfileIsNeverBlocked() {
        MockEnvironment env = new MockEnvironment(); // 无任何配置、无 prod profile
        assertDoesNotThrow(() -> guard.postProcessEnvironment(env, application));
        env.setActiveProfiles("dev", "local");
        assertDoesNotThrow(() -> guard.postProcessEnvironment(env, application));
    }

    @Test
    void prodWithAllMissingListsEveryKey() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> guard.postProcessEnvironment(prodEnv(), application));
        for (String key : ProductionConfigGuard.REQUIRED_KEYS) {
            assertTrue(ex.getMessage().contains(key), "应列出缺失键 " + key + "，实际=" + ex.getMessage());
        }
    }

    @Test
    void prodRejectsPlaceholderDefaults() {
        MockEnvironment env = completeEnv();
        env.setProperty("spring.datasource.password", "changeme");
        env.setProperty("storage.minio.access-key", "minioadmin");
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> guard.postProcessEnvironment(env, application));
        assertTrue(ex.getMessage().contains("spring.datasource.password"));
        assertTrue(ex.getMessage().contains("storage.minio.access-key"));
    }

    @Test
    void prodRejectsShortJwtSecret() {
        MockEnvironment env = completeEnv();
        env.setProperty("argus.rag.auth.jwt-secret", "short-secret");
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> guard.postProcessEnvironment(env, application));
        assertTrue(ex.getMessage().contains("argus.rag.auth.jwt-secret"));
        assertTrue(ex.getMessage().contains("32"));
    }

    @Test
    void prodWithCompleteConfigPasses() {
        assertDoesNotThrow(() -> guard.postProcessEnvironment(completeEnv(), application));
    }

    @Test
    void unresolvablePlaceholderCountsAsMissingNotCrash() {
        MockEnvironment env = prodEnv();
        // 值本身就是未加默认值的占位符：解析时抛 IAE，应归入"缺失"清单而不是让异常直接炸出
        env.setProperty("spring.datasource.url", "${DB_URL}");
        env.setProperty("elasticsearch.host", "${ES_HOST}");
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> guard.postProcessEnvironment(env, application));
        assertTrue(ex.getMessage().contains("spring.datasource.url"));
        assertTrue(ex.getMessage().contains("elasticsearch.host"));
        // 未设置的键也一并列出（一次看全，而不是只报第一个）
        assertTrue(ex.getMessage().contains("spring.datasource.password"));
    }
}
