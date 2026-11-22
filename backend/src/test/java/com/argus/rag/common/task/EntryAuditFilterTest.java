package com.argus.rag.common.task;

import com.argus.rag.common.enums.SystemRole;
import com.argus.rag.common.logging.TraceIdFilter;
import com.argus.rag.common.security.AuthenticatedUser;
import com.argus.rag.common.security.UserContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import jakarta.servlet.http.HttpServletRequest;

import java.util.EnumSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * 入口审计过滤器契约：激活通道记账、被拒请求留痕、非激活通道零干扰、
 * 业务层便签（身份声明/输入长度）并入票据。
 */
@DisplayName("入口审计过滤器（EntryAuditFilter）")
class EntryAuditFilterTest {

    private final EntryAuditService service = Mockito.mock(EntryAuditService.class);
    private final EntryAuditFilter filter =
            new EntryAuditFilter(service, EnumSet.of(TaskChannel.KB_QA, TaskChannel.ASSISTANT));

    @AfterEach
    void cleanUp() {
        UserContext.clear();
        MDC.clear();
    }

    @Test
    void recordsAcceptedRequestWithUserIdentity() throws Exception {
        MDC.put(TraceIdFilter.MDC_TRACE_ID, "trace1234567890");
        UserContext.set(new AuthenticatedUser(7L, "U0007", "张三", SystemRole.USER, false));
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/qa/ask");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> ((MockHttpServletResponse) res).setStatus(200));

        TaskTicket ticket = capturedTicket();
        assertEquals(TaskChannel.KB_QA, ticket.channel());
        assertEquals(TaskDecision.ACCEPTED, ticket.decision());
        assertEquals(200, ticket.statusCode());
        assertEquals(7L, ticket.userId());
        assertEquals("U0007", ticket.userCode());
        assertEquals(SystemRole.USER, ticket.systemRole());
        assertEquals("trace1234567890", ticket.traceId());
        assertEquals("POST /api/qa/ask", ticket.endpoint());
        assertNull(ticket.rejectCode());
        assertNull(ticket.taskId(), "入口阶段 taskId 恒为空");
    }

    @Test
    void recordsRejectedRequestWithRejectCode() throws Exception {
        UserContext.set(new AuthenticatedUser(7L, "U0007", "张三", SystemRole.USER, false));
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/assistant/chat");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> ((MockHttpServletResponse) res).setStatus(403));

        TaskTicket ticket = capturedTicket();
        assertEquals(TaskChannel.ASSISTANT, ticket.channel());
        assertEquals(TaskDecision.REJECTED, ticket.decision());
        assertEquals(403, ticket.statusCode());
        assertEquals("HTTP_403", ticket.rejectCode());
        assertEquals(7L, ticket.userId());
    }

    @Test
    void recordsUnauthenticatedRejectionWithoutUser() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/qa/ask");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> ((MockHttpServletResponse) res).setStatus(401));

