package com.argus.rag.assistant.service;

import com.argus.rag.assistant.agent.AssistantAgentFacade;
import com.argus.rag.assistant.model.dto.chat.AssistantChatRequest;
import com.argus.rag.assistant.model.enums.AssistantMessageRole;
import com.argus.rag.assistant.model.enums.AssistantToolMode;
import com.argus.rag.assistant.model.enums.KbRouteStrategy;
import com.argus.rag.assistant.model.vo.chat.AssistantAgentResult;
import com.argus.rag.assistant.model.vo.chat.AssistantChatResponse;
import com.argus.rag.assistant.model.vo.chat.AssistantChatStreamEvent;
import com.argus.rag.assistant.model.vo.message.AssistantMessageVO;
import com.argus.rag.auth.CurrentUserService;
import com.argus.rag.common.exception.BusinessException;
import com.argus.rag.common.exception.ErrorCodes;
import com.argus.rag.group.service.GroupMembershipService;
import com.argus.rag.metrics.LlmEndpoint;
import com.argus.rag.metrics.LlmModule;
import com.argus.rag.metrics.collector.LlmUsageCollector;
import com.argus.rag.metrics.cost.LlmCostCalculator;
import com.argus.rag.metrics.model.dto.LlmUsageRecordDTO;
import com.argus.rag.qa.route.KnowledgeGroupRouter;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link AssistantService} 助手编排门面契约（mock 8 协作方，真实 ObjectMapper，纯 JVM/零 DB/零 LLM）：
 * - requireChatRequest：null→「聊天请求不能为空」、CHAT 禁止 groupId/groupIds、KB_SEARCH 手动模式必须传组；
 * - resolveKnowledgeScope：CHAT→空范围且不触碰路由/成员；KB 手动→显式范围并逐组 requireGroupReadable；
 *   KB 自动→router.route 映射 groupIds、空路由结果→「当前没有可用于检索的知识库…」；
 * - chat：保存用户消息→调用 Agent→保存助手消息→组装响应；任一步失败统一包装 MODEL_UNAVAILABLE；
 *   finally 记录用量（模块/端点/会话/估算/成本/成功标志/错误信息）；
 * - streamChat：start→delta*→done 事件序列（3-arg 自演 + 2-arg HTTP 委托 facade.streamChat）；
 *   失败统一抛 MODEL_UNAVAILABLE 并记录失败用量。
 */
@DisplayName("助手编排门面（AssistantService）")
class AssistantServiceTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 18, 12, 0);

    private AssistantConversationService conversationService;
    private AssistantAgentFacade agentFacade;
    private GroupMembershipService membershipService;
    private CurrentUserService currentUserService;
    private KnowledgeGroupRouter router;
    private ObjectMapper objectMapper;
    private LlmUsageCollector usageCollector;
    private LlmCostCalculator costCalculator;
    private HttpServletRequest request;
    private AssistantStreamEventEmitter emitter;
    private AssistantService svc;

    @BeforeEach
    void setUp() {
        conversationService = mock(AssistantConversationService.class);
        agentFacade = mock(AssistantAgentFacade.class);
        membershipService = mock(GroupMembershipService.class);
        currentUserService = mock(CurrentUserService.class);
        router = mock(KnowledgeGroupRouter.class);
        objectMapper = new ObjectMapper();
        usageCollector = mock(LlmUsageCollector.class);
        costCalculator = mock(LlmCostCalculator.class);
        request = mock(HttpServletRequest.class);
        emitter = mock(AssistantStreamEventEmitter.class);
        svc = new AssistantService(
                conversationService, agentFacade, membershipService, currentUserService,
                router, objectMapper, usageCollector, costCalculator);
        when(currentUserService.requireBusinessUser()).thenReturn(
                new CurrentUserService.CurrentUser(7L, "u7", "显示名"));
        when(costCalculator.calculate(anyString(), anyInt(), anyInt()))
                .thenReturn(new BigDecimal("0.0500"));
    }

    // ---------- helpers ----------

    private static AssistantChatRequest chatReq(AssistantToolMode mode, Long groupId, List<Long> groupIds,
                                                KbRouteStrategy route) {
        return new AssistantChatRequest(9L, "查报表", mode, groupId, groupIds, route);
    }

    private static AssistantMessageVO userVO() {
        return new AssistantMessageVO(50L, 9L, AssistantMessageRole.USER, AssistantToolMode.CHAT,
                null, List.of(), null, "查报表", null, NOW);
    }

    private static AssistantMessageVO assistantVO(Long id, String reply) {
        return new AssistantMessageVO(id, 9L, AssistantMessageRole.ASSISTANT, AssistantToolMode.CHAT,
                null, List.of(), null, reply, null, NOW);
    }

    private void stubAgentOk(String reply) {
        when(agentFacade.chat(any(), any(), any(), any(), any(), any()))
                .thenReturn(new AssistantAgentResult(reply, List.of()));
    }

    private void stubConversation() {
        when(conversationService.saveUserMessage(any(), any())).thenReturn(userVO());
        when(conversationService.saveAssistantMessage(any(), any()))
                .thenReturn(assistantVO(11L, "回答"));
    }

    private LlmUsageRecordDTO lastRecord() {
        ArgumentCaptor<LlmUsageRecordDTO> cap = ArgumentCaptor.forClass(LlmUsageRecordDTO.class);
        verify(usageCollector).record(cap.capture());
        return cap.getValue();
    }

    // ---------- requireChatRequest / resolveKnowledgeScope ----------

    @Test
    void nullChatRequestRejected() {
        BusinessException e = assertThrows(BusinessException.class, () -> svc.chat(request, null));
        assertEquals("聊天请求不能为空", e.getMessage());
    }

    @Test
    void chatModeRejectsGroupId() {
        BusinessException e = assertThrows(BusinessException.class,
                () -> svc.chat(request, chatReq(AssistantToolMode.CHAT, 5L, null, null)));
        assertEquals("CHAT 模式不允许传 groupId", e.getMessage());
    }

    @Test
    void chatModeRejectsGroupIds() {
        BusinessException e = assertThrows(BusinessException.class,
                () -> svc.chat(request, chatReq(AssistantToolMode.CHAT, null, List.of(5L), null)));
        assertEquals("CHAT 模式不允许传 groupId", e.getMessage());
    }

    @Test
    void kbSearchSelectedRequiresAtLeastOneGroup() {
        BusinessException e = assertThrows(BusinessException.class,
                () -> svc.chat(request,
                        chatReq(AssistantToolMode.KB_SEARCH, null, List.of(), KbRouteStrategy.SELECTED)));
        assertEquals("KB_SEARCH 手动模式必须至少传一个 groupId", e.getMessage());
    }

    @Test
    void kbSearchAutoRoutedEmptyRejected() {
        when(router.route(7L, "查报表", List.of())).thenReturn(List.of());
        BusinessException e = assertThrows(BusinessException.class,
                () -> svc.chat(request,
                        chatReq(AssistantToolMode.KB_SEARCH, null, List.of(), KbRouteStrategy.AUTO)));
        assertEquals("当前没有可用于检索的知识库，请先在知识库管理中上传文档。", e.getMessage());
    }

    // ---------- chat 编排 ----------

    @Test
    void chatChatModeSuccessAndRecordsUsage() {
        stubConversation();
        stubAgentOk("回答");
        AssistantChatResponse res = svc.chat(request, chatReq(AssistantToolMode.CHAT, null, null, null));
        assertEquals("回答", res.reply());
        assertEquals(11L, res.messageId());
        assertEquals(List.of(), res.groupIds());
        assertNull(res.groupId());
        verify(router, never()).route(any(), any(), any());
        verify(membershipService, never()).requireGroupReadable(any());

        LlmUsageRecordDTO rec = lastRecord();
        assertEquals(7L, rec.getUserId());
        assertEquals(LlmModule.ASSISTANT, rec.getModule());
        assertEquals(LlmEndpoint.ASSISTANT_CHAT, rec.getEndpoint());
        assertEquals("9", rec.getSessionId());
        assertEquals(Boolean.TRUE, rec.getSuccess());
        assertEquals(Boolean.TRUE, rec.getIsEstimated());
        assertEquals("qwen-plus", rec.getModelName());
        assertEquals(0, new BigDecimal("0.0500").compareTo(rec.getCostAmount()));
    }

    @Test
    void chatKbSelectedReadsGroupsAndStaysManual() {
        stubConversation();
        stubAgentOk("回答");
        AssistantChatResponse res = svc.chat(request,
                chatReq(AssistantToolMode.KB_SEARCH, 5L, List.of(5L, 6L), KbRouteStrategy.SELECTED));
        verify(membershipService).requireGroupReadable(5L);
        verify(membershipService).requireGroupReadable(6L);
        verify(router, never()).route(any(), any(), any());
        assertEquals(List.of(5L, 6L), res.groupIds());
        assertEquals(5L, res.groupId());
        assertEquals(KbRouteStrategy.SELECTED, res.routeStrategy());
        assertEquals(5L, lastRecord().getGroupId());
    }

    @Test
    void chatKbAutoResolvesRoutedGroups() {
        stubConversation();
        stubAgentOk("回答");
        when(router.route(eq(7L), anyString(), anyList())).thenReturn(List.of(
                new KnowledgeGroupRouter.RoutedKnowledgeGroup(2L, "g2", 0.9, "描述命中"),
                new KnowledgeGroupRouter.RoutedKnowledgeGroup(3L, "g3", 0.8, "文档名命中")
        ));
        AssistantChatResponse res = svc.chat(request,
                chatReq(AssistantToolMode.KB_SEARCH, null, List.of(1L), KbRouteStrategy.AUTO));
        verify(membershipService).requireGroupReadable(1L);
        assertEquals(List.of(2L, 3L), res.groupIds());
        assertEquals(2L, res.groupId());
        assertEquals(KbRouteStrategy.AUTO, res.routeStrategy());
    }

    @Test
    void failureWrappedAsUnavailableAndRecordedAsFailed() {
        stubConversation();
        when(agentFacade.chat(any(), any(), any(), any(), any(), any()))
                .thenThrow(new RuntimeException("llm down"));
        BusinessException e = assertThrows(BusinessException.class,
                () -> svc.chat(request, chatReq(AssistantToolMode.CHAT, null, null, null)));
        assertEquals(ErrorCodes.MODEL_UNAVAILABLE, e.getCode());
        LlmUsageRecordDTO rec = lastRecord();
        assertEquals(Boolean.FALSE, rec.getSuccess());
        assertEquals("llm down", rec.getErrorMessage());
    }

    @Test
    void subscriberFailureAlsoWrappedAsUnavailable() {
        when(conversationService.saveUserMessage(any(), any()))
                .thenThrow(new BusinessException("消息保存失败"));
        stubAgentOk("回答");
        BusinessException e = assertThrows(BusinessException.class,
                () -> svc.chat(request, chatReq(AssistantToolMode.CHAT, null, null, null)));
        assertEquals(ErrorCodes.MODEL_UNAVAILABLE, e.getCode());
    }

    // ---------- 流式 ----------

    @Test
    void streamChatEmitsStartDeltaDone() {
        stubConversation();
        AssistantChatRequest req = chatReq(AssistantToolMode.CHAT, null, null, null);
        svc.streamChat(req, emitter, deltaConsumer -> {
            deltaConsumer.accept("你");
            deltaConsumer.accept("好");
            return new AssistantAgentResult("你好", List.of());
        });
        List<AssistantChatStreamEvent> events = capturedEvents(4);
        assertEquals("start", events.get(0).event());
        assertEquals("delta", events.get(1).event());
        assertEquals("你", events.get(1).delta());
        assertEquals("delta", events.get(2).event());
        assertEquals("好", events.get(2).delta());
        assertEquals("done", events.get(3).event());
        assertEquals("你好", events.get(3).reply());
    }

    @Test
    void httpStreamChatDelegatesViaFacadeStreaming() {
        stubConversation();
        when(agentFacade.streamChat(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenAnswer(inv -> {
                    var dc = (java.util.function.Consumer<String>) inv.getArgument(6);
                    dc.accept("片段");
                    return new AssistantAgentResult("片段", List.of());
                });
        svc.streamChat(request, chatReq(AssistantToolMode.CHAT, null, null, null), emitter);
        verify(agentFacade).streamChat(any(), any(), any(), any(), any(), any(), any(), any());
        List<AssistantChatStreamEvent> events = capturedEvents(3);
        assertEquals("start", events.get(0).event());
        assertEquals("delta", events.get(1).event());
        assertEquals("done", events.get(2).event());
    }

    @Test
    void streamFailureThrowsUnavailableAndRecordsFailure() {
        stubConversation();
        AssistantChatRequest req = chatReq(AssistantToolMode.CHAT, null, null, null);
        BusinessException e = assertThrows(BusinessException.class,
                () -> svc.streamChat(req, emitter, deltaConsumer -> {
                    throw new RuntimeException("stream boom");
                }));
        assertEquals(ErrorCodes.MODEL_UNAVAILABLE, e.getCode());
        LlmUsageRecordDTO rec = lastRecord();
        assertEquals(Boolean.FALSE, rec.getSuccess());
        assertEquals("stream boom", rec.getErrorMessage());
    }

    private List<AssistantChatStreamEvent> capturedEvents(int expectedEmits) {
        ArgumentCaptor<AssistantChatStreamEvent> captor = ArgumentCaptor.forClass(AssistantChatStreamEvent.class);
        verify(emitter, times(expectedEmits)).emit(captor.capture());
        return captor.getAllValues();
    }
}