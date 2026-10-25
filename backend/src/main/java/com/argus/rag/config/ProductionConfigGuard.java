package com.argus.rag.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.ConfigurableEnvironment;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 生产 profile 启动配置守卫：在 Spring 上下文刷新之前对关键凭据做 fail-fast 校验。
 *
 * <p>没有这道守卫时，缺失的环境变量要等到对应 Bean（数据源、MinIO 客户端等）
 * 创建时才以"Could not resolve placeholder"之类的堆栈失败，报错晚且难定位；
 * 空串、示例值（changeme/minioadmin 等）则根本不会失败，直接以弱凭据上线。
 * 本守卫把所有问题一次列全并给出可操作提示，且只在 {@code prod} profile 激活时运行，
 * 开发/本地/测试环境不受影响。</p>
 *
 * <p>注册于 {@code META-INF/spring.factories}（EnvironmentPostProcessor SPI），
 * 执行时序晚于 ConfigData 环境后处理（.env 导入、profile 解析均已完成），
 * 因此这里读到的值已经是最终生效值。凭据轮换与暴露面分析见
 * {@code docs/operations/credential-hygiene-audit.md}。</p>
 */
@Order(Ordered.LOWEST_PRECEDENCE)
public class ProductionConfigGuard implements EnvironmentPostProcessor {

    /** 生产必须显式提供的关键配置（与 application-prod.yml 的占位符一一对应）。 */
    static final List<String> REQUIRED_KEYS = List.of(
            "spring.datasource.url",
            "spring.datasource.username",
            "spring.datasource.password",
            "spring.ai.openai.api-key",
            "storage.minio.endpoint",
            "storage.minio.access-key",
            "storage.minio.secret-key",
            "argus.rag.auth.jwt-secret",
            "elasticsearch.host");

    /** 出现在这些值上的凭据一律视为未替换的示例/默认值，拒绝以弱凭据启动。 */
    static final Set<String> REJECTED_VALUES = Set.of(
            "changeme", "changeit", "password", "admin123", "minioadmin",
            "local-dev", "your-access-key", "your-secret-key", "your-password");

    /** HS256 要求密钥 ≥ 256 位；jjwt 对过短密钥会在签发时才抛异常，提前在这里拦下。 */
    private static final int MIN_JWT_SECRET_LENGTH = 32;

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment,
                                       SpringApplication application) {
        if (!Arrays.asList(environment.getActiveProfiles()).contains("prod")) {
            return;
        }
        List<String> problems = new ArrayList<>();
        for (String key : REQUIRED_KEYS) {
            String value = resolveQuietly(environment, key);
            if (value == null || value.isBlank()) {
                problems.add(key + "：缺失、为空或环境变量未设置");
            } else if (REJECTED_VALUES.contains(value.trim().toLowerCase(Locale.ROOT))) {
                problems.add(key + "：仍是示例/默认值，必须替换为真实凭据");
            }
        }
        String jwtSecret = resolveQuietly(environment, "argus.rag.auth.jwt-secret");
        if (jwtSecret != null && !jwtSecret.isBlank()
                && jwtSecret.trim().length() < MIN_JWT_SECRET_LENGTH) {
            problems.add("argus.rag.auth.jwt-secret：长度不足 " + MIN_JWT_SECRET_LENGTH
                    + " 字符（HS256 需要 ≥256 位密钥，过短密钥签发时才失败，更难排查）");
        }
        if (!problems.isEmpty()) {
            throw new IllegalStateException(
                    "生产配置校验失败（prod profile），以下关键配置缺失或无效，请通过环境变量/.env 提供后再启动：\n - "
                            + String.join("\n - ", problems));
        }
    }

    /**
     * 占位符无法解析时（如 prod yml 引用了不存在的环境变量），
     * getProperty 会抛 IllegalArgumentException——这里转为"缺失"问题统一汇报，
     * 避免被 Spring 默认的占位符异常打断、一次只暴露一个错误。
     */
    private static String resolveQuietly(ConfigurableEnvironment environment, String key) {
        try {
            return environment.getProperty(key);
        } catch (IllegalArgumentException unresolvablePlaceholder) {
            return null;
        }
    }
}
