package com.argus.rag.report.task;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 任务进度扫描器契约：状态归一化（口语变体）、逾期/今日到期判定、按人汇总、空行跳过。
 */
@DisplayName("任务进度扫描器（TaskProgressScanner）")
class TaskProgressScannerTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 21);

    private static Map<String, String> row(String name, String owner, String status, String due, String note) {
        return Map.of("任务名称", name, "负责人", owner, "状态", status, "截止日期", due, "备注", note);
    }

    @Test
    void aggregatesStatusOverdueAndOwner() {
        List<Map<String, String>> rows = List.of(
                row("月度结算培训", "张三", "已完成", "2026-11-20", ""),
                row("催收清单核对", "李四", "进行中", "2026-11-21", "今日到期"),
                row("ERP 接口联调", "王五", "阻塞", "2026-11-19", "等待环境"),
                row("盘点复核", "李四", "未开始", "2026-11-26", ""),
                row("资金日报模板", "赵六", "doing", "2026-11-22", ""),
                row("", "张三", "已完成", "2026-11-20", "")); // 空任务名 → 跳过
        TaskDailyResult result = TaskProgressScanner.scan(rows, TODAY);

        assertEquals(5, result.total());
        assertEquals(1, result.byStatus().get(TaskProgressScanner.STATUS_DONE));
        assertEquals(2, result.byStatus().get(TaskProgressScanner.STATUS_IN_PROGRESS), "doing→进行中");
        assertEquals(1, result.byStatus().get(TaskProgressScanner.STATUS_NOT_STARTED));
        assertEquals(1, result.byStatus().get(TaskProgressScanner.STATUS_BLOCKED));
        assertEquals(1, result.overdue().size(), "截止 11-19 阻塞 → 逾期");
        assertEquals("ERP 接口联调", result.overdue().get(0).name());
        assertEquals(1, result.dueToday().size(), "截止 11-21 进行中 → 今日到期");
        // 按人：张三 1 / 李四 2 / 王五 1 / 赵六 1
        assertEquals(4, result.ownerSummary().size());
        TaskDailyResult.OwnerStat lisi = result.ownerSummary().stream()
                .filter(s -> s.owner().equals("李四")).findFirst().orElseThrow();
        assertEquals(2, lisi.total());
        assertEquals(1, lisi.inProgress());
        assertTrue(result.digest().contains("已逾期 1 项"));
        assertTrue(result.digest().contains("ERP 接口联调"));
    }

    @Test
    void statusVariantsNormalize() {
        assertEquals(TaskProgressScanner.STATUS_DONE, TaskProgressScanner.normalizeStatus("已经完成啦"));
        assertEquals(TaskProgressScanner.STATUS_BLOCKED, TaskProgressScanner.normalizeStatus("被供应商阻塞"));
        assertEquals(TaskProgressScanner.STATUS_IN_PROGRESS, TaskProgressScanner.normalizeStatus("处理中"));
        assertEquals(TaskProgressScanner.STATUS_NOT_STARTED, TaskProgressScanner.normalizeStatus(""));
        assertEquals(TaskProgressScanner.STATUS_NOT_STARTED, TaskProgressScanner.normalizeStatus("待启动"));
    }

    @Test
    void dateParsingSupportsCommonFormats() {
        assertEquals(LocalDate.of(2026, 11, 21), TaskProgressScanner.parseDate("2026-11-21"));
        assertEquals(LocalDate.of(2026, 11, 21), TaskProgressScanner.parseDate("2026/11/21"));
        assertEquals(TaskProgressScanner.parseDate("垃圾"), null);
        assertEquals(null, TaskProgressScanner.parseDate(""));
    }

    @Test
    void completedTaskNeverOverdue() {
        List<Map<String, String>> rows = List.of(
                row("早该完成的事", "张三", "已完成", "2026-11-01", ""));
        TaskDailyResult result = TaskProgressScanner.scan(rows, TODAY);
        assertEquals(0, result.overdue().size(), "已完成任务不算逾期");
    }
}
