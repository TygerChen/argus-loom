package com.argus.rag.prompt;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import jakarta.annotation.PostConstruct;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Prompt 模板管理器。
 * <p>从 resources/prompts/ 目录加载 YAML 模板文件，支持按名称获取和变量渲染。</p>
 * <p>文章依据：</p>
 * <ul>
 *   <li>第五部分第7节 — Prompt 应被模板化、版本化，不散落在代码字符串中</li>
 *   <li>第四部分第2节 — 模板适合重复执行的任务，变量由业务数据动态填充</li>
 * </ul>
 */
@Component
public class PromptTemplateManager {

    private static final Logger log = LoggerFactory.getLogger(PromptTemplateManager.class);

    private static final String TEMPLATE_LOCATION = "classpath*:/prompts/*.yml";

    private final Map<String, PromptTemplate> templates = new ConcurrentHashMap<>();

    /**
     * 启动时加载所有 Prompt 模板。
     */
    @PostConstruct
    public void loadTemplates() {
        try {
            PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
            Resource[] resources = resolver.getResources(TEMPLATE_LOCATION);
            Yaml yaml = new Yaml();

            for (Resource resource : resources) {
                String filename = resource.getFilename();
                if (filename == null) {
                    continue;
                }
                String name = filename.replace(".yml", "");

                try (InputStreamReader reader = new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8)) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> data = yaml.load(reader);
                    if (data == null) {
                        log.warn("Prompt template is empty: {}", filename);
                        continue;
                    }

                    String version = String.valueOf(data.getOrDefault("version", "0.0"));
                    String description = String.valueOf(data.getOrDefault("description", ""));
                    String content = String.valueOf(data.getOrDefault("content", ""));

                    PromptTemplate template = new PromptTemplate(name, version, description, content);
                    templates.put(name, template);
                    log.info("Loaded prompt template: {} (v{})", name, version);
                }
            }
            log.info("Prompt template loading complete. Total templates: {}", templates.size());
        } catch (Exception e) {
            log.error("Failed to load prompt templates", e);
        }
    }

    /**
     * 获取指定名称的 Prompt 模板。
     *
     * @param name 模板名称（不含 .yml 后缀）
     * @return 模板对象，不存在则返回 {@code null}
     */
    public PromptTemplate getTemplate(String name) {
        return templates.get(name);
    }

    /**
     * 获取模板并渲染。
     *
     * @param name      模板名称
     * @param variables 渲染变量
     * @return 渲染后的 Prompt 文本，模板不存在则返回空字符串
     */
    public String render(String name, Map<String, String> variables) {
        PromptTemplate template = templates.get(name);
        if (template == null) {
            log.error("Prompt template not found: {}", name);
            return "";
        }
        return template.render(variables);
    }

    /**
     * 获取已加载模板数量。
     */
    public int templateCount() {
        return templates.size();
    }

    /**
     * 是否已加载指定模板。
     */
    public boolean hasTemplate(String name) {
        return templates.containsKey(name);
    }
}