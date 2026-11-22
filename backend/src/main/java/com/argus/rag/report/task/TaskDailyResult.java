package com.argus.rag.report.task;

import java.util.List;
import java.util.Map;

/**
 * 任务日报聚合结果（纯数据，scanner 产出、service 消费）。
 *
 * @param total       任务总数
 * @param byStatus    按状态计数（已完成/进行中/未开始/阻塞）
 * @param dueToday    今日到期（未完成的）任务
 * @param overdue     已逾期任务（截止日 < 今天 且未完成）
 * @param ownerSummary 按负责人汇总：owner → {total, done, inProgress, blocked, notStarted}
 * @param digest      喂给 AI 解读的统计摘要（Markdown 片段）
 */
public record TaskDailyResult(
        int total,
        Map<String, Integer> byStatus,
        List<TaskItem> dueToday,
        List<TaskItem> overdue,
        List<OwnerStat> ownerSummary,
        String digest
) {

    /** 单条任务摘要（不携带备注全文——日报要简洁）。 */
    public record TaskItem(String name, String owner, String dueDate, String note) {
    }

    /** 负责人维度统计。 */
    public record OwnerStat(String owner, int total, int done, int inProgress, int blocked, int notStarted) {
    }
}
