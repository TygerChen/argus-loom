package com.argus.rag.report.task;

import com.argus.rag.finance.report.entity.ReportDefinition;
import com.argus.rag.finance.report.entity.ReportExecution;
import com.argus.rag.finance.report.mapper.ReportDefinitionMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;

/**
 * 任务日报调度器 —— 每日定时为所有绑定了任务表的定义生成日报。
 *
 * <p>默认开启、每日 18:00（方案默认节奏）；cron 与开关均可配：
 * {@code argus.rag.task-report.schedule-enabled / schedule-cron}。
 * 钉钉 webhook 未配置时推送自动 SKIPPED（报告仍落库可看）——fail-open，
 * 配好领导群机器人后无需改代码自动开始推送。</p>
 */
@Component
public class TaskDailyReportScheduler {

    private static final Logger log = LoggerFactory.getLogger(TaskDailyReportScheduler.class);
    private static final String REPORT_TYPE = "TASK_DAILY";

    private final TaskDailyReportService reportService;
    private final ReportDefinitionMapper definitionMapper;
    private final boolean enabled;

    public TaskDailyReportScheduler(
            TaskDailyReportService reportService,
            ReportDefinitionMapper definitionMapper,
            @org.springframework.beans.factory.annotation.Value(
                    "${argus.rag.task-report.schedule-enabled:true}") boolean enabled
    ) {
        this.reportService = reportService;
        this.definitionMapper = definitionMapper;
        this.enabled = enabled;
    }

    @Scheduled(cron = "${argus.rag.task-report.schedule-cron:0 0 18 * * *}")
    public void runDailyReports() {
        if (!enabled) {
            return;
        }
        LocalDate today = LocalDate.now();
        List<ReportDefinition> definitions = definitionMapper.selectList(
                new LambdaQueryWrapper<ReportDefinition>()
                        .eq(ReportDefinition::getReportType, REPORT_TYPE)
                        .eq(ReportDefinition::getEnabled, true));
        log.info("任务日报定时生成开始: date={}, definitions={}", today, definitions.size());
        for (ReportDefinition definition : definitions) {
            try {
                ReportExecution execution = reportService.generate(today, definition.getOwnerUserId());
                log.info("任务日报生成完成: owner={}, status={}, push={}",
                        definition.getOwnerUserId(), execution.getStatus(), execution.getPushStatus());
            } catch (RuntimeException e) {
                // 单个定义失败不影响其余用户（调度是批处理，逐户容错）
                log.error("任务日报定时生成失败: owner={}", definition.getOwnerUserId(), e);
            }
        }
    }
}
