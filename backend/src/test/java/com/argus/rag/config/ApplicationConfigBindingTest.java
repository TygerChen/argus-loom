package com.argus.rag.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.env.PropertySource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * P1-07 配置键绑定自检：直接加载各 profile 的 YAML 并断言关键键路径。
 *
 * <p>背景：曾经 llm / excel（dev 里还有 servlet）缩进错位——dev 挂在 elasticsearch 名下、
 * local/prod 挂在根层级，而代码读取的是 {@code argus.rag.llm.*} / {@code argus.rag.excel.*} /
 * {@code spring.servlet.multipart.*}，导致配置永不生效、只能走代码默认值。
 * 本测试锁住「键必须出现在代码读取的路径上」，配置再错位即红。</p>
 */
@DisplayName("配置键绑定自检（P1-07）")
class ApplicationConfigBindingTest {

    private static final List<String> PROFILES = List.of(
            "application-dev.yml", "application-local.yml", "application-prod.yml");

    private PropertySource<?> load(String fileName) throws Exception {
        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
        List<PropertySource<?>> sources = loader.load(fileName, new ClassPathResource(fileName));
        assertNotNull(sources, "YAML 应能解析: " + fileName);
        return sources.get(0);
    }

    @Test
    void llmAndExcelKeysBindUnderArgusRagInAllProfiles() throws Exception {
        for (String profile : PROFILES) {
            PropertySource<?> ps = load(profile);
            assertNotNull(ps.getProperty("argus.rag.llm.timeout-seconds"),
                    profile + " 缺少 argus.rag.llm.timeout-seconds");
            assertNotNull(ps.getProperty("argus.rag.llm.max-retries"),
                    profile + " 缺少 argus.rag.llm.max-retries");
            assertNotNull(ps.getProperty("argus.rag.excel.upload-path"),
                    profile + " 缺少 argus.rag.excel.upload-path");
            // 不允许再出现历史错位路径
            assertNull(ps.getProperty("elasticsearch.llm.timeout-seconds"),
                    profile + " 出现错位键 elasticsearch.llm.*");
        }
    }

    @Test
    void devProfileLlmAndUploadPathTakeEffect() throws Exception {
        PropertySource<?> ps = load("application-dev.yml");
        assertEquals(45, ((Number) ps.getProperty("argus.rag.llm.timeout-seconds")).intValue());
        assertEquals(3, ((Number) ps.getProperty("argus.rag.llm.max-retries")).intValue());
        // upload-path 支持环境变量覆盖（占位符由运行时解析），这里锁默认值仍指向 ./upload
        String uploadPath = String.valueOf(ps.getProperty("argus.rag.excel.upload-path"));
        org.junit.jupiter.api.Assertions.assertTrue(uploadPath.contains("./upload"),
                "upload-path 默认值应指向 ./upload（支持 ARGUS_EXCEL_UPLOAD_PATH 覆盖），实际: " + uploadPath);
    }

    @Test
    void devMultipartUnderSpringServlet() throws Exception {
        PropertySource<?> ps = load("application-dev.yml");
        assertEquals("100MB", ps.getProperty("spring.servlet.multipart.max-file-size"));
        assertEquals("100MB", ps.getProperty("spring.servlet.multipart.max-request-size"));
    }

    @Test
    void websocketOriginsDeclaredInAllProfiles() throws Exception {
        for (String profile : PROFILES) {
            PropertySource<?> ps = load(profile);
            Object origins = ps.getProperty("argus.rag.websocket.allowed-origin-patterns");
            assertNotNull(origins, profile + " 缺少 argus.rag.websocket.allowed-origin-patterns");
            org.junit.jupiter.api.Assertions.assertTrue(origins.toString().contains("://"),
                    profile + " 的 WebSocket 来源应为 origin 列表: " + origins);
        }
    }

    @Test
    void baseKeysRemainIntact() throws Exception {
        PropertySource<?> ps = load("application-dev.yml");
        assertNotNull(ps.getProperty("argus.rag.auth.jwt-secret"));
        assertNotNull(ps.getProperty("elasticsearch.host"));
        assertNotNull(ps.getProperty("elasticsearch.index-name"));
    }
}
