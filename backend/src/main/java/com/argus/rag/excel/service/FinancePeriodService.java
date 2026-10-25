package com.argus.rag.excel.service;

import com.argus.rag.common.exception.BusinessException;
import com.argus.rag.common.exception.ErrorCodes;
import com.argus.rag.excel.entity.FinancePeriod;
import com.argus.rag.excel.mapper.FinancePeriodMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 财务期间关闭服务（任务 10）。
 *
 * <p>核心规则：期间关闭后，业务日期落在该月的导入与编辑一律拒绝（fail-closed）；
 * 重新打开必须填写原因；关闭与重开都记录操作人与原因，形成审计线索。</p>
 */
@Service
public class FinancePeriodService {

    private static final Logger log = LoggerFactory.getLogger(FinancePeriodService.class);

    private final FinancePeriodMapper periodMapper;

    public FinancePeriodService(FinancePeriodMapper periodMapper) {
        this.periodMapper = periodMapper;
    }

    /** 关闭期间：原因必填。期间不存在时创建，已关闭时拒绝重复关闭。 */
    @Transactional
    public FinancePeriod close(String periodMonth, Long operatorId, String reason) {
        if (!StringUtils.hasText(reason)) {
            throw new BusinessException(ErrorCodes.FINANCE_PERIOD_REASON_REQUIRED, "关闭期间必须填写原因");
        }
        FinancePeriod period = selectByMonth(periodMonth);
        if (period == null) {
            period = new FinancePeriod();
            period.setPeriodMonth(periodMonth);
        } else if (FinancePeriod.STATUS_CLOSED.equals(period.getStatus())) {
            throw new BusinessException("该期间已是关闭状态");
        }
        period.setStatus(FinancePeriod.STATUS_CLOSED);
        period.setClosedBy(operatorId);
        period.setClosedReason(reason.trim());
        period.setClosedAt(LocalDateTime.now());
        if (period.getId() == null) {
            periodMapper.insert(period);
        } else {
            periodMapper.updateById(period);
        }
        log.info("财务期间已关闭: period={}, operator={}", periodMonth, operatorId);
        return period;
    }

    /** 重新打开期间：原因必填，只有授权调用方（控制器按财务角色校验）能到达这里。 */
    @Transactional
    public FinancePeriod reopen(String periodMonth, Long operatorId, String reason) {
        if (!StringUtils.hasText(reason)) {
            throw new BusinessException(ErrorCodes.FINANCE_PERIOD_REASON_REQUIRED, "重新打开期间必须填写原因");
        }
        FinancePeriod period = selectByMonth(periodMonth);
        if (period == null) {
            throw new BusinessException(ErrorCodes.EXCEL_NOT_FOUND, "期间不存在: " + periodMonth);
        }
        period.setStatus(FinancePeriod.STATUS_OPEN);
        period.setReopenedBy(operatorId);
        period.setReopenReason(reason.trim());
        period.setReopenedAt(LocalDateTime.now());
        periodMapper.updateById(period);
        log.info("财务期间已重新打开: period={}, operator={}", periodMonth, operatorId);
        return period;
    }

    /** 校验业务日期所在期间处于开放状态；关闭即抛 FINANCE_PERIOD_CLOSED。 */
    public void assertPeriodOpen(LocalDate businessDate) {
        if (businessDate == null) {
            return;
        }
        FinancePeriod period = selectByMonth(
                businessDate.format(DateTimeFormatter.ofPattern("yyyy-MM")));
        if (period != null && FinancePeriod.STATUS_CLOSED.equals(period.getStatus())) {
            throw new BusinessException(ErrorCodes.FINANCE_PERIOD_CLOSED,
                    "期间 " + businessDate.format(DateTimeFormatter.ofPattern("yyyy-MM")) + " 已关闭，禁止修改财务数据");
        }
    }

    /** 期间状态查询（供列表/详情展示）。 */
    public FinancePeriod findByMonth(String periodMonth) {
        return selectByMonth(periodMonth);
    }

    private FinancePeriod selectByMonth(String periodMonth) {
        if (!StringUtils.hasText(periodMonth)) {
            return null;
        }
        return periodMapper.selectOne(new QueryWrapper<FinancePeriod>()
                .eq("period_month", periodMonth)
                .last("LIMIT 1"));
    }
}
