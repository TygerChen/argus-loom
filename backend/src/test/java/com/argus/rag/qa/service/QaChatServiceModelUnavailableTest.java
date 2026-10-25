package com.argus.rag.qa.service;

import com.argus.rag.common.exception.BusinessException;
import com.argus.rag.common.exception.ErrorCodes;
import com.argus.rag.common.metrics.LlmDialogueMetrics;
import com.argus.rag.qa.model.EvidenceLevel;
import com.argus.rag.qa.rag.ReadyChunkDocumentRetriever;
import com.argus.rag.qa.rag.RetrievedEvidenceBundle;
import com.argus.rag.qa.support.CitationAssembler;
import com.argus.rag.qa.support.EvidenceOverviewAssembler;
import com.argus.rag.qa.support.QaAnswerParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.document.Document;

import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * QA 同步路径「模型不可用」归一化契约。
 *
 * <p>锁定修复：{@link QaChatService#getStructuredAnswer} 将「LLM 调用本身失败」与
 * 「JSON 解析失败」分离——模型调用失败现在直接抛带 {@code MODULE_UNAVAILABLE} 码的
 * {@link BusinessException}，经全局异常处理透成 {@code ApiResponse.code}，
 * 而不是被误标为「回答格式错误」。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("QA 同步路径模型不可用归一化")
class QaChatServiceModelUnavailableTest {

    @Mock
    private ChatClient chatClient;
    @Mock
    private PromptTemplate promptTemplate;
    @Mock
    private ReadyChunkDocumentRetriever documentRetriever;
    @Mock
    private QaAnswerParser answerParser;
    @Mock
    private CitationAssembler citationAssembler;
    @Mock
    private EvidenceOverviewAssembler evidenceOverviewAssembler;

    private QaChatService newService() {
        return new QaChatService(
                chatClient, promptTemplate, documentRetriever, answerParser,
                citationAssembler, evidenceOverviewAssembler, new ObjectMapper(),
                new LlmDialogueMetrics(new SimpleMeterRegistry()));
    }

    @Test
    @DisplayName("LLM 调用异常应抛 MODEL_UNAVAILABLE（而非误标格式错误）")
    void modelCallFailureShouldThrowModelUnavailableCode() {
        RetrievedEvidenceBundle bundle = evidenceWith(EvidenceLevel.SUFFICIENT);
        when(documentRetriever.retrieveEvidence(1L, "q")).thenReturn(bundle);
        when(promptTemplate.create(any(java.util.Map.class)))
                .thenReturn(new org.springframework.ai.chat.prompt.Prompt("q"));

        var spec = mock(ChatClient.ChatClientRequestSpec.class);
        var call = mock(ChatClient.CallResponseSpec.class);
        when(chatClient.prompt(any(org.springframework.ai.chat.prompt.Prompt.class))).thenReturn(spec);
        when(spec.advisors(any(Consumer.class))).thenReturn(spec);
        when(spec.call()).thenReturn(call);
        when(call.chatResponse()).thenThrow(new RuntimeException("simulated connection refused"));

        QaChatService service = newService();

        // askWithUsage 走到模型调用失败：应抛带 MODEL_UNAVAILABLE 的 BusinessException。
        assertThatThrownBy(() -> service.askWithUsage(1L, "q"))
                .isInstanceOfSatisfying(BusinessException.class, ex -> {
                    assertEquals(ErrorCodes.MODEL_UNAVAILABLE, ex.getCode());
                    assertEquals("模型服务暂时不可用，请稍后重试。", ex.getMessage());
                });
    }

    private RetrievedEvidenceBundle evidenceWith(EvidenceLevel level) {
        RetrievedEvidenceBundle bundle = mock(RetrievedEvidenceBundle.class);
        when(bundle.evidenceLevel()).thenReturn(level);
        when(bundle.evidenceGuidance()).thenReturn("按证据回答");
        when(bundle.documents()).thenReturn(List.of(
                Document.builder().id("d1").text("group policy").build()));
        return bundle;
    }
}