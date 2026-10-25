package com.argus.rag.excel.pipeline.filter;

import com.argus.rag.common.util.NumericTexts;

/**
 * 列过滤策略（Strategy）——将 {@code queryStructuredData} 中按运算符分发的过滤逻辑
 * 收敛为一个可独立测试、可扩展的枚举策略。
 *
 * <p>运算符子集：{@code eq / contains / gt / lt}，与 Excel 结构化查询约定一致；
 * 未识别的运算符落到 {@link #NO_OP}（永假），语义与旧实现 {@code default → false} 一致。</p>
 */
public enum ColumnFilterStrategy {

    /** 等于（忽略大小写）。 */
    EQ {
        @Override
        public boolean matches(String cellValue, String target) {
            return anyNonNull(cellValue, target) && cellValue.equalsIgnoreCase(target);
        }
    },

    /** 包含（忽略大小写）。 */
    CONTAINS {
        @Override
        public boolean matches(String cellValue, String target) {
            return anyNonNull(cellValue, target)
                    && cellValue.toLowerCase().contains(target.toLowerCase());
        }
    },

    /** 大于（数值比较，逗号千分位可容忍）。 */
    GT {
        @Override
        public boolean matches(String cellValue, String target) {
            return compareNumeric(cellValue, target) > 0;
        }
    },

    /** 小于（数值比较，逗号千分位可容忍）。 */
    LT {
        @Override
        public boolean matches(String cellValue, String target) {
            return compareNumeric(cellValue, target) < 0;
        }
    },

    /** 兜底策略：运算符不识别时永假（不缩小原默认 false 语义）。 */
    NO_OP {
        @Override
        public boolean matches(String cellValue, String target) {
            return false;
        }
    };

    /** 按运算符名解析策略；{@code null} 视为默认 {@code eq}，不识别的回落 {@link #NO_OP}。 */
    public static ColumnFilterStrategy from(String operator) {
        if (operator == null) return EQ;
        for (ColumnFilterStrategy s : values()) {
            if (s.name().equalsIgnoreCase(operator)) return s;
        }
        return NO_OP;
    }

    /**
     * 判断单元格值是否命中该过滤条件。
     *
     * @param cellValue 单元格文本（已通过 {@code extractValueAsString} 归一化）
     * @param target     过滤目标值
     */
    public abstract boolean matches(String cellValue, String target);

    private static boolean anyNonNull(String a, String b) {
        return a != null && b != null;
    }

    private static Double numericOrNull(String s) {
        return NumericTexts.parseOrNull(s);
    }

    private static int compareNumeric(String cell, String target) {
        Double c = numericOrNull(cell);
        Double t = numericOrNull(target);
        if (c == null || t == null) return 0; // 解析失败按「不匹配」处理（原 GT/LT 在异常时 return false）
        return c.compareTo(t);
    }
}