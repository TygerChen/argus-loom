package com.argus.rag.common.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link DateFormats} 固定日期/时间格式常量契约（纯 JVM，无状态）。
 * 锁定各 formatter 产生的精确字符串，防全库散落漂移。
 */
@DisplayName("日期格式常量（DateFormats）")
class DateFormatsTest {

    private static final LocalDate D = LocalDate.of(2026, 9, 18);
    private static final LocalDateTime DT = LocalDateTime.of(2026, 9, 18, 14, 30, 0);

    @Test
    void dateFormatsToIsoDate() {
        assertEquals("2026-09-18", DateFormats.DATE.format(D));
    }

    @Test
    void monthFormatsToYearMonth() {
        assertEquals("2026-09", DateFormats.MONTH.format(D));
    }

    @Test
    void dateTimeFormatsWithSeconds() {
        assertEquals("2026-09-18 14:30:00", DateFormats.DATE_TIME.format(DT));
    }

    @Test
    void dateTimeWithZoneRendersZoneSuffix() {
        ZonedDateTime zdt = ZonedDateTime.of(DT, ZoneId.of("UTC"));
        assertEquals("2026-09-18 14:30:00 UTC", DateFormats.DATE_TIME_Z.format(zdt));
    }
}