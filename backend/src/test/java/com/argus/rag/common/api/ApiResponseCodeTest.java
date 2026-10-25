package com.argus.rag.common.api;

import com.argus.rag.common.exception.ErrorCodes;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ApiResponse} 业务错误码契约测试。
 *
 * <p>锁住两点：统一响应可携带稳定业务码 {@code code}，且 {@code @JsonInclude(NON_NULL)}
 * 保证无码（成功响应/普通业务异常）时该字段不泄露到 JSON、与改动前序列化保持一致。</p>
 */
@DisplayName("统一响应业务错误码（ApiResponse.code）")
class ApiResponseCodeTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void threeArgConstructorLeavesCodeNull() {
        ApiResponse<Void> resp = new ApiResponse<>(false, null, "业务失败");
        assertEquals("业务失败", resp.getMessage());
    }

    @Test
    void fourArgRetainsStableCode() {
        ApiResponse<Void> resp = new ApiResponse<>(
                false, null, "模型服务暂时不可用，请稍后重试。", ErrorCodes.MODEL_UNAVAILABLE);
        assertEquals(ErrorCodes.MODEL_UNAVAILABLE, resp.code());
    }

    @Test
    void nullCodeIsOmittedFromJson() throws Exception {
        String json = MAPPER.writeValueAsString(new ApiResponse<>(false, null, "业务失败"));
        // 未设置业务码时 code 字段不得出现，与改动前 JSON 结构一致（向后兼容）。
        assertFalse(json.contains("\"code\""));
    }

    @Test
    void presentCodeIsSerialized() throws Exception {
        String json = MAPPER.writeValueAsString(
                new ApiResponse<>(false, null, "模型服务暂时不可用，请稍后重试。", ErrorCodes.MODEL_UNAVAILABLE));
        assertTrue(json.contains("\"code\""), json);
        assertTrue(json.contains(ErrorCodes.MODEL_UNAVAILABLE), json);
        assertTrue(json.contains("模型服务暂时不可用，请稍后重试。"), json);
    }
}