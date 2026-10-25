package com.argus.rag.common.exception;

/**
 * 资源不存在异常，抛出时由 {@link GlobalExceptionHandler} 统一处理，返回 HTTP 404。
 *
 * <p>用于语义上明确「目标资源查无」(文档/会话/批记录等)，与参数错误的
 * {@link BusinessException}(400) 区分开，避免把「资源不存在」误报成客户端参数错误。</p>
 */
public class ResourceNotFoundException extends RuntimeException {

    public ResourceNotFoundException(String message) {
        super(message);
    }

    public ResourceNotFoundException(String message, Throwable cause) {
        super(message, cause);
    }
}