package com.argus.rag.common.api;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 统一 API 响应体。
 * 所有控制器返回值都通过此类包装，确保前端接收到一致的响应格式。
 *
 * @param <T>     数据类型
 * @param success 请求是否成功
 * @param data    响应数据
 * @param message 错误时的提示信息，成功时为 null
 * @param code    稳定业务错误码（nullable，用于结构化识别错误语义，如 MODEL_UNAVAILABLE）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)//整理json格式，防止空值不显示字段的问题
public record ApiResponse<T>(boolean success, T data, String message, String code) {

    /** 兼容互联网同响应：未指定业务码时 code 为 null（不序列化）。 */
    public ApiResponse(boolean success, T data, String message) {
        this(success, data, message, null);
    }

    /** 快速构造成功响应，message 为 null */
    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(true, data, "操作成功");
    }

    public boolean isSuccess() {
        return success;
    }

    public T getData() {
        return data;
    }

    public String getMessage() {
        return message;
    }
}
