package com.argus.rag.excel.service;

import com.argus.rag.auth.CurrentUserService;
import com.argus.rag.common.exception.BusinessException;
import com.argus.rag.common.exception.ErrorCodes;
import com.argus.rag.excel.entity.FinanceLedgerRecord;
import com.argus.rag.excel.entity.FinancePeriod;
import com.argus.rag.excel.finance.FinanceWorkbookExtractor;
import com.argus.rag.excel.mapper.FinanceImportBatchMapper;
import com.argus.rag.excel.mapper.FinanceLedgerRecordMapper;
import com.argus.rag.excel.mapper.FinancePeriodMapper;
import com.argus.rag.excel.model.dto.FinanceRecordUpdateRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 财务导入/编辑的期间关闭与文件幂等守卫（任务 10）：
 * 已关闭期间的流水禁止修改；同一文件不允许二次导入产生重复批次。
 */
@DisplayName("财务期间与文件幂等守卫（FinanceImportService + FinancePeriodService）")
class FinanceImportPeriodGuardTest {

    private FinancePeriodMapper periodMapper;
    private FinanceLedgerRecordMapper ledgerMapper;
    private FinanceImportBatchMapper batchMapper;
    private FinanceImportService service;

    @BeforeEach
    void setUp() {
        periodMapper = mock(FinancePeriodMapper.class);
        ledgerMapper = mock(FinanceLedgerRecordMapper.class);
        batchMapper = mock(FinanceImportBatchMapper.class);
        service = new FinanceImportService(
                mock(FinanceWorkbookExtractor.class),
                mock(com.argus.rag.excel.service.ExcelDocumentService.class),
                mock(ExcelParserService.class),
                batchMapper,
                ledgerMapper,
                mock(JdbcTemplate.class),
                mock(ObjectMapper.class),
                mock(CurrentUserService.class),
                new com.argus.rag.common.config.ArgusRagProperties(),
                new FinancePeriodService(periodMapper));
    }

    @Test
    @DisplayName("已关闭期间的流水禁止修改：抛 FINANCE_PERIOD_CLOSED 且不落更新")
    void closedPeriodBlocksRecordUpdate() {
        FinanceLedgerRecord record = new FinanceLedgerRecord();
        record.setId(1L);
        record.setBusinessDate(LocalDate.of(2026, 9, 15));
        when(ledgerMapper.selectByIdForUpdate(1L)).thenReturn(record);
        FinancePeriod closed = new FinancePeriod();
        closed.setPeriodMonth("2026-09");
        closed.setStatus("CLOSED");
        when(periodMapper.selectOne(any())).thenReturn(closed);

        assertThatThrownBy(() -> service.batchUpdateRecords(java.util.List.of(
                new FinanceRecordUpdateRequest(1L, null, null, null, null, null, null, null,
                        null, null, null, null, null, null, null, null, null, null, null, null))))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ErrorCodes.FINANCE_PERIOD_CLOSED));
        verify(ledgerMapper, never()).update(any(), any());
    }

    @Test
    @DisplayName("期间开放时正常更新")
    void openPeriodAllowsRecordUpdate() {
        FinanceLedgerRecord record = new FinanceLedgerRecord();
        record.setId(2L);
        record.setBusinessDate(LocalDate.of(2026, 10, 8));
        when(ledgerMapper.selectByIdForUpdate(2L)).thenReturn(record);
        when(periodMapper.selectOne(any())).thenReturn(null);
        when(ledgerMapper.update(any(), any())).thenReturn(1);

        int updated = service.batchUpdateRecords(java.util.List.of(
                new FinanceRecordUpdateRequest(2L, null, null, null, null, null, null, null,
                        null, null, null, null, null, null, null, null, null, null, null, null)));

        assertThat(updated).isEqualTo(1);
    }

    @Test
    @DisplayName("同一文件二次导入被拒绝：抛 FINANCE_FILE_DUPLICATED")
    void duplicateFileRejected() {
        when(batchMapper.selectCount(any())).thenReturn(1L);

        assertThatThrownBy(() -> service.assertFileNotImported("abc123hash"))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ErrorCodes.FINANCE_FILE_DUPLICATED));
    }

    @Test
    @DisplayName("新文件放行")
    void freshFileAllowed() {
        when(batchMapper.selectCount(any())).thenReturn(0L);

        assertThatCode(() -> service.assertFileNotImported("new-hash"))
                .doesNotThrowAnyException();
    }
}


