package com.argus.rag.excel.audit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link OperationLogService#parseValue(String)} 行为锁
 * （纯 JVM、static 同包直调）。
 * <p>锁契约：null/空白/「null」（trim 后）→ null；合法 JSON 按类型解析（数字/布尔/对象/数组）；
 * 非法 JSON（如裸文本）→ 原样返回原字符串，不抛异常——用于 undo/redo 时把存储的历史值安全还原为单元格值。</p>
 */
@DisplayName("操作日志值解析还原（OperationLogService.parseValue）")
class OperationLogParseValueTest {

    @Test
    void nullAndBlankAndLiteralNullYieldNull() {
        assertNull(OperationLogService.parseValue(null));
        assertNull(OperationLogService.parseValue(""));
        assertNull(OperationLogService.parseValue("   "));
        assertNull(OperationLogService.parseValue("null"));
        assertNull(OperationLogService.parseValue("  null  "));
    }

    @Test
    void numericJsonReturnsNumber() {
        assertEquals(Integer.valueOf(5), OperationLogService.parseValue("5"));
        // Jackson 对浮点数默认解析为 Double（非 fastjson 的 BigDecimal）
        Number num = (Number) OperationLogService.parseValue("5.5");
        assertEquals(5.5, num.doubleValue(), 0.001);
    }

    @Test
    void booleanAndStringJsonParse() {
        assertEquals(Boolean.TRUE, OperationLogService.parseValue("true"));
    }

    @Test
    void jsonStringValueParsesToStringBody() {
        Object parsed = OperationLogService.parseValue("\"hello\"");
        assertEquals("hello", parsed);
    }

    @Test
    void jsonObjectParsed() {
        Object parsed = OperationLogService.parseValue("{\"a\":1,\"b\":\"x\"}");
        @SuppressWarnings("unchecked")
        Map<String, Object> obj = assertInstanceOf(Map.class, parsed);
        assertEquals(1, ((Number) obj.get("a")).intValue());
        assertEquals("x", obj.get("b"));
    }

    @Test
    void jsonArrayParsed() {
        Object parsed = OperationLogService.parseValue("[1,\"x\"]");
        List<?> arr = assertInstanceOf(List.class, parsed);
        assertEquals(2, arr.size());
    }

    @Test
    void invalidJsonReturnsOriginalText() {
        String raw = "not a json";
        assertEquals(raw, OperationLogService.parseValue(raw));
    }
}
