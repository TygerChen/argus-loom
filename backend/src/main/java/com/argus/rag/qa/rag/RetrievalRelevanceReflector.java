package com.argus.rag.qa.rag;

import com.argus.rag.qa.support.QaAnswerParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 检索后反思器（CRAG / Self-RAG 思想，第三步优化）——生成前的"语义相关性"裁判。
 *
 * <p><b>它补上 {@link EvidenceAssessor} 的盲区</b>：后者只看分数/通道交叉命中做
 * 分级（弱信号），抓不住"向量检索召回了字面相近但语义跑题"的段落。本类让模型
 * 对每条证据做<b>语义相关性</b>判断（强信号），把跑题噪音在进大模型之前滤掉。</p>
 *
 * <p><b>模型只给标签，代码决定执行</b>（与第八步独立裁判、第十一步确认门同一哲学）：
 * 模型输出"哪些证据相关 + 全无关时的改写检索词"，<b>不</b>决定过滤/二次检索/拒答——
 * 这些动作由 {@code QaChatService} 按本类返回的 {@link ReflectionResult} 执行。</p>
 *
 * <p><b>fail-soft</b>：反思调用/解析失败 → 退化为"保留全部证据"（行为等同未开启反思），
 * 绝不因反思器故障阻断主链路。</p>
 */
@Component
public class RetrievalRelevanceReflector {

    private static final Logger log = LoggerFactory.getLogger(RetrievalRelevanceReflector.class);

    /** 每条证据送入反思的字符上限（截断控制 token，相关性判断只需前段语义）。 */
    private static final int MAX_EVIDENCE_TEXT_CHARS = 800;

    private final ObjectMapper objectMapper;

    public RetrievalRelevanceReflector(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 反思结果。
     *
     * @param keepIds     语义相关的证据编号集合（已合法化为真实存在的证据 id）
     * @param rewriteQuery 全无关时模型给的改写检索词；null 表示"问题本身超出知识库范围"
     */
    public record ReflectionResult(Set<String> keepIds, String rewriteQuery) {

        /** 是否全部证据都不相关（应触发二次检索或拒答）。 */
        public boolean allIrrelevant() {
            return keepIds == null || keepIds.isEmpty();
        }

        /** 是否给出了可用的改写检索词（用于二次检索）。 */
        public boolean hasRewrite() {
            return StringUtils.hasText(rewriteQuery);
        }
    }

    /**
     * 对检索证据做语义相关性反思。
     *
     * @param chatClient 生效的 ChatClient（调用方传入，复用用户级模型覆盖）
     * @param question   用户问题
     * @param documents  检索证据（每条证据的 {@code id} 即反思编号 "E1" "E2"...）
     * @return 反思结果；失败时退化为保留全部证据
     */
    public ReflectionResult reflect(ChatClient chatClient, String question, List<Document> documents) {
        if (chatClient == null || documents == null || documents.isEmpty()) {
            return keepAll(documents);
        }
        String prompt = buildReflectionPrompt(question, documents);
        try {
            String raw = chatClient.prompt().user(prompt).call().content();
            if (!StringUtils.hasText(raw)) {
                return keepAll(documents);
            }
            return parse(raw, documents);
        } catch (Exception failure) {
            log.warn("检索反思失败，退化为保留全部证据: {}", failure.getMessage());
            return keepAll(documents);
        }
    }

    /** 构建纯文本反思提示词（不用 PromptTemplate，规避 ST4 花括号坑）。 */
    private String buildReflectionPrompt(String question, List<Document> documents) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是检索质量评估器。判断下面每条证据是否真的与用户问题「语义相关」")
                .append("（而非字面相近但语义跑题）。\n\n");
        sb.append("用户问题：").append(question).append("\n\n");
        sb.append("证据列表：\n");
        for (Document document : documents) {
            String text = document.getText() == null ? "" : document.getText().replaceAll("\\s+", " ").trim();
            if (text.length() > MAX_EVIDENCE_TEXT_CHARS) {
                text = text.substring(0, MAX_EVIDENCE_TEXT_CHARS) + "…";
            }
            sb.append("[").append(document.getId()).append("] ").append(text).append("\n\n");
        }
        sb.append("只输出一个 JSON 对象，不要任何解释或 Markdown 围栏：\n");
        sb.append("{\"keepIds\": [\"E1\"], \"rewriteQuery\": null}\n\n");
        sb.append("规则：\n");
        sb.append("- keepIds：语义相关的证据编号数组（从上面 [编号] 里选）\n");
        sb.append("- 若所有证据都不相关：keepIds 为 []，rewriteQuery 给一个更可能命中的检索词；")
                .append("若问题本身超出知识库范围（不存在可改写的检索词），rewriteQuery 为 null\n");
        return sb.toString();
    }

    /** 解析反思 JSON；keepIds 合法化为真实存在的证据 id（防模型编造编号）。包可见便于单测。 */
    ReflectionResult parse(String raw, List<Document> documents) {
        try {
            String json = QaAnswerParser.sanitizeJson(raw);
            JsonNode node = objectMapper.readTree(json);
            Set<String> keepIds = new LinkedHashSet<>();
            JsonNode keepNode = node.get("keepIds");
            if (keepNode != null && keepNode.isArray()) {
                for (JsonNode id : keepNode) {
                    if (id != null && id.isTextual() && StringUtils.hasText(id.asText())) {
                        keepIds.add(id.asText().trim());
                    }
                }
            }
            String rewriteQuery = null;
            JsonNode rewriteNode = node.get("rewriteQuery");
            if (rewriteNode != null && rewriteNode.isTextual() && StringUtils.hasText(rewriteNode.asText())) {
                rewriteQuery = rewriteNode.asText().trim();
            }
            Set<String> validIds = documents.stream()
                    .map(Document::getId)
                    .filter(StringUtils::hasText)
                    .collect(Collectors.toSet());
            keepIds.retainAll(validIds);
            return new ReflectionResult(keepIds, rewriteQuery);
        } catch (Exception parseFailure) {
            log.warn("检索反思 JSON 解析失败，退化为保留全部证据: {}", parseFailure.getMessage());
            return keepAll(documents);
        }
    }

    /** 保留全部证据的降级结果（反思失败/空输入）。 */
    private ReflectionResult keepAll(List<Document> documents) {
        if (documents == null || documents.isEmpty()) {
            return new ReflectionResult(Set.of(), null);
        }
        Set<String> allIds = documents.stream()
                .map(Document::getId)
                .filter(StringUtils::hasText)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        return new ReflectionResult(allIds, null);
    }
}
