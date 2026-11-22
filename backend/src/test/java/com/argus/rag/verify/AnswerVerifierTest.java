package com.argus.rag.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * 独立校验服务契约（第八步）：裁判 JSON→结果映射、分数仲裁（score 优先于布尔）、
 * 降级标注格式、JSON 失败纠偏重试一次后 fail-soft。
 */
@DisplayName("答案独立校验服务（AnswerVerifier）")
class AnswerVerifierTest {

    private VerifyClient client;
    private AnswerVerifier verifier;
    private String promptText;

    @BeforeEach
    void setUp() throws Exception {
        client = Mockito.mock(VerifyClient.class);
        try (var in = new ClassPathResource("prompts/verify/user.st").getInputStream()) {
            promptText = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        verifier = new AnswerVerifier(client, new ObjectMapper(), promptText, true);
    }

    private static final String SUPPORTED_JSON =
            "{\"supported\": true, \"score\": 92, \"unsupportedClaims\": [], \"reason\": \"结论均有证据支撑\"}";
    private static final String UNSUPPORTED_JSON =
            "{\"supported\": true, \"score\": 30, \"unsupportedClaims\": [\"10 天\", \"华东补充制度 2026\"], \"reason\": \"数字与适用性无证据\"}";

    @Test
    void supportedAnswerPassesWithoutAnnotation() {
        when(client.complete(anyString())).thenReturn(SUPPORTED_JSON);
        VerificationResult result = verifier.verify("年假几天", "10 天", List.of("证据A"));
        assertTrue(result.supported());
        assertEquals("答案正文", verifier.annotate("答案正文", result), "通过→原文返回，零改动");
    }

    @Test
    void lowScoreOverridesSupportedFlagAndAnnotates() {
        when(client.complete(anyString())).thenReturn(UNSUPPORTED_JSON);
        VerificationResult result = verifier.verify("年假几天", "10 天", List.of("证据A"));
        // 仲裁：score=30 < 60 → 以分数为准判不支持（数值比布尔更难被模型乱写）
        assertTrue(!result.supported());
        String annotated = verifier.annotate("答案正文", result);
        assertTrue(annotated.contains("独立核验提示"), "降级标注必须出现");
        assertTrue(annotated.contains("10 天") && annotated.contains("华东补充制度 2026"),
                "未获支撑的表述逐条亮给用户");
        assertTrue(annotated.startsWith("答案正文"), "标注追加在原文之后");
    }

    @Test
    void invalidJsonRetriesOnceThenSilent() {
        when(client.complete(anyString())).thenReturn("不是 JSON", "还不是 JSON");
        VerificationResult result = verifier.verify("问题", "答案", List.of("证据"));
        assertTrue(result.isEmpty(), "两次失败→空结果（不加标注，fail-soft）");
    }

    @Test
    void noEvidenceMeansUnsupported() {
        when(client.complete(anyString())).thenReturn(
                "{\"supported\": false, \"score\": 0, \"unsupportedClaims\": [\"全部\"], \"reason\": \"无证据\"}");
        VerificationResult result = verifier.verify("问题", "答案", List.of());
        assertTrue(!result.supported());
    }

    @Test
    void disabledOrBlankSkipsJudge() {
        AnswerVerifier off = new AnswerVerifier(client, new ObjectMapper(), promptText, false);
        assertTrue(off.verify("问题", "答案", List.of("证据")).isEmpty());
        assertTrue(verifier.verify("问题", "  ", List.of("证据")).isEmpty());
        Mockito.verifyNoInteractions(client);
    }
}
