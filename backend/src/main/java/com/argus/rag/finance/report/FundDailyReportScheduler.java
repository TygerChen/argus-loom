package com.argus.rag.finance.report;

import com.argus.rag.finance.report.entity.ReportDefinition;
import com.argus.rag.finance.report.mapper.ReportDefinitionMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;

/**
 * 资金日报定时触发器：每天按 cron 对所有启用的资金日报定义各生成一次。
 *
 * <p>默认关闭（{@code argus.fund-report.schedule-enabled=false}），开启后替代
 * WorkBuddy 的外部定时任务。工作日闸门、勾稽拦截、钉钉推送语义均在
 * {@link FundDailyReportService#generate} 内；调度器只负责"到点叫人"，
 * 单条失败不影响其他定义（每条独立 try-catch）。</p>
 */
@Component
public class FundDailyReportScheduler {

    private static final Logger log = LoggerFactory.getLogger(FundDailyReportScheduler.class);

    private final FundDailyReportService reportService;
    private final ReportDefinitionMapper definitionMapper;

    @Value("${argus.fund-report.schedule-enabled:false}")
    private boolean scheduleEnabled;

    public FundDailyReportScheduler(FundDailyReportService reportService,
                                    ReportDefinitionMapper definitionMapper) {
        this.reportService = reportService;
        this.definitionMapper = definitionMapper;
    }

    /** 默认每天 20:18（与技能定时任务一致），可用 argus.fund-report.schedule-cron 覆盖。 */
    @Scheduled(cron = "${argus.fund-report.schedule-cron:0 18 20 * * *}")
    public void runDailyReports() {
        if (!scheduleEnabled) {
            return;
        }
        LocalDate today = LocalDate.now();
        List<ReportDefinition> definitions = definitionMapper.selectList(new QueryWrapper<ReportDefinition>()
                .eq("report_type", "FUND_DAILY")
                .eq("enabled", true));
        log.info("资金日报定时触发: date={}, definitions={}", today, definitions.size());
        for (ReportDefinition definition : definitions) {
            try {
                reportService.generate(today, definition.getOwnerUserId());
            } catch (Exception e) {
                log.error("资金日报定时生成失败: definitionId={}, owner={}",
                        definition.getId(), definition.getOwnerUserId(), e);
            }
        }
    }
}
