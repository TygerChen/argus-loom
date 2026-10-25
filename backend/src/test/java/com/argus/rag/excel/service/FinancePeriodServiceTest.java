package com.argus.rag.excel.service;

import com.argus.rag.common.exception.BusinessException;
import com.argus.rag.common.exception.ErrorCodes;
import com.argus.rag.excel.entity.FinancePeriod;
import com.argus.rag.excel.mapper.FinancePeriodMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 财务期间关闭服务契约（任务 10）：
 * 关闭后该月禁止导入与编辑；重新打开必须填写原因；关闭/重开都留下操作人与原因审计。
 */
@DisplayName("财务期间关闭（FinancePeriodService）")
class FinancePeriodServiceTest {

    private FinancePeriodMapper mapper;
    private FinancePeriodService service;

    @BeforeEach
    void setUp() {
        mapper = mock(FinancePeriodMapper.class);
        service = new FinancePeriodService(mapper);
    }

    @Test
    @DisplayName("关闭期间必须填写原因")
    void closeRequiresReason() {
        assertThatThrownBy(() -> service.close("2026-09", 7L, "  "))
                .isInstanceOf(BusinessException.class);
        verify(mapper, never()).insert(any(FinancePeriod.class));
    }

    @Test
    @DisplayName("关闭不存在的期间：新建一行并记录 CLOSED 与审计字段")
    void closeCreatesClosedPeriod() {
        when(mapper.selectOne(any())).thenReturn(null);

        FinancePeriod result = service.close("2026-09", 7L, "月度结账");

        ArgumentCaptor<FinancePeriod> cap = ArgumentCaptor.forClass(FinancePeriod.class);
        verify(mapper).insert(cap.capture());
        assertThat(cap.getValue().getPeriodMonth()).isEqualTo("2026-09");
        assertThat(cap.getValue().getStatus()).isEqualTo("CLOSED");
        assertThat(cap.getValue().getClosedBy()).isEqualTo(7L);
        assertThat(cap.getValue().getClosedReason()).isEqualTo("月度结账");
        assertThat(cap.getValue().getClosedAt()).isNotNull();
        assertThat(result.getStatus()).isEqualTo("CLOSED");
    }

    @Test
    @DisplayName("重新打开期间必须填写原因")
    void reopenRequiresReason() {
        assertThatThrownBy(() -> service.reopen("2026-09", 7L, null))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("重新打开：状态回 OPEN 并记录重开人与原因")
    void reopenSetsOpenWithAudit() {
        FinancePeriod closed = new FinancePeriod();
        closed.setId(1L);
        closed.setPeriodMonth("2026-09");
        closed.setStatus("CLOSED");
        when(mapper.selectOne(any())).thenReturn(closed);

        FinancePeriod result = service.reopen("2026-09", 9L, "补录凭证");

        verify(mapper).updateById(closed);
        assertThat(result.getStatus()).isEqualTo("OPEN");
        assertThat(result.getReopenedBy()).isEqualTo(9L);
        assertThat(result.getReopenReason()).isEqualTo("补录凭证");
        assertThat(result.getReopenedAt()).isNotNull();
    }

    @Test
    @DisplayName("已关闭期间拒绝操作：抛 FINANCE_PERIOD_CLOSED")
    void closedPeriodBlocksOperations() {
        FinancePeriod closed = new FinancePeriod();
        closed.setPeriodMonth("2026-09");
        closed.setStatus("CLOSED");
        when(mapper.selectOne(any())).thenReturn(closed);

        assertThatThrownBy(() -> service.assertPeriodOpen(LocalDate.of(2026, 9, 15)))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ErrorCodes.FINANCE_PERIOD_CLOSED));
    }

    @Test
    @DisplayName("期间不存在或 OPEN 时放行")
    void openOrMissingPeriodAllowsOperations() {
        when(mapper.selectOne(any())).thenReturn(null);

        service.assertPeriodOpen(LocalDate.of(2026, 9, 15));

        FinancePeriod open = new FinancePeriod();
        open.setPeriodMonth("2026-10");
        open.setStatus("OPEN");
        when(mapper.selectOne(any())).thenReturn(open);
        service.assertPeriodOpen(LocalDate.of(2026, 10, 1));
    }
}
