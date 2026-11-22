package com.argus.rag.verify;

import com.argus.rag.qa.support.QaAnswerParser;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 答案独立校验服务 —— 第八步"验证结果"的编排层。
 *
 * <p><b>方案核心原则：独立校验，不自我背书</b>——生成者与裁判是<b>两次独立的
 * LLM 调用</b>：裁判只拿到「问题 + 答案 + 证据摘要」，不知晓生成过程的任何
 * 内部状态，从零对照证据逐条检查结论支撑/数字日期/适用性。</p>
 *
 * <p><b>失败处置（方案第六节）</b>：不丢弃答案——降级为"参考资料"：
 * 在回复上追加证据缺陷声明，把未获支撑的表述逐条亮给用户。
 * 修正重生成（重新走 Agent 循环）留作演进项——自动重生成有放大幻觉与
 * 延迟翻倍的风险，降级标注是当前更稳的兜底。</p>
 *
 * <p><b>失败语义</b>：校验关闭/裁判失败/输出不合法 → 空结果（不加标注，
 * 行为与从前一致）。校验本身绝不阻塞或改变主链路的成败。</p>
 */
@Service
public class AnswerVerifier {

    private static final Logger log = LoggerFactory.getLogger(AnswerVerifier.class);
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() { };
    private static final String RETRY_SUFFIX =
            "\n\n【重试要求】你上一次的输出不是合法 JSON。请严格只输出一个 JSON 对象，"
                    + "不要 Markdown 代码块，不要任何解释。";
    /** 可支撑度低于该阈值的答案视为"未获整体支撑"。 */
    private static final int SUPPORT_THRESHOLD = 60;
    private static final int MAX_CLAIMS = 5;
    private static final int MAX_EVIDENCE_BLOCKS = 5;
    private static final int MAX_ANSWER_CHARS = 3000;

    private final VerifyClient verifyClient;
    private final ObjectMapper objectMapper;
    private final String verifyPromptText;
    private final boolean enabled;

    public AnswerVerifier(
            VerifyClient verifyClient,
            ObjectMapper objectMapper,
            @Qualifier("verifyPromptText") String verifyPromptText,
            @Value("${argus.rag.assistant.verification.enabled:true}") boolean enabled
    ) {
        this.verifyClient = verifyClient;
        this.objectMapper = objectMapper;
        this.verifyPromptText = verifyPromptText;
        this.enabled = enabled;
    }

    /**
     * 独立校验答案与证据的一致性。
     *
     * @param question        用户原始问题
     * @param answer          待验证的答案文本
     * @param evidenceDigests 证据摘要（KB 检索留存；空=无证据可对照）
     * @return 校验结果；关闭/失败返回 {@link VerificationResult#empty()}
     */
    public VerificationResult verify(String question, String answer, List<String> evidenceDigests) {
        if (!enabled || question == null || question.isBlank()
                || answer == null || answer.isBlank()) {
            return VerificationResult.empty();
        }
        try {
            String evidence = renderEvidence(evidenceDigests);
            String prompt = verifyPromptText
                    .replace("{{question}}", question.trim())
                    .replace("{{answer}}", truncate(answer, MAX_ANSWER_CHARS))
                    .replace("{{evidence}}", evidence);

            Map<String, Object> parsed = parseOrNull(verifyClient.complete(prompt));
            if (parsed == null) {
                parsed = parseOrNull(verifyClient.complete(prompt + RETRY_SUFFIX));
            }
            if (parsed == null) {
                log.warn("答案校验输出两次均不合法 JSON，跳过标注");
                return VerificationResult.empty();
            }
            return toResult(parsed);
        } catch (RuntimeException verificationFailure) {
            log.warn("答案校验失败（不影响主流程）: {}", verificationFailure.getMessage());
            return VerificationResult.empty();
        }
    }

    /**
     * 按校验结果生成降级标注（追加到回复末尾；通过或空结果返回原文）。
     * <p>方案第六节：不能修正时降级——把结果作为参考资料、明确告知证据缺陷。</p>
     */
    public String annotate(String answer, VerificationResult result) {
        if (result == null || result.isEmpty() || result.supported()) {
            return answer;
        }
        StringBuilder sb = new StringBuilder(answer);
        sb.append("\n\n---\n> ⚠️ **独立核验提示**（可支撑度 ").append(result.score())
                .append("/100）：以下内容未能在已检索证据中找到直接支撑，请谨慎采信或要求重新检索：");
        List<String> claims = result.unsupportedClaims();
        if (claims == null || claims.isEmpty()) {
            sb.append("\n> - ").append(result.reason());
        } else {
            for (String claim : claims) {
                sb.append("\n> - ").append(claim);
            }
        }
        return sb.toString();
    }

    private String renderEvidence(List<String> evidenceDigests) {
        if (evidenceDigests == null || evidenceDigests.isEmpty()) {
            return "（无证据——答案必须整体标记为未获支撑）";
        }
        StringBuilder sb = new StringBuilder();
        int shown = Math.min(evidenceDigests.size(), MAX_EVIDENCE_BLOCKS);
        for (int i = 0; i < shown; i++) {
            sb.append("【证据").append(i + 1).append("】").append(evidenceDigests.get(i)).append('\n');
        }
        return sb.toString();
    }

    private VerificationResult toResult(Map<String, Object> parsed) {
        boolean supported = Boolean.TRUE.equals(parsed.get("supported"));
        int score = parsed.get("score") instanceof Number number
                ? Math.max(0, Math.min(100, number.intValue()))
                : (supported ? 100 : 0);
        List<String> claims = new ArrayList<>();
        if (parsed.get("unsupportedClaims") instanceof List<?> list) {
            for (Object item : list) {
                if (claims.size() >= MAX_CLAIMS) {
                    break;
                }
                if (item != null && !String.valueOf(item).isBlank()) {
                    claims.add(String.valueOf(item));
                }
            }
        }
        String reason = parsed.get("reason") == null ? "" : String.valueOf(parsed.get("reason"));
        // 仲裁：分数与 supported 标志不一致时，以分数为准（数值比布尔更难被模型乱写）
        if (score < SUPPORT_THRESHOLD) {
            supported = false;
        }
        return new VerificationResult(supported, score, claims, reason);
    }

    private Map<String, Object> parseOrNull(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            Map<String, Object> parsed = objectMapper.readValue(QaAnswerParser.sanitizeJson(raw), MAP_TYPE);
            return parsed == null || parsed.isEmpty() ? null : parsed;
        } catch (Exception parseFailure) {
            return null;
        }
    }

    private String truncate(String text, int maxChars) {
        return text.length() <= maxChars ? text : text.substring(0, maxChars) + "…";
    }
}
