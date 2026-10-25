package com.argus.rag.qa.service;

import com.argus.rag.common.metrics.LlmDialogueMetrics;
import com.argus.rag.qa.model.EvidenceLevel;
import com.argus.rag.qa.model.vo.AskQuestionResponse;
import com.argus.rag.qa.rag.ReadyChunkDocumentRetriever;
import com.argus.rag.qa.rag.RetrievedEvidenceBundle;
import com.argus.rag.qa.support.CitationAssembler;
import com.argus.rag.qa.support.EvidenceOverviewAssembler;
import com.argus.rag.qa.support.QaAnswerParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.document.Document;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link QaChatService} 同步作答「证据门闸」契约（零 LLM 调用）。
 * <p>锁定 fall-closed：检索不到证据（空文档）或证据等级 INSUFFICIENT 时，
 * 直接返回 {@code RETRIEVAL_NOT_GROUNDED} 拒答，绝不发起大模型调用——
 * 证据是 LLM 的前置闸门。这两个分支不需要构造真实 ChatResponse，纯 mock 检索器 + 真埋点。</p>
 * <p>模型不可用归一化由既有 {@link QaChatServiceModelUnavailableTest} 覆盖；带证据的成功/格式回退路径
 * 需构造 Spring AI 1.1.2 的 ChatResponse 内部对象，版本易漂移，同 FinancePdfRenderer 先例不硬做。</p>
 */
@DisplayName("QA 同步证据门闸（QaChatService）")
class QaChatServiceAskTest {

    private ChatClient chatClient;
    private ReadyChunkDocumentRetriever documentRetriever;
    private QaChatService svc;

    @BeforeEach
    void setUp() {
        chatClient = mock(ChatClient.class);
        documentRetriever = mock(ReadyChunkDocumentRetriever.class);
        PromptTemplate promptTemplate = mock(PromptTemplate.class);
        QaAnswerParser answerParser = mock(QaAnswerParser.class);
        CitationAssembler citationAssembler = mock(CitationAssembler.class);
        EvidenceOverviewAssembler evidenceAssembler = mock(EvidenceOverviewAssembler.class);
        svc = new QaChatService(
                chatClient, promptTemplate, documentRetriever, answerParser,
                citationAssembler, evidenceAssembler, new ObjectMapper(),
                new LlmDialogueMetrics(new SimpleMeterRegistry()));
    }

    private static RetrievedEvidenceBundle bundleWith(EvidenceLevel level, List<Document> docs) {
        RetrievedEvidenceBundle b = mock(RetrievedEvidenceBundle.class);
        when(b.evidenceLevel()).thenReturn(level);
        when(b.documents()).thenReturn(docs);
        when(b.evidenceGuidance()).thenReturn("按证据回答");
        return b;
    }

    @Test
    void noEvidenceReturnsInsufficientWithoutCallingLlm() {
        RetrievedEvidenceBundle bundle = bundleWith(EvidenceLevel.INSUFFICIENT, List.of());
        when(documentRetriever.retrieveEvidence(1L, "q")).thenReturn(bundle);
        QaChatService.AskResult r = svc.askWithUsage(1L, "q");
        AskQuestionResponse res = r.response();
        assertFalse(res.answered());
        assertEquals("RETRIEVAL_NOT_GROUNDED", res.reasonCode());
        assertEquals("检索到的有效证据不足，暂不回答。", res.reasonMessage());
        assertEquals(EvidenceLevel.INSUFFICIENT, r.evidenceLevel());
        assertTrue(r.documents().isEmpty());
        assertEquals(0, r.usage().promptTokens());
        assertEquals(0, r.usage().completionTokens());
        assertEquals(0, r.usage().totalTokens());
        assertFalse(r.usage().estimated());
        // 关键：证据闸门阻止任何大模型调用
        verify(chatClient, never()).prompt(any(Prompt.class));
    }

    @Test
    void insufficientLevelShortCircuitsEvenWithDocuments() {
        // 有文档但证据等级仍判定 INSUFFICIENT → 同样拒答、不调 LLM
        RetrievedEvidenceBundle bundle = bundleWith(EvidenceLevel.INSUFFICIENT, List.of(
                Document.builder().id("d1").text("weak evidence").build()));
        when(documentRetriever.retrieveEvidence(2L, "warm")).thenReturn(bundle);
        AskQuestionResponse res = svc.ask(2L, "warm");
        assertFalse(res.answered());
        assertEquals("RETRIEVAL_NOT_GROUNDED", res.reasonCode());
        verify(chatClient, never()).prompt(any(Prompt.class));
    }

    @Test
    void askFacadeDelegatesToAskWithUsage() {
        RetrievedEvidenceBundle bundle = bundleWith(EvidenceLevel.INSUFFICIENT, List.of());
        when(documentRetriever.retrieveEvidence(3L, "q3")).thenReturn(bundle);
        AskQuestionResponse res = svc.ask(3L, "q3");   // 兼容旧签名 facade
        assertFalse(res.answered());
        assertEquals("RETRIEVAL_NOT_GROUNDED", res.reasonCode());
    }
}
