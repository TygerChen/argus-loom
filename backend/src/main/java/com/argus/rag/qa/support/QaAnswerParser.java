package com.argus.rag.qa.support;

import com.argus.rag.qa.model.KnowledgeAnswerOutput;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 问答回答解析器。
 * <p>
 * <b>注意（别误读主链路）：</b>这个类里 {@link #sanitizeJson} 才是被主线直接使用的——它负责把
 * 大模型输出的、可能带 Markdown 代码围栏或前后附言的原始文本，挑干净成一个可反序列化的 JSON。
 * 而 {@link #parse} 是<b>另一条链路</b>（非 RAG 主路径）在拿到原始文本时做反序列化兜底用的，
 * 并不是"主 RAG 一旦失败才来回退"的回退引擎。
 * </p>
 * <p>主链路（{@code QaChatService}）的用法是：直接调 {@code QaAnswerParser.sanitizeJson(...)}
 * 再用 {@code ObjectMapper.readValue} 解析；只有当这一步还失败时，才走它自己的 {@code fallbackParse}。</p>
 */
@Component
public class QaAnswerParser {

    private final ObjectMapper objectMapper;

    /**
     * 构造函数。
     *
     * @param objectMapper JSON 序列化/反序列化工具
     */
    public QaAnswerParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 解析大模型返回的原始 JSON 文本为结构化回答对象。
     *
     * @param rawAnswer 大模型返回的原始文本，应为 JSON 格式
     * @return 解析成功返回 {@link KnowledgeAnswerOutput}；输入为空或解析失败时返回 {@code null}
     * @see #sanitizeJson 剥壳用的静态工具，主线（{@code QaChatService}）直接用它
     */
    public KnowledgeAnswerOutput parse(String rawAnswer) {
        if (!StringUtils.hasText(rawAnswer)) {
            return null;
        }
        try {
            return objectMapper.readValue(sanitizeJson(rawAnswer), KnowledgeAnswerOutput.class);
        } catch (JsonProcessingException exception) {
            return null;
        }
    }

    /** 截取原始文本中的 JSON 对象，兼容模型输出 Markdown 代码围栏或前后附言。 */
    public static String sanitizeJson(String rawAnswer) {
        String text = rawAnswer.replaceAll("(?m)^```[A-Za-z]*\\s*", "").replaceAll("```", "").trim();
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end < 0 || end <= start) {
            return text;
        }
        return text.substring(start, end + 1);
    }
}
