package com.argus.rag.common.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ApiResponse} 统一 API 响应体契约（纯 JVM/零 mock/零容器）。
 * success(data) 产成功响应（成功+数据+「操作成功」，code=null）；3 参构造 code=null；
 * 4 参构造透传业务码；isSuccess/getData/getMessage 访问器；错误响应由调用方显式
 * 构造（success=false + message/code）。
 */
@DisplayName("统一 API 响应（ApiResponse）")
class ApiResponseTest {

    @Test
    void successFactoryBuildsSuccessWithOperationMessage() {
        ApiResponse<String> r = ApiResponse.success("hello");
        assertTrue(r.success());
        assertEquals("hello", r.getData());
        assertEquals("操作成功", r.getMessage());
        assertNull(r.code());
        assertTrue(r.isSuccess());
    }

    @Test
    void successWithComplexPayloadRetainsIt() {
        Map<String, Object> payload = Map.of("id", 1L, "tags", List.of("a", "b"));
        ApiResponse<Map<String, Object>> r = ApiResponse.success(payload);
        assertEquals(payload, r.getData());
        assertTrue(r.isSuccess());
    }

    @Test
    void threeArgConstructorDefaultsCodeToNull() {
        ApiResponse<Integer> r = new ApiResponse<>(false, null, "出错了");
        assertEquals(false, r.success());
        assertNull(r.getData());
        assertEquals("出错了", r.getMessage());
        assertNull(r.code());
    }

    @Test
    void fourArgConstructorCarriesStableBusinessCode() {
        ApiResponse<String> r = new ApiResponse<>(false, null, "模型不可用", "MODEL_UNAVAILABLE");
        assertTrue(!r.success(), "错误响应 success=false");
        assertNull(r.getData());
        assertEquals("MODEL_UNAVAILABLE", r.code());
        assertEquals("模型不可用", r.getMessage());
    }

    @Test
    void successWithNullDataIsAllowed() {
        ApiResponse<Object> r = ApiResponse.success(null);
        assertTrue(r.isSuccess());
        assertNull(r.getData());
        assertEquals("操作成功", r.getMessage());
    }
}