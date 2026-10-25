package com.argus.rag.excel.service;

import com.argus.rag.auth.CurrentUserService;
import com.argus.rag.common.exception.BusinessException;
import com.argus.rag.common.exception.ErrorCodes;
import com.argus.rag.excel.entity.FinanceImportBatch;
import com.argus.rag.excel.entity.FinanceLedgerRecord;
import com.argus.rag.excel.finance.FinanceWorkbookExtractor;
import com.argus.rag.excel.mapper.FinanceImportBatchMapper;
import com.argus.rag.excel.mapper.FinanceLedgerRecordMapper;
import com.argus.rag.excel.model.dto.FinanceRecordUpdateRequest;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link FinanceImportService} 财务一致性守卫（P0）：不改 POI/文件/聚合 SQL，
 * 只锁「批量改流水的乐观锁指纹」与「版本激活」两条关键业务分支的抛错/提交语义。
 * 整个类纯 mock（mapper + jdbcTemplate），无文件系统、无系统字体依赖，可在 CI 跑。
 */
@DisplayName("财务导入关键分支（FinanceImportService）")
class FinanceImportServiceTest {

    private FinanceImportBatchMapper batchMapper;
    private FinanceLedgerRecordMapper ledgerMapper;
    private FinanceImportService service;

    @BeforeEach
    void setUp() {
        batchMapper = mock(FinanceImportBatchMapper.class);
        ledgerMapper = mock(FinanceLedgerRecordMapper.class);
        service = new FinanceImportService(
                mock(FinanceWorkbookExtractor.class),
                mock(ExcelDocumentService.class),
                mock(ExcelParserService.class),
                batchMapper,
                ledgerMapper,
                mock(JdbcTemplate.class),
                mock(ObjectMapper.class),
                mock(CurrentUserService.class),
                new com.argus.rag.common.config.ArgusRagProperties(),
                org.mockito.Mockito.mock(FinancePeriodService.class));
    }

    // ── batchUpdateRecords：乐观锁指纹 ──

    @Test
    void batchUpdateEmptyRequestsIsNoOp() {
        assertEquals(0, service.batchUpdateRecords(null));
        assertEquals(0, service.batchUpdateRecords(List.of()));
        verify(ledgerMapper, never()).selectById(any());
    }

    @Test
    void batchUpdateRejectsNullId() {
        FinanceRecordUpdateRequest r = new FinanceRecordUpdateRequest(
                null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null,
                null, null, null);
        // P1-03：裸 IllegalArgumentException → 带语义的 BusinessException
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.batchUpdateRecords(List.of(r)));
        assertEquals("明细记录 ID 不能为空", ex.getMessage());
    }

    @Test
    void batchUpdateRejectsMissingRecord() {
        when(ledgerMapper.selectByIdForUpdate(42L)).thenReturn(null);
        FinanceRecordUpdateRequest r = new FinanceRecordUpdateRequest(
                42L, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null,
                null, null, null);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.batchUpdateRecords(List.of(r)));
        assertEquals(ErrorCodes.EXCEL_NOT_FOUND, ex.getCode());
        assertTrue(ex.getMessage().contains("42"));
    }

    @Test
    void batchUpdateRejectsStaleFingerprint() {
        FinanceLedgerRecord current = new FinanceLedgerRecord();
        current.setId(42L);
        current.setSummary("核对无误");
        when(ledgerMapper.selectByIdForUpdate(42L)).thenReturn(current);

        FinanceRecordUpdateRequest r = new FinanceRecordUpdateRequest(
                42L, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null,
                null, null, "tampered");
        // 并发冲突语义：改用带稳定码的 BusinessException，前端可按 CONCURRENT_MODIFIED 提示刷新重试
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.batchUpdateRecords(List.of(r)));
        assertEquals(ErrorCodes.CONCURRENT_MODIFIED, ex.getCode());
        assertTrue(ex.getMessage().contains("请刷新后重试"));
    }

    @Test
    void batchUpdateWithoutFingerprintProceedsAndCounts() {
        FinanceLedgerRecord current = new FinanceLedgerRecord();
        current.setId(42L);
        when(ledgerMapper.selectByIdForUpdate(42L)).thenReturn(current);
        when(ledgerMapper.update(eq(null), any(UpdateWrapper.class))).thenReturn(1);

        FinanceRecordUpdateRequest r = new FinanceRecordUpdateRequest(
                42L, null, null, "新摘要", null, null, null, null,
                null, null, null, null, null, null, null, null, null,
                null, null, null);
        assertEquals(1, service.batchUpdateRecords(List.of(r)));
        verify(ledgerMapper).update(eq(null), any(UpdateWrapper.class));
    }

    // ── activateBatch：版本激活 ──

    @Test
    void activateBatchRejectsMissing() {
        when(batchMapper.selectById(9L)).thenReturn(null);
        BusinessException ex = assertThrows(BusinessException.class, () -> service.activateBatch(9L));
        assertEquals(ErrorCodes.EXCEL_NOT_FOUND, ex.getCode());
    }

    @Test
    void activateBatchRejectsBatchWithoutBookKey() {
        FinanceImportBatch target = new FinanceImportBatch();
        target.setId(9L);
        when(batchMapper.selectById(9L)).thenReturn(target);
        assertThrows(BusinessException.class, () -> service.activateBatch(9L));
    }

    @Test
    void activateBatchSwitchesAndMarksCurrent() {
        FinanceImportBatch target = new FinanceImportBatch();
        target.setId(9L);
        target.setBookKey("BJ-2026");
        when(batchMapper.selectById(9L)).thenReturn(target);

        FinanceImportBatch result = service.activateBatch(9L);
        assertSame(target, result);
        assertEquals(1, result.getIsCurrent());
        // 同账本旧批次先被标记为非当前
        verify(batchMapper).update(eq(null), any(UpdateWrapper.class));
        verify(batchMapper).updateById(target);
    }

    // ── listBatchVersions ──

    @Test
    void listVersionsWithoutBookKeyReturnsEmpty() {
        assertEquals(List.of(), service.listBatchVersions(""));
        assertEquals(List.of(), service.listBatchVersions(null));
        verify(batchMapper, never()).selectList(any());
    }
}
