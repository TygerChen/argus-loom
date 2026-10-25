package com.argus.rag.qa.service;

import com.argus.rag.auth.CurrentUserService;
import com.argus.rag.common.enums.SystemRole;
import com.argus.rag.group.service.GroupMembershipService;
import com.argus.rag.metrics.collector.LlmUsageCollector;
import com.argus.rag.metrics.cost.LlmCostCalculator;
import com.argus.rag.qa.model.EvidenceLevel;
import com.argus.rag.qa.model.dto.AskQuestionRequest;
import com.argus.rag.qa.model.vo.AskQuestionResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.document.Document;
import reactor.core.publisher.Flux;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("QA 服务")
class QaServiceTest {

    @Mock
    private GroupMembershipService groupMembershipService;
    @Mock
    private QaChatService qaChatService;
    @Mock
    private CurrentUserService currentUserService;
    @Mock
    private LlmUsageCollector llmUsageCollector;
    @Mock
    private LlmCostCalculator llmCostCalculator;
    @Mock
    private QaRecordPersistenceService qaRecordPersistenceService;

    @Test
    @DisplayName("同步问答应持久化记录并返回 recordId")
    void shouldPersistSyncAnswerAndReturnRecordId() {
        QaService qaService = new QaService(
                groupMembershipService,
                qaChatService,
                currentUserService,
                llmUsageCollector,
                llmCostCalculator,
                qaRecordPersistenceService
        );
        AskQuestionRequest request = new AskQuestionRequest();
        request.setGroupId(1L);
        request.setQuestion("文档上传流程是什么？");
        AskQuestionResponse response = AskQuestionResponse.answered("上传流程分为三步。", List.of());
        Document evidence = Document.builder()
                .id("E1")
                .text("上传流程分为三步。")
                .metadata(Map.of("fileName", "guide.pdf", "documentId", 10L, "chunkId", 100L))
                .build();

        when(currentUserService.getRequiredCurrentUser())
                .thenReturn(new CurrentUserService.CurrentUser(5L, "u001", "Alice", SystemRole.USER, false));
        when(qaChatService.askWithUsage(1L, "文档上传流程是什么？"))
                .thenReturn(new QaChatService.AskResult(
                        response,
                        new QaChatService.UsageInfo(10, 5, 15, false, 321L),
                        EvidenceLevel.SUFFICIENT,
                        List.of(evidence)
                ));
        when(qaRecordPersistenceService.saveCompleted(any())).thenReturn(88L);
        when(llmCostCalculator.calculate("qwen-plus", 10, 5)).thenReturn(BigDecimal.ZERO);

        AskQuestionResponse actual = qaService.ask(null, request);

        assertThat(actual.recordId()).isEqualTo(88L);
        assertThat(actual.answer()).isEqualTo("上传流程分为三步。");

        ArgumentCaptor<QaRecordPersistenceService.SaveCommand> commandCaptor =
                ArgumentCaptor.forClass(QaRecordPersistenceService.SaveCommand.class);
        verify(qaRecordPersistenceService).saveCompleted(commandCaptor.capture());
        QaRecordPersistenceService.SaveCommand command = commandCaptor.getValue();
        assertThat(command.userId()).isEqualTo(5L);
        assertThat(command.groupId()).isEqualTo(1L);
        assertThat(command.question()).isEqualTo("文档上传流程是什么？");
        assertThat(command.evidenceLevel()).isEqualTo(EvidenceLevel.SUFFICIENT);
        assertThat(command.evidenceDocuments()).containsExactly(evidence);
    }

    @Test
    @DisplayName("流式问答完成后应持久化完整回答并回填 recordId")
    void shouldPersistStreamAnswerAndExposeRecordId() {
        QaService qaService = new QaService(
                groupMembershipService,
                qaChatService,
                currentUserService,
                llmUsageCollector,
                llmCostCalculator,
                qaRecordPersistenceService
        );
        AskQuestionRequest request = new AskQuestionRequest();
        request.setGroupId(1L);
        request.setQuestion("How to upload a document?");
        Document evidence = Document.builder()
                .id("E1")
                .text("Upload documents from the group page.")
                .metadata(Map.of("fileName", "guide.pdf", "documentId", 10L, "chunkId", 100L))
                .build();

        when(currentUserService.getRequiredCurrentUser())
                .thenReturn(new CurrentUserService.CurrentUser(5L, "u001", "Alice", SystemRole.USER, false));
        when(qaChatService.askStream(eq(1L), eq("How to upload a document?"), any()))
                .thenAnswer(invocation -> {
                    @SuppressWarnings("unchecked")
                    Consumer<QaChatService.UsageInfo> callback = invocation.getArgument(2, Consumer.class);
                    Flux<String> tokenFlux = Flux.just("Upload ", "from group page.")
                            .doOnComplete(() -> callback.accept(
                                    new QaChatService.UsageInfo(12, 6, 18, false, 222L)));
                    return new QaChatService.StreamContext(
                            tokenFlux,
                            List.of(evidence),
                            EvidenceLevel.SUFFICIENT,
                            new AtomicReference<>());
                });
        when(qaRecordPersistenceService.saveCompleted(any())).thenReturn(89L);
        when(llmCostCalculator.calculate("qwen-plus", 12, 6)).thenReturn(BigDecimal.ZERO);

        QaChatService.StreamContext context = qaService.askStream(null, request);
        List<String> tokens = context.tokenStream().collectList().block();

        assertThat(tokens).containsExactly("Upload ", "from group page.");
        assertThat(context.recordId()).isEqualTo(89L);

        ArgumentCaptor<QaRecordPersistenceService.SaveCommand> commandCaptor =
                ArgumentCaptor.forClass(QaRecordPersistenceService.SaveCommand.class);
        verify(qaRecordPersistenceService).saveCompleted(commandCaptor.capture());
        QaRecordPersistenceService.SaveCommand command = commandCaptor.getValue();
        assertThat(command.userId()).isEqualTo(5L);
        assertThat(command.groupId()).isEqualTo(1L);
        assertThat(command.question()).isEqualTo("How to upload a document?");
        assertThat(command.response().answer()).isEqualTo("Upload from group page.");
        assertThat(command.evidenceLevel()).isEqualTo(EvidenceLevel.SUFFICIENT);
        assertThat(command.evidenceDocuments()).containsExactly(evidence);
    }

    /**
     * 「token 级重试幂等」契约：返回给上层的 token 流被多次订阅（重试/重连）时，
     * LLM 上游与 QA 记录落库等副作用必须只执行一次，不得因重复订阅重复插记录/重复计量。
     * cache() 固化一次源流执行，后续订阅仅重放 token 缓存。
     */
    @Test
    @DisplayName("流式 token 流被重订阅（重试）时持久化只发生一次、返回幂等")
    void shouldPersistOnlyOnceWhenStreamResubscribed() {
        QaService qaService = new QaService(
                groupMembershipService,
                qaChatService,
                currentUserService,
                llmUsageCollector,
                llmCostCalculator,
                qaRecordPersistenceService
        );
        AskQuestionRequest request = new AskQuestionRequest();
        request.setGroupId(1L);
        request.setQuestion("What is World Refund?");
        when(currentUserService.getRequiredCurrentUser())
                .thenReturn(new CurrentUserService.CurrentUser(7L, "u007", "Bob", SystemRole.USER, false));
        when(qaChatService.askStream(eq(1L), eq("What is World Refund?"), any()))
                .thenAnswer(invocation -> {
                    @SuppressWarnings("unchecked")
                    Consumer<QaChatService.UsageInfo> callback = invocation.getArgument(2, Consumer.class);
                    Flux<String> tokenFlux = Flux.just("The ", "answer")
                            .doOnComplete(() -> callback.accept(
                                    new QaChatService.UsageInfo(3, 5, 8, false, 55L)));
                    return new QaChatService.StreamContext(
                            tokenFlux,
                            List.of(),
                            EvidenceLevel.SUFFICIENT,
                            new AtomicReference<>());
                });
        when(qaRecordPersistenceService.saveCompleted(any())).thenReturn(90L);
        when(llmCostCalculator.calculate("qwen-plus", 3, 5)).thenReturn(BigDecimal.ZERO);

        QaChatService.StreamContext context = qaService.askStream(null, request);

        // 首次订阅：触发真实上游（LLM + 落库）。
        List<String> first = context.tokenStream().collectList().block();
        // 立即重订阅：模拟网络重试/SSE 重连。幂等墙下不得再次触达上游副作用。
        List<String> second = context.tokenStream().collectList().block();

        assertThat(first).containsExactly("The ", "answer");
        assertThat(second).containsExactly("The ", "answer");
        // 核心契约：无论订阅几次，QA 记录仅落一次；usage 计量同样只发生一次。
        verify(qaRecordPersistenceService, times(1)).saveCompleted(any());
        verify(currentUserService, times(1)).getRequiredCurrentUser();
    }
}
