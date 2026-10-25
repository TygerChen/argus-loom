package com.argus.rag.prompt;

/**
 * Prompt 模板实体。
 * <p>对应 resources/prompts/ 目录下的 YAML 模板文件。</p>
 * <p>文章依据：Prompt 应像代码一样接受版本管理（第五部分第7节）。</p>
 *
 * @param name        模板名称（如 "assistant-base"）
 * @param version     版本号（如 "1.0"）
 * @param description 模板用途说明
 * @param content     模板正文内容
 */
public record PromptTemplate(
        String name,
        String version,
        String description,
        String content
) {
    /**
     * 渲染模板，替换变量占位符。
     *
     * @param variables 变量键值对
     * @return 渲染后的 Prompt 文本，未匹配的占位符保留原样
     */
    public String render(java.util.Map<String, String> variables) {
        return renderStatic(content, variables);
    }

    /**
     * 静态渲染方法，对任意模板字符串做变量替换。
     *
     * @param template  模板字符串，包含 {variable} 占位符
     * @param variables 变量键值对
     * @return 渲染后的文本
     */
    public static String renderStatic(String template, java.util.Map<String, String> variables) {
        String result = template;
        for (var entry : variables.entrySet()) {
            result = result.replace("{" + entry.getKey() + "}", entry.getValue());
        }
        return result;
    }
}