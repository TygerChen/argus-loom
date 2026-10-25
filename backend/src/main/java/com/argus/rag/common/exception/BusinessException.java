package com.argus.rag.common.exception;

/**
 * 业务异常，抛出时由 {@link GlobalExceptionHandler} 统一处理，返回 HTTP 400。
 *
 * <p>可选携带稳定业务错误码 {@code code}，供上层做结构化识别（例如拒绝作答原因），
 * 而不是通过 {@code message} 的文本前缀匹配；同时 {@code message} 保留面向用户的可读文案。</p>
 */
public class BusinessException extends RuntimeException {

    private final String code;

    public BusinessException(String message) {
        this(null, message, null);
    }

    public BusinessException(String message, Throwable cause) {
        this(null, message, cause);
    }

    /** 携带业务错误码，便于上层稳定识别错误语义。 */
    public BusinessException(String code, String message) {
        this(code, message, null);
    }

    /** 携带业务错误码与根因。 */
    public BusinessException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    /** 稳定业务错误码，未设置时为 {@code null}。 */
    public String getCode() {
        return code;
    }
}