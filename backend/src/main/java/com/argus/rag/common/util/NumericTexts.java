package com.argus.rag.common.util;

/**
 * 数字文本解析纯工具（无实例态、无 IO）。
 * <p>
 * 收拢 {@code ColumnFilterStrategy.numericOrNull} 与 {@code TableSchemaService.isNumeric}
 * 重复的「去千分位 + {@code Double.parseDouble}」判定核心：去逗号后能否解析为 double，
 * 是则返回其值、否则（null/空白/非数字）返回 null，永不抛。
 * </p>
 */
public final class NumericTexts {

    private NumericTexts() {
    }

    /** 去千分位后按 double 解析；null/空白/非数字→null（不抛）。 */
    public static Double parseOrNull(String s) {
        if (s == null) {
            return null;
        }
        try {
            return Double.parseDouble(s.replace(",", ""));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}