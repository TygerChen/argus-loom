package com.argus.rag.common.util;

import java.time.format.DateTimeFormatter;

/**
 * 日期 / 时间格式化常量收口。
 *
 * <p>统一全工程散落的 {@link java.time.format.DateTimeFormatter} 字面量，
 * 避免相同模式写进多个文件导致漂移。形如 `yyyy-MM-dd` 的固定格式统一在此引入，
 * 需动态解析（如从单元格或前端传来的不定 pattern）的调用请保持 `ofPattern(pattern)` 原位。</p>
 */
public final class DateFormats {

    /** 日期：2026-09-18 */
    public static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    /** 月份：2026-09 */
    public static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("yyyy-MM");

    /** 日期 + 时间 + 时区：2026-09-18 14:30:00 +08:00 */
    public static final DateTimeFormatter DATE_TIME_Z = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z");

    /** 日期 + 时间（无时区）：2026-09-18 14:30:00 */
    public static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private DateFormats() {
    }
}