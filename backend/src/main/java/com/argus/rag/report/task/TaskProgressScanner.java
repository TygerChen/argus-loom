package com.argus.rag.report.task;

import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 任务进度扫描器 —— 把在线表格的行数据聚合成日报统计（纯函数，独立可测）。
 *
 * <p><b>解析口径（用户拍板的默认 5 列）</b>：任务名称/负责人/状态/截止日期/备注。
 * 状态归一化：含"完成"→已完成；含"阻塞"→阻塞；含"进行"→进行中；其余→未开始
 * （团队手填的口语变体不炸链路）。逾期=截止日&lt;今天且未完成；今日到期=截止日=今天且未完成。</p>
 */
public final class TaskProgressScanner {

    public static final String STATUS_DONE = "已完成";
    public static final String STATUS_IN_PROGRESS = "进行中";
    public static final String STATUS_BLOCKED = "阻塞";
    public static final String STATUS_NOT_STARTED = "未开始";

    private TaskProgressScanner() {
    }

    /**
     * 扫描任务行（表头→值的映射列表，来自在线表格快照）。
     *
     * @param rows  数据行（不含表头）
     * @param today 今天（逾期/到期判定基准）
     * @return 聚合结果
     */
    public static TaskDailyResult scan(List<Map<String, String>> rows, LocalDate today) {
        int total = 0;
        Map<String, Integer> byStatus = new LinkedHashMap<>();
        byStatus.put(STATUS_DONE, 0);
        byStatus.put(STATUS_IN_PROGRESS, 0);
        byStatus.put(STATUS_NOT_STARTED, 0);
        byStatus.put(STATUS_BLOCKED, 0);
        List<TaskDailyResult.TaskItem> dueToday = new ArrayList<>();
        List<TaskDailyResult.TaskItem> overdue = new ArrayList<>();
        Map<String, int[]> byOwner = new LinkedHashMap<>();

        for (Map<String, String> row : rows) {
            String name = text(row, "任务名称");
            if (!StringUtils.hasText(name)) {
                continue;
            }
            total++;
            String owner = textOr(row, "负责人", "未指派");
            String status = normalizeStatus(text(row, "状态"));
            String dueRaw = text(row, "截止日期");
            LocalDate due = parseDate(dueRaw);
            String note = text(row, "备注");

            byStatus.merge(status, 1, Integer::sum);
            int[] slot = byOwner.computeIfAbsent(owner, k -> new int[5]);
            slot[0]++;
            switch (status) {
                case STATUS_DONE -> slot[1]++;
                case STATUS_IN_PROGRESS -> slot[2]++;
                case STATUS_BLOCKED -> slot[3]++;
                default -> slot[4]++;
            }

            boolean open = !STATUS_DONE.equals(status);
            if (open && due != null) {
                if (due.isBefore(today)) {
                    overdue.add(new TaskDailyResult.TaskItem(name, owner, dueRaw, note));
                } else if (due.isEqual(today)) {
                    dueToday.add(new TaskDailyResult.TaskItem(name, owner, dueRaw, note));
                }
            }
        }

        List<TaskDailyResult.OwnerStat> ownerSummary = new ArrayList<>();
        for (Map.Entry<String, int[]> entry : byOwner.entrySet()) {
            int[] slot = entry.getValue();
            ownerSummary.add(new TaskDailyResult.OwnerStat(entry.getKey(), slot[0], slot[1], slot[2], slot[3], slot[4]));
        }

        return new TaskDailyResult(total, byStatus, dueToday, overdue, ownerSummary, buildDigest(total, byStatus, overdue, dueToday));
    }

    /** AI 解读与日报正文的统计摘要（Markdown 片段）。 */
    private static String buildDigest(int total, Map<String, Integer> byStatus,
                                      List<TaskDailyResult.TaskItem> overdue,
                                      List<TaskDailyResult.TaskItem> dueToday) {
        StringBuilder sb = new StringBuilder();
        sb.append("任务总数 ").append(total)
                .append("：已完成 ").append(byStatus.getOrDefault(STATUS_DONE, 0))
                .append("，进行中 ").append(byStatus.getOrDefault(STATUS_IN_PROGRESS, 0))
                .append("，未开始 ").append(byStatus.getOrDefault(STATUS_NOT_STARTED, 0))
                .append("，阻塞 ").append(byStatus.getOrDefault(STATUS_BLOCKED, 0)).append("。\n");
        sb.append("今日到期 ").append(dueToday.size()).append(" 项");
        for (TaskDailyResult.TaskItem item : dueToday) {
            sb.append("；").append(item.name()).append("（").append(item.owner()).append("）");
        }
        sb.append("。\n已逾期 ").append(overdue.size()).append(" 项");
        for (TaskDailyResult.TaskItem item : overdue) {
            sb.append("；").append(item.name()).append("（").append(item.owner()).append("，截止 ")
                    .append(item.dueDate()).append("）");
        }
        sb.append("。");
        return sb.toString();
    }

    /** 状态归一化：口语变体映射到四态，未匹配归"未开始"。 */
    static String normalizeStatus(String raw) {
        if (!StringUtils.hasText(raw)) {
            return STATUS_NOT_STARTED;
        }
        String text = raw.trim();
        if (text.contains("完成") || text.contains("done")) {
            return STATUS_DONE;
        }
        if (text.contains("阻塞") || text.contains("阻碍") || text.contains("blocked")) {
            return STATUS_BLOCKED;
        }
        if (text.contains("进行") || text.contains("在做") || text.contains("处理中") || text.contains("doing")) {
            return STATUS_IN_PROGRESS;
        }
        return STATUS_NOT_STARTED;
    }

    /** 截止日期解析：支持 yyyy-MM-dd / yyyy/M/d；解析失败返回 null（不炸日报）。 */
    static LocalDate parseDate(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        String text = raw.trim().replace('/', '-').replace('.', '-');
        try {
            return LocalDate.parse(text, DateTimeFormatter.ofPattern("yyyy-M-d"));
        } catch (DateTimeParseException ignored) {
            // 尝试下一种格式
        }
        try {
            return LocalDate.parse(text);
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    private static String text(Map<String, String> row, String key) {
        String value = row.get(key);
        return value == null ? "" : value.trim();
    }

    private static String textOr(Map<String, String> row, String key, String fallback) {
        String value = text(row, key);
        return StringUtils.hasText(value) ? value : fallback;
    }
}
