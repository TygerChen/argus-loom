package com.argus.rag.common.exception;

import com.argus.rag.common.api.ApiResponse;
import com.argus.rag.common.ratelimit.RateLimitExceededException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 全局异常拦截，将各类异常映射为对应的 HTTP 状态码和 {@link ApiResponse}。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public ApiResponse<Void> handleBusinessException(BusinessException exception,
                                                     jakarta.servlet.http.HttpServletResponse response) {
        HttpStatus status = ErrorCodes.httpStatus(exception.getCode());
        response.setStatus(status.value());
        return new ApiResponse<>(false, null, exception.getMessage(), exception.getCode());
    }

    @ExceptionHandler(ForbiddenException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public ApiResponse<Void> handleForbiddenException(ForbiddenException exception) {
        return new ApiResponse<>(false, null, exception.getMessage());
    }

    @ExceptionHandler(UnauthorizedException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    public ApiResponse<Void> handleUnauthorizedException(UnauthorizedException exception) {
        return new ApiResponse<>(false, null, exception.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> handleMethodArgumentNotValidException(MethodArgumentNotValidException exception) {
        String message = exception.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(FieldError::getDefaultMessage)
                .orElse("请求参数校验失败");
        return new ApiResponse<>(false, null, message);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> handleHttpMessageNotReadableException(HttpMessageNotReadableException exception) {
        return new ApiResponse<>(false, null, "请求体格式非法");
    }

    /**
     * 参数类运行时异常（历史遗留的裸 {@code IllegalArgumentException}）。
     * <p>P1-03：这类异常语义上是「入参不合法」，不应落到 {@code Exception} 兜底变成 500；
     * 这里统一映射 400 + 通用文案，完整堆栈只进日志、不回前端（避免泄露内部细节）。
     * 新增代码仍应优先抛带错误码的 {@link BusinessException}。</p>
     */
    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> handleIllegalArgumentException(IllegalArgumentException exception) {
        log.warn("请求参数不合法: {}", exception.getMessage(), exception);
        return new ApiResponse<>(false, null, "请求参数不合法");
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    @ResponseStatus(HttpStatus.METHOD_NOT_ALLOWED)
    public ApiResponse<Void> handleHttpRequestMethodNotSupportedException(
            HttpRequestMethodNotSupportedException exception
    ) {
        return new ApiResponse<>(false, null, "请求方法不支持");
    }

    @ExceptionHandler(NoResourceFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ApiResponse<Void> handleNoResourceFoundException(NoResourceFoundException exception) {
        return new ApiResponse<>(false, null, "请求资源不存在");
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ApiResponse<Void> handleResourceNotFoundException(ResourceNotFoundException exception) {
        return new ApiResponse<>(false, null, exception.getMessage());
    }

    /** 数据库约束冲突（唯一键/外键/非空等），语义为「数据状态冲突」，返回 409 而非笼统 400。 */
    @ExceptionHandler(DataIntegrityViolationException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ApiResponse<Void> handleDataIntegrityViolation(DataIntegrityViolationException exception) {
        log.warn("数据完整性冲突", exception);
        return new ApiResponse<>(false, null, "数据已存在或状态冲突，请刷新后重试");
    }

    /** 单条查询无结果，语义为「资源不存在」，返回 404，避免各服务手写 catch EmptyResult...。 */
    @ExceptionHandler(EmptyResultDataAccessException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ApiResponse<Void> handleEmptyResult(EmptyResultDataAccessException exception) {
        return new ApiResponse<>(false, null, "请求资源不存在");
    }

    @ExceptionHandler(RateLimitExceededException.class)
    @ResponseStatus(HttpStatus.TOO_MANY_REQUESTS)
    public ApiResponse<Void> handleRateLimitExceededException(RateLimitExceededException exception) {
        log.warn("限流触发: limit={}, window={}s, scope={}", exception.getLimit(), exception.getWindow(), exception.getScope());
        return new ApiResponse<>(false, null, exception.getMessage());
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    @ResponseStatus(HttpStatus.PAYLOAD_TOO_LARGE)
    public ApiResponse<Void> handleMaxUploadSizeExceededException(
            MaxUploadSizeExceededException exception
    ) {
        return new ApiResponse<>(false, null, "上传文件超过大小限制");
    }

    /** 兜底处理，避免堆栈信息泄露到前端 */
    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ApiResponse<Void> handleException(Exception exception) {
        log.error("Unhandled exception", exception);
        return new ApiResponse<>(false, null, "服务器内部错误");
    }

    /**
     * 供 SSE 流式端点使用的「用户可见 message」解析。
     * <p>SSE 端点无法复用 {@code @RestControllerAdvice} 的拦截链路，必须自行决定
     * 哪个异常 message 可以透传给前端。这里维持「透传可读 message」的语义
     * （模型/服务异常会携带如“模型不可用”这类用户可读信息），但做两道安全收口：</p>
     * <ul>
     *   <li>只取第一行，后续脏堆栈/多行日志绝缘在外，不泄内部细节；</li>
     *   <li>message 为空白时回退到 {@code fallback} 降级文案。</li>
     * </ul>
     */
    public static String userFacingMessage(Throwable exception, String fallback) {
        String raw = exception.getMessage();
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        int newline = raw.indexOf('\n');
        if (newline >= 0) {
            raw = raw.substring(0, newline);
        }
        return raw;
    }
}
