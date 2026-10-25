package com.argus.rag.assistant.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link CurrentTimeTool} 当前时间工具契约（纯 JVM/零 mock/零 IO，锁格式不锁时刻）。
 * getCurrentTime 以 DateFormats.DATE_TIME_Z（"yyyy-MM-dd HH:mm:ss z" 形如 2026-09-19 10:30:00 GMT+8）输出：
 * 非 null/非空白、日期与北京时间段与系统当日吻合（允许时区 token 名各异）、整体与当下一分钟内一致。
 */
@DisplayName("当前时间工具（CurrentTimeTool）")
class CurrentTimeToolTest {

    private static final String PREFIX = "yyyy-MM-dd HH:mm:ss";

    private final CurrentTimeTool tool = new CurrentTimeTool();

    @Test
    void returnsNonEmptyFormattedTime() {
        String s = tool.getCurrentTime();
        assertNotNull(s);
        assertFalse(s.isBlank());
    }

    @Test
    void outputMatchesDateDateTimeZoneLayout() {
        String s = tool.getCurrentTime();
        // 前缀 = yyyy-MM-dd HH:mm:ss，后随空格 + 时区名/偏移 token
        assertTrue(s.matches("^\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2} .+$"),
                "实际输出: [" + s + "]");
    }

    @Test
    void datePortionReflectsToday() {
        String s = tool.getCurrentTime();
        String datePart = s.substring(0, 10);
        assertEquals(LocalDate.now().toString(), datePart);
    }

    @Test
    void timeIsWithinOneMinuteOfNow() {
        String s = tool.getCurrentTime();
        LocalDateTime emitted =
                LocalDateTime.parse(s.substring(0, PREFIX.length()), DateTimeFormatter.ofPattern(PREFIX));
        long secs = java.time.Duration.between(emitted, LocalDateTime.now()).getSeconds();
        assertTrue(Math.abs(secs) <= 60,
                "工具输出应接近系统时钟: 差了 " + secs + " 秒");
    }
}