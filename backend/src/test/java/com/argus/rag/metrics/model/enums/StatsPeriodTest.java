package com.argus.rag.metrics.model.enums;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link StatsPeriod} 统计时间段枚举契约（纯 JVM/零 mock/零 IO）。
 * getDays 暴露各档天数（今日 0、近7/14/30 天）；getStartTime 返回当日 00:00，
 * 档位按 days 往前平移且均在当天午夜整点。
 */
@DisplayName("统计时间段（StatsPeriod）")
class StatsPeriodTest {

    @Test
    void daysReflectEachPeriodBucket() {
        assertEquals(0, StatsPeriod.TODAY.getDays());
        assertEquals(7, StatsPeriod.LAST_7_DAYS.getDays());
        assertEquals(14, StatsPeriod.LAST_14_DAYS.getDays());
        assertEquals(30, StatsPeriod.LAST_30_DAYS.getDays());
    }

    @Test
    void todayStartIsTodayMidnight() {
        LocalDateTime start = StatsPeriod.TODAY.getStartTime();
        assertEquals(LocalDate.now(), start.toLocalDate());
        assertEquals(0, start.getHour());
        assertEquals(0, start.getMinute());
        assertEquals(0, start.getSecond());
        assertEquals(0, start.getNano());
    }

    @Test
    void nonTodayStartIsNowMinusDaysMidnight() {
        assertStartEqualsDaysAgo(StatsPeriod.LAST_7_DAYS, 7);
        assertStartEqualsDaysAgo(StatsPeriod.LAST_14_DAYS, 14);
        assertStartEqualsDaysAgo(StatsPeriod.LAST_30_DAYS, 30);
    }

    @Test
    void allFourPeriodsEnumSetStable() {
        assertEquals(4, StatsPeriod.values().length);
        StatsPeriod[] all = StatsPeriod.values();
        assertEquals(StatsPeriod.TODAY, all[0]);
        assertEquals(StatsPeriod.LAST_7_DAYS, all[1]);
        assertEquals(StatsPeriod.LAST_14_DAYS, all[2]);
        assertEquals(StatsPeriod.LAST_30_DAYS, all[3]);
    }

    private static void assertStartEqualsDaysAgo(StatsPeriod period, int days) {
        LocalDateTime start = period.getStartTime();
        assertEquals(LocalDate.now().minusDays(days), start.toLocalDate());
        assertEquals(0, start.getHour());
        assertEquals(0, start.getMinute());
        assertEquals(0, start.getSecond());
        assertEquals(0, start.getNano());
    }
}