package com.argus.rag.excel.service;

import com.argus.rag.auth.CurrentUserService;
import com.argus.rag.excel.entity.ExcelAuditLog;
import com.argus.rag.excel.mapper.ExcelAuditLogMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ExcelAuditService} 审计写入契约（mock mapper / 请求上下文）：
 * - 正常落库：user id/username、action、target、detail 截断 2000、IP 取 X-Forwarded-For 首段；
 * - 无转发头 → 退回 remoteAddr；头为空串 → 同退回；
 * - 审计失败（取用户或落库抛）必须吞掉、不阻断业务主流程。
 */
@DisplayName("Excel 审计日志（ExcelAuditService）")
class ExcelAuditServiceTest {

    private ExcelAuditLogMapper mapper;
    private CurrentUserService currentUserService;
    private HttpServletRequest request;
    private ExcelAuditService service;

    private final CurrentUserService.CurrentUser user =
            new CurrentUserService.CurrentUser(7L, "U7", "会计");

    @BeforeEach
    void setUp() {
        mapper = mock(ExcelAuditLogMapper.class);
        currentUserService = mock(CurrentUserService.class);
        request = mock(HttpServletRequest.class);
        when(currentUserService.getRequiredCurrentUser()).thenReturn(user);
        service = new ExcelAuditService(mapper, currentUserService, request);
    }

    @Test
    void recordPersistsClientFields() {
        when(request.getHeader("X-Forwarded-For")).thenReturn("1.2.3.4, 5.6.7.8");
        service.record("EXPORT", "Sheet", "s-9", "导出了 S1");

        ArgumentCaptor<ExcelAuditLog> cap = ArgumentCaptor.forClass(ExcelAuditLog.class);
        verify(mapper).insert(cap.capture());
        ExcelAuditLog log = cap.getValue();
        assertEquals(7L, log.getUserId());
        assertEquals("U7", log.getUsername());
        assertEquals("EXPORT", log.getAction());
        assertEquals("Sheet", log.getTargetType());
        assertEquals("s-9", log.getTargetId());
        assertEquals("导出了 S1", log.getDetail());
        // 转发头取首段
        assertEquals("1.2.3.4", log.getIp());
    }

    @Test
    void recordTruncatesLongDetailTo2000() {
        when(request.getHeader("X-Forwarded-For")).thenReturn(null);
        when(request.getRemoteAddr()).thenReturn("9.9.9.9");
        StringBuilder longDetail = new StringBuilder();
        for (int i = 0; i < 3000; i++) longDetail.append('x');

        service.record("EDIT", "Sheet", "s-1", longDetail.toString());

        ArgumentCaptor<ExcelAuditLog> cap = ArgumentCaptor.forClass(ExcelAuditLog.class);
        verify(mapper).insert(cap.capture());
        assertEquals(2000, cap.getValue().getDetail().length());
        assertEquals("9.9.9.9", cap.getValue().getIp());
    }

    @Test
    void recordFallsBackToRemoteAddrWithoutForwardHeader() {
        when(request.getHeader("X-Forwarded-For")).thenReturn(null);
        when(request.getRemoteAddr()).thenReturn("0:0:0:0:0:0:0:1");
        service.record("VIEW", "Doc", "d-5", "查看文档");
        ArgumentCaptor<ExcelAuditLog> cap = ArgumentCaptor.forClass(ExcelAuditLog.class);
        verify(mapper).insert(cap.capture());
        assertEquals("0:0:0:0:0:0:0:1", cap.getValue().getIp());
    }

    @Test
    void recordSwallowsUserLookupFailure() {
        when(currentUserService.getRequiredCurrentUser())
                .thenThrow(new RuntimeException("no login"));
        assertDoesNotThrow(() -> service.record("X", "Y", "1", "z"));
        verify(mapper, never()).insert(any(ExcelAuditLog.class));
    }

    @Test
    void recordSwallowsInsertFailure() {
        when(request.getHeader("X-Forwarded-For")).thenReturn(null);
        org.mockito.Mockito.doThrow(new RuntimeException("db down"))
                .when(mapper).insert(any(ExcelAuditLog.class));
        assertDoesNotThrow(() -> service.record("SAVE", "Sheet", "1", "文本"));
    }
}