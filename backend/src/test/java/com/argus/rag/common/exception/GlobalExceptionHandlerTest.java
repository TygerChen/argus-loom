package com.argus.rag.common.exception;

import com.argus.rag.common.api.ApiResponse;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.verify;

/**
 * {@link GlobalExceptionHandler} 业务错误码透传契约测试。
 *
 * <p>锁住：业务异常携带的稳定码（如"模型不可用" {@code MODEL_UNAVAILABLE}）必须从
 * {@link BusinessException} 经全局异常处理器进入统一响应 {@code ApiResponse.code}，
 * 供前端结构化识别；未设置码的业务异常 code 保持 null，不引入新字段。</p>
 */
@DisplayName("全局异常处理业务码透传（GlobalExceptionHandler）")
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    /** 创建捕获 setStatus 的轻量 mock。 */
    private static HttpServletResponse mockResponse() {
        HttpServletResponse resp = Mockito.mock(HttpServletResponse.class);
        Mockito.when(resp.getStatus()).thenReturn(200);
        // 拦截 setStatus 以捕获状态码
        org.mockito.invocation.InvocationOnMock[] ref = new org.mockito.invocation.InvocationOnMock[1];
        Mockito.doAnswer(inv -> {
            int sc = inv.getArgument(0);
            Mockito.when(resp.getStatus()).thenReturn(sc);
            return null;
        }).when(resp).setStatus(anyInt());
        return resp;
    }

    // ── 码透传测试 ──

    @Test
    void carriesStableCodeForModelUnavailable() {
        HttpServletResponse resp = mockResponse();
        ApiResponse<Void> r = handler.handleBusinessException(
                new BusinessException(ErrorCodes.MODEL_UNAVAILABLE, ErrorCodes.MODEL_UNAVAILABLE_MESSAGE), resp);
        assertFalse(r.isSuccess());
        assertEquals(ErrorCodes.MODEL_UNAVAILABLE, r.code());
        assertEquals("模型服务暂时不可用，请稍后重试。", r.getMessage());
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE.value(), resp.getStatus());
    }

    @Test
    void leavesCodeNullWhenBusinessExceptionHasNone() {
        HttpServletResponse resp = mockResponse();
        ApiResponse<Void> r = handler.handleBusinessException(new BusinessException("通用业务失败"), resp);
        assertNull(r.code());
        assertEquals(HttpStatus.BAD_REQUEST.value(), resp.getStatus());
    }

    @Test
    void carriesFinanceStableCodesThroughToResponse() {
        HttpServletResponse resp = mockResponse();
        ApiResponse<Void> payroll = handler.handleBusinessException(
                new BusinessException(ErrorCodes.FINANCE_PAYROLL_NOT_AVAILABLE, "该月薪酬尚未发布或不存在"), resp);
        assertEquals(ErrorCodes.FINANCE_PAYROLL_NOT_AVAILABLE, payroll.code());
        assertEquals(HttpStatus.BAD_REQUEST.value(), resp.getStatus());

        resp = mockResponse();
        ApiResponse<Void> pdf = handler.handleBusinessException(
                new BusinessException(ErrorCodes.FINANCE_PDF_FILE_FAILED, "PDF 生成失败: boom"), resp);
        assertEquals(ErrorCodes.FINANCE_PDF_FILE_FAILED, pdf.code());
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR.value(), resp.getStatus());
    }

    @Test
    void carriesAuthAndUserStableCodesThroughToResponse() {
        HttpServletResponse resp = mockResponse();
        ApiResponse<Void> token = handler.handleBusinessException(
                new BusinessException(ErrorCodes.AUTH_TOKEN_INVALID, "access token 非法或已过期"), resp);
        assertEquals(ErrorCodes.AUTH_TOKEN_INVALID, token.code());
        assertEquals(HttpStatus.UNAUTHORIZED.value(), resp.getStatus());

        resp = mockResponse();
        ApiResponse<Void> cred = handler.handleBusinessException(
                new BusinessException(ErrorCodes.AUTH_CREDENTIALS_INVALID, "账号或密码错误"), resp);
        assertEquals(ErrorCodes.AUTH_CREDENTIALS_INVALID, cred.code());
        assertEquals(HttpStatus.UNAUTHORIZED.value(), resp.getStatus());

        resp = mockResponse();
        ApiResponse<Void> user = handler.handleBusinessException(
                new BusinessException(ErrorCodes.USER_NOT_FOUND, "用户不存在"), resp);
        assertEquals(ErrorCodes.USER_NOT_FOUND, user.code());
        assertEquals(HttpStatus.NOT_FOUND.value(), resp.getStatus());

        resp = mockResponse();
        ApiResponse<Void> pwd = handler.handleBusinessException(
                new BusinessException(ErrorCodes.USER_PASSWORD_MISMATCH, "当前密码不正确"), resp);
        assertEquals(ErrorCodes.USER_PASSWORD_MISMATCH, pwd.code());
        assertEquals(HttpStatus.BAD_REQUEST.value(), resp.getStatus());
    }

    @Test
    void carriesGroupStableCodesThroughToResponse() {
        HttpServletResponse resp = mockResponse();
        ApiResponse<Void> notFound = handler.handleBusinessException(
                new BusinessException(ErrorCodes.GROUP_NOT_FOUND, "邀请不存在"), resp);
        assertEquals(ErrorCodes.GROUP_NOT_FOUND, notFound.code());
        assertEquals(HttpStatus.NOT_FOUND.value(), resp.getStatus());

        resp = mockResponse();
        ApiResponse<Void> handled = handler.handleBusinessException(
                new BusinessException(ErrorCodes.GROUP_ALREADY_HANDLED, "申请已处理"), resp);
        assertEquals(ErrorCodes.GROUP_ALREADY_HANDLED, handled.code());
        assertEquals(HttpStatus.CONFLICT.value(), resp.getStatus());

        resp = mockResponse();
        ApiResponse<Void> pending = handler.handleBusinessException(
                new BusinessException(ErrorCodes.GROUP_REQUEST_PENDING, "已存在待处理邀请"), resp);
        assertEquals(ErrorCodes.GROUP_REQUEST_PENDING, pending.code());
        assertEquals(HttpStatus.CONFLICT.value(), resp.getStatus());

        resp = mockResponse();
        ApiResponse<Void> forbidden = handler.handleBusinessException(
                new BusinessException(ErrorCodes.GROUP_ACTION_FORBIDDEN, "无权处理该邀请"), resp);
        assertEquals(ErrorCodes.GROUP_ACTION_FORBIDDEN, forbidden.code());
        assertEquals(HttpStatus.FORBIDDEN.value(), resp.getStatus());
    }

    @Test
    void carriesExcelStableCodesThroughToResponse() {
        HttpServletResponse resp = mockResponse();
        ApiResponse<Void> finance = handler.handleBusinessException(
                new BusinessException(ErrorCodes.EXCEL_FINANCE_FORBIDDEN, "无权导出财务文件"), resp);
        assertEquals(ErrorCodes.EXCEL_FINANCE_FORBIDDEN, finance.code());
        assertEquals(HttpStatus.FORBIDDEN.value(), resp.getStatus());

        resp = mockResponse();
        ApiResponse<Void> share = handler.handleBusinessException(
                new BusinessException(ErrorCodes.EXCEL_SHARE_INVALID, "分享链接无效或已过期"), resp);
        assertEquals(ErrorCodes.EXCEL_SHARE_INVALID, share.code());
        assertEquals(HttpStatus.BAD_REQUEST.value(), resp.getStatus());

        resp = mockResponse();
        ApiResponse<Void> perm = handler.handleBusinessException(
                new BusinessException(ErrorCodes.PERMISSION_DENIED, "无权访问该导出任务"), resp);
        assertEquals(ErrorCodes.PERMISSION_DENIED, perm.code());
        assertEquals(HttpStatus.FORBIDDEN.value(), resp.getStatus());
    }

    @Test
    void carriesDocumentStableCodesThroughToResponse() {
        HttpServletResponse resp = mockResponse();
        ApiResponse<Void> notFound = handler.handleBusinessException(
                new BusinessException(ErrorCodes.DOCUMENT_NOT_FOUND, "文档不存在或已删除"), resp);
        assertEquals(ErrorCodes.DOCUMENT_NOT_FOUND, notFound.code());
        assertEquals(HttpStatus.NOT_FOUND.value(), resp.getStatus());

        resp = mockResponse();
        ApiResponse<Void> ready = handler.handleBusinessException(
                new BusinessException(ErrorCodes.DOCUMENT_NOT_READY, "文档尚未就绪，暂不可预览"), resp);
        assertEquals(ErrorCodes.DOCUMENT_NOT_READY, ready.code());
        assertEquals(HttpStatus.CONFLICT.value(), resp.getStatus());

        resp = mockResponse();
        ApiResponse<Void> sess = handler.handleBusinessException(
                new BusinessException(ErrorCodes.UPLOAD_SESSION_INVALID, "上传会话已过期"), resp);
        assertEquals(ErrorCodes.UPLOAD_SESSION_INVALID, sess.code());
        assertEquals(HttpStatus.BAD_REQUEST.value(), resp.getStatus());

        resp = mockResponse();
        ApiResponse<Void> incomplete = handler.handleBusinessException(
                new BusinessException(ErrorCodes.UPLOAD_INCOMPLETE, "缺少分片，无法完成上传"), resp);
        assertEquals(ErrorCodes.UPLOAD_INCOMPLETE, incomplete.code());
        assertEquals(HttpStatus.BAD_REQUEST.value(), resp.getStatus());
    }

    // ── 其他辅助方法测试 ──

    @Test
    void userFacingMessageTakesOnlyFirstLine() {
        String message = GlobalExceptionHandler.userFacingMessage(
                new RuntimeException("模型服务暂时不可用\n\tat com.argus...hiddenstack"), "fallback");
        assertEquals("模型服务暂时不可用", message);
    }

    @Test
    void userFacingMessageFallsBackOnBlank() {
        assertEquals("fallback", GlobalExceptionHandler.userFacingMessage(new RuntimeException("   "), "fallback"));
        assertEquals("fallback", GlobalExceptionHandler.userFacingMessage(new RuntimeException(), "fallback"));
    }

    @Test
    void mapsIllegalArgumentToBadRequestWithGenericMessage() throws Exception {
        HttpServletResponse resp = mockResponse();
        ApiResponse<Void> r = handler.handleIllegalArgumentException(
                new IllegalArgumentException("原始文件路径 /tmp/x/secret 解析失败"));
        assertFalse(r.isSuccess());
        assertEquals("请求参数不合法", r.getMessage());
        assertNull(r.code());
        assertFalse(r.getMessage().contains("secret"));

        ResponseStatus status = GlobalExceptionHandler.class
                .getMethod("handleIllegalArgumentException", IllegalArgumentException.class)
                .getAnnotation(ResponseStatus.class);
        assertNotNull(status);
        assertEquals(HttpStatus.BAD_REQUEST, status.value());
    }

    @Test
    void businessExceptionNoLongerHasResponseStatusAnnotation() throws Exception {
        java.lang.reflect.Method method = GlobalExceptionHandler.class
                .getMethod("handleBusinessException", BusinessException.class,
                        jakarta.servlet.http.HttpServletResponse.class);
        assertNull(method.getAnnotation(ResponseStatus.class));
    }
}