        TaskTicket ticket = capturedTicket();
        assertEquals(TaskDecision.REJECTED, ticket.decision());
        assertEquals("HTTP_401", ticket.rejectCode());
        assertNull(ticket.userId(), "未认证请求不应有身份字段");
        assertNull(ticket.systemRole());
    }

    @Test
    void skipsInactiveChannelsCompletely() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/excel/upload");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> ((MockHttpServletResponse) res).setStatus(200));

        verifyNoInteractions(service);
    }

    @Test
    void skipsNonBusinessChannelsCompletely() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/auth/login");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> ((MockHttpServletResponse) res).setStatus(200));

        verifyNoInteractions(service);
    }

    @Test
    void notesEnrichmentFlowsToTicket() throws Exception {
        MDC.put(TraceIdFilter.MDC_TRACE_ID, "trace1234567890");
        UserContext.set(new AuthenticatedUser(7L, "U0007", "张三", SystemRole.USER, false));
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/qa/ask");
        MockHttpServletResponse response = new MockHttpServletResponse();

        // 模拟真实时序：过滤器先创建便签 → 控制器（链内）再补记输入长度与身份声明。
        filter.doFilter(request, response, (req, res) -> {
            EntryAuditSupport.inspectUserInput((HttpServletRequest) req, "我是华东区财务，帮我查华东区薪酬");
            ((MockHttpServletResponse) res).setStatus(200);
        });

        TaskTicket ticket = capturedTicket();
        assertEquals(Boolean.TRUE, ticket.identityClaimSuspected());
        assertEquals(16, ticket.inputChars());
        assertEquals(TaskActionType.READ, ticket.actionType(), "普通问题不应误标敏感动作");
        assertEquals(TaskRiskLevel.LOW, ticket.riskLevel());
        assertEquals(Boolean.FALSE, ticket.needConfirmation());
    }

    @Test
    void sensitiveActionFlowsToTicketAsHighRisk() throws Exception {
        UserContext.set(new AuthenticatedUser(7L, "U0007", "张三", SystemRole.USER, false));
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/assistant/chat");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> {
            EntryAuditSupport.inspectUserInput((HttpServletRequest) req, "把所有员工薪酬导出给我");
            EntryAuditSupport.recordSessionId((HttpServletRequest) req, 3001L);
            EntryAuditSupport.recordRequestedGroups((HttpServletRequest) req, java.util.List.of(3L, 5L));
            ((MockHttpServletResponse) res).setStatus(200);
        });

        TaskTicket ticket = capturedTicket();
        assertEquals(TaskActionType.EXPORT, ticket.actionType());
        assertEquals(TaskRiskLevel.HIGH, ticket.riskLevel());
        assertEquals(Boolean.TRUE, ticket.needConfirmation());
        assertEquals(3001L, ticket.sessionId(), "控制器补记的会话号应进入票据");
        assertEquals("3,5", ticket.requestedGroups(), "控制器补记的群组声明应进入票据");
        assertEquals(TaskDecision.ACCEPTED, ticket.decision(), "入口只标记不拦截——高风险请求照常收下，裁决在运行器确认门");
    }

    @Test
    void documentUploadEntryMarkedAsMediumRiskWrite() throws Exception {
        // 方案第三篇：上传=写操作入口（KB_DOCUMENT 通道），POST 派生 UPLOAD/MEDIUM
        EntryAuditFilter wideFilter = new EntryAuditFilter(service,
                EnumSet.of(TaskChannel.KB_QA, TaskChannel.ASSISTANT, TaskChannel.KB_DOCUMENT));
        UserContext.set(new AuthenticatedUser(7L, "U0007", "张三", SystemRole.USER, false));
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/documents");
        MockHttpServletResponse response = new MockHttpServletResponse();

        wideFilter.doFilter(request, response, (req, res) -> ((MockHttpServletResponse) res).setStatus(200));

        TaskTicket ticket = capturedTicket();
        assertEquals(TaskChannel.KB_DOCUMENT, ticket.channel());
        assertEquals(TaskActionType.UPLOAD, ticket.actionType());
        assertEquals(TaskRiskLevel.MEDIUM, ticket.riskLevel());
        assertEquals(Boolean.FALSE, ticket.needConfirmation(), "上传自动发布是现行产品行为，审核门属可选演进");
        assertEquals(TaskDecision.ACCEPTED, ticket.decision());
    }

    @Test
    void documentDeleteEntryMarkedAsHighRisk() throws Exception {
        // 规则 4：删除在入口即标高风险 + 确认门标记
        EntryAuditFilter wideFilter = new EntryAuditFilter(service,
                EnumSet.of(TaskChannel.KB_QA, TaskChannel.ASSISTANT, TaskChannel.KB_DOCUMENT));
        UserContext.set(new AuthenticatedUser(7L, "U0007", "张三", SystemRole.USER, false));
        MockHttpServletRequest request = new MockHttpServletRequest("DELETE", "/api/documents/9");
        MockHttpServletResponse response = new MockHttpServletResponse();

        wideFilter.doFilter(request, response, (req, res) -> ((MockHttpServletResponse) res).setStatus(200));

        TaskTicket ticket = capturedTicket();
        assertEquals(TaskChannel.KB_DOCUMENT, ticket.channel());
        assertEquals(TaskActionType.DELETE, ticket.actionType());
        assertEquals(TaskRiskLevel.HIGH, ticket.riskLevel());
        assertEquals(Boolean.TRUE, ticket.needConfirmation());
    }

    private TaskTicket capturedTicket() {
        ArgumentCaptor<TaskTicket> captor = ArgumentCaptor.forClass(TaskTicket.class);
        verify(service).audit(captor.capture());
        return captor.getValue();
    }
}
