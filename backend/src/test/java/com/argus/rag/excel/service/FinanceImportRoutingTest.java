package com.argus.rag.excel.service;

import com.argus.rag.auth.CurrentUserService;
import com.argus.rag.excel.entity.ExcelDocument;
import com.argus.rag.excel.entity.FinanceImportBatch;
import com.argus.rag.excel.entity.FinanceLedgerRecord;
import com.argus.rag.excel.finance.FinanceWorkbookExtractor;
import com.argus.rag.excel.mapper.FinanceImportBatchMapper;
import com.argus.rag.excel.mapper.FinanceLedgerRecordMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link FinanceImportService} 导入路由/记账批次/版本号判定行为锁
 * （纯 JVM + mock batchMapper：占位构造注入 mock mapper，零文件/POI/JDBC 真实调用）。
 * <p>锁契约：nextBatchVersion 空 bookKey/no latest/latest 版本空 → 1，否则 latest+1；
 * inferBookKey/inferRegion 按文件名含「北京/唐山」回退账本标识/区域；buildBatch 组装批次时
 * 显式 bookKey/region 优先、空回退推断，status=2(无跳过)/3(有跳过) 且 skipped 时拼 errorSummary。</p>
 */
@DisplayName("财务导入路由与批次决策（FinanceImportService）")
class FinanceImportRoutingTest {

    private FinanceImportBatchMapper batchMapper;
    private FinanceImportService service;

    @BeforeEach
    void setUp() {
        batchMapper = mock(FinanceImportBatchMapper.class);
        service = new FinanceImportService(
                mock(FinanceWorkbookExtractor.class),
                mock(ExcelDocumentService.class),
                mock(ExcelParserService.class),
                batchMapper,
                mock(FinanceLedgerRecordMapper.class),
                mock(JdbcTemplate.class),
                mock(ObjectMapper.class),
                mock(CurrentUserService.class),
                new com.argus.rag.common.config.ArgusRagProperties(),
                org.mockito.Mockito.mock(FinancePeriodService.class));
    }

    // ---------- nextBatchVersion ----------

    @Test
    void emptyOrNullBookKeyYieldsOne() {
        assertEquals(1, service.nextBatchVersion(""));
        assertEquals(1, service.nextBatchVersion(null));
        verify(batchMapper, never()).selectOne(any());
    }

    @Test
    void noLatestBatchYieldsOne() {
        when(batchMapper.selectOne(any())).thenReturn(null);
        assertEquals(1, service.nextBatchVersion("BJ"));
    }

    @Test
    void latestWithoutVersionYieldsOne() {
        FinanceImportBatch latest = new FinanceImportBatch();
        when(batchMapper.selectOne(any())).thenReturn(latest);
        assertEquals(1, service.nextBatchVersion("BJ"));
    }

    @Test
    void incrementsLatestVersion() {
        FinanceImportBatch latest = new FinanceImportBatch();
        latest.setBatchVersion(7);
        when(batchMapper.selectOne(any())).thenReturn(latest);
        assertEquals(8, service.nextBatchVersion("BJ"));
    }

    // ---------- inferBookKey ----------

    @Test
    void bookKeyFromFileName() {
        assertEquals("BJ-2026", service.inferBookKey("北京分公司台账.xlsx"));
        assertEquals("TS-2026", service.inferBookKey("唐山工厂.csv"));
        assertEquals("UNIT-2026", service.inferBookKey("广州.xlsx"));
        assertEquals("UNIT-2026", service.inferBookKey(null));
    }

    // ---------- inferRegion ----------

    @Test
    void regionFromFileName() {
        assertEquals("北京", service.inferRegion("北京分公司.xlsx"));
        assertEquals("唐山", service.inferRegion("唐山厂.csv"));
        assertEquals("其他单位", service.inferRegion("上海.xlsx"));
        assertEquals("其他单位", service.inferRegion(null));
    }

    // ---------- buildBatch ----------

    private static ExcelDocument doc() {
        ExcelDocument d = new ExcelDocument();
        d.setId(9L);
        d.setFilePath("/tmp/finance.xlsx");
        d.setFileSize(1234L);
        return d;
    }

    private static FinanceWorkbookExtractor.ExtractionResult extraction(
            int importedSheets, int skippedRows, Map<String, String> skippedSheets, int recordCount) {
        FinanceWorkbookExtractor.ExtractionResult r = new FinanceWorkbookExtractor.ExtractionResult();
        r.importedSheets = importedSheets;
        r.skippedRows = skippedRows;
        r.skippedSheets.clear();
        r.skippedSheets.putAll(skippedSheets);
        for (int i = 0; i < recordCount; i++) {
            r.records.add(new FinanceLedgerRecord());
        }
        return r;
    }

    @Test
    void buildBatchFallsBackBookAndRegionFromFileName() {
        var extraction = extraction(2, 0, Map.of(), 3);
        FinanceImportBatch b = service.buildBatch(doc(), "北京单位.xlsx", "", "", extraction);
        assertEquals("BJ-2026", b.getBookKey());
        assertEquals("北京", b.getRegionName());
    }

    @Test
    void buildBatchPrefersExplicitBookAndRegion() {
        var extraction = extraction(2, 0, Map.of(), 3);
        FinanceImportBatch b = service.buildBatch(doc(), "北京.xlsx", "TS-2026", "上海", extraction);
        assertEquals("TS-2026", b.getBookKey());
        assertEquals("上海", b.getRegionName());
    }

    @Test
    void buildBatchStatusTwoWhenNoSkippedSheets() {
        var extraction = extraction(2, 0, Map.of(), 5);
        FinanceImportBatch b = service.buildBatch(doc(), "月末.xlsx", "BJ-2026", "北京", extraction);
        assertEquals(2, b.getStatus());
        assertNull(b.getErrorSummary());
        assertEquals(2, b.getTotalSheets());
        assertEquals(5, b.getTotalRows());
        assertEquals(5, b.getMappedRows());
        assertEquals(0, b.getSkippedRows());
    }

    @Test
    void buildBatchStatusThreeAndErrorSummaryWhenSkipped() {
        Map<String, String> skipped = new LinkedHashMap<>();
        skipped.put("Sheet1", "表头不匹配");
        var extraction = extraction(1, 4, skipped, 2);
        FinanceImportBatch b = service.buildBatch(doc(), "唐山.xlsx", "TS-2026", "唐山", extraction);
        assertEquals(3, b.getStatus());
        assertEquals("跳过 Sheet: Sheet1", b.getErrorSummary());
        assertEquals(2, b.getTotalSheets());
        assertEquals(6, b.getTotalRows());
        assertEquals(4, b.getSkippedRows());
    }
}
