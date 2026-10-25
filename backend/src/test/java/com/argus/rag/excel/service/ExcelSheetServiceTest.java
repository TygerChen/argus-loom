package com.argus.rag.excel.service;

import com.argus.rag.common.exception.BusinessException;
import com.argus.rag.common.exception.ErrorCodes;
import com.argus.rag.excel.entity.ExcelSheet;
import com.argus.rag.excel.entity.ExcelSheetChunk;
import com.argus.rag.excel.entity.ExcelSheetChunkDelta;
import com.argus.rag.excel.mapper.ExcelSheetChunkDeltaMapper;
import com.argus.rag.excel.mapper.ExcelSheetChunkMapper;
import com.argus.rag.excel.mapper.ExcelSheetMapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ExcelSheetService} 契约（全部 mock / 零 DB / 零字体）：
 * - Sheet 语义：null / 软删(status=3) 视为不存在、存活返回；无历史插入默认 status=1 / chunkCount=0；
 * - 分块读取（delta 增量合并后回写缓存）；单格快照（降错 cell 定位 + 缺失语义）；
 * - 批量更新：跨块分组、缺失块自动 new + chunkCount 上抬、delta insert/update 复用、缓存失效；
 * - 重建 ({@code replaceWorkbook}) 与删除（软删 sheet + 硬删 chunk/delta）。
 * 注：compactSheetDeltas 阈值(>=500)与后续 MyBatis-Plus update 的强 tabInfo 段不在纯单测断言，
 * 以免依赖运行时缓存硬凑（同 FinancePdfRenderer 先例）。
 */
@DisplayName("Excel Sheet 分块与增量（ExcelSheetService）")
class ExcelSheetServiceTest {

    private ExcelSheetMapper sheetMapper;
    private ExcelSheetChunkMapper chunkMapper;
    private ExcelSheetChunkDeltaMapper deltaMapper;
    private ExcelDocumentService documentService;
    private RedisChunkCacheService cacheService;
    private ExcelSheetService service;

    @BeforeEach
    void setUp() {
        sheetMapper = mock(ExcelSheetMapper.class);
        chunkMapper = mock(ExcelSheetChunkMapper.class);
        deltaMapper = mock(ExcelSheetChunkDeltaMapper.class);
        documentService = mock(ExcelDocumentService.class);
        cacheService = mock(RedisChunkCacheService.class);
        // 锁服务用真实实例：runWithLock 需真正执行内部写入，供批量更新断言复用。
        ExcelDocumentLockService lockService = new ExcelDocumentLockService();
        service = new ExcelSheetService(sheetMapper, chunkMapper, deltaMapper,
                documentService, cacheService, lockService);
    }

    private ExcelSheet sheet(Long id, Integer status) {
        ExcelSheet s = new ExcelSheet();
        s.setId(id);
        s.setStatus(status);
        // 分块缓存以 (documentId, sheetId) 为键；真实 Sheet 行必有 documentId，这里保持一致
        s.setDocumentId(1L);
        return s;
    }

    private ExcelSheetChunk chunk(Long sheetId, int chunkIndex, String celldata) {
        ExcelSheetChunk c = new ExcelSheetChunk();
        c.setDocumentId(1L);
        c.setSheetId(sheetId);
        c.setChunkIndex(chunkIndex);
        c.setCelldataJson(celldata);
        return c;
    }

    private ExcelSheetChunkDelta delta(Integer row, Integer col, String valueJson) {
        ExcelSheetChunkDelta d = new ExcelSheetChunkDelta();
        d.setSheetId(1L);
        d.setChunkIndex(0);
        d.setCellRow(row);
        d.setCellCol(col);
        d.setCellValue(valueJson);
        return d;
    }

    /** 构造 batchUpdateCells 的单条更新记录：v 按格子形态包为 JSONObject{"v":值} */
    private Map<String, Object> update(Object val, int r, int c) {
        Map<String, Object> u = new HashMap<>();
        u.put("sheetId", 1L);
        u.put("r", r);
        u.put("c", c);
        Map<String, Object> vObj = new LinkedHashMap<>();
        vObj.put("v", val);
        u.put("v", vObj);
        return u;
    }

    // ---------- getSheetById ----------

    @Test
    void getSheetByIdNullReturnsNull() {
        assertNull(service.getSheetById(null));
    }

    @Test
    void getSheetByIdDeletedStatus3ReturnsNull() {
        when(sheetMapper.selectById(1L)).thenReturn(sheet(1L, 3));
        assertNull(service.getSheetById(1L));
    }

    @Test
    void getSheetByIdReturnsActive() {
        when(sheetMapper.selectById(1L)).thenReturn(sheet(1L, 1));
        assertEquals(1L, service.getSheetById(1L).getId());
    }

    // ---------- insertSheetWithoutHistory / insertChunkWithoutHistory ----------

    @Test
    void insertSheetWithoutHistoryDefaultsStatusAndChunkCount() {
        ExcelSheet s = sheet(null, null);
        service.insertSheetWithoutHistory(s);
        assertEquals(1, s.getStatus());
        assertEquals(0, s.getChunkCount());
        verify(sheetMapper).insert(s);
    }

    @Test
    void insertChunkWithoutHistoryDefaultsNullCelldata() {
        ExcelSheetChunk c = service.insertChunkWithoutHistory(1L, 1L, 0, 0, 999, null);
        assertEquals("[]", c.getCelldataJson());
        assertEquals(0, c.getChunkIndex());
        assertEquals(0, c.getRowStart());
        assertEquals(999, c.getRowEnd());
        verify(chunkMapper).insert(c);
    }

    // ---------- listSheetsByDocumentId ----------

    @Test
    void listSheetsSuccess() {
        when(sheetMapper.selectList(any())).thenReturn(List.of(sheet(1L, 1), sheet(2L, 1)));
        assertEquals(2, service.listSheetsByDocumentId(1L).size());
    }

    @Test
    void listSheetsFailureWrapsParseError() {
        when(sheetMapper.selectList(any())).thenThrow(new RuntimeException("db down"));
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.listSheetsByDocumentId(1L));
        assertEquals(ErrorCodes.EXCEL_PARSE_ERROR, ex.getCode());
    }

    // ---------- listChunksBySheetId（delta 合并 + 分块缓存） ----------

    @Test
    void listChunksMergesDeltasAndCachesRawChunks() {
        when(sheetMapper.selectById(1L)).thenReturn(sheet(1L, 1));
        ExcelSheetChunk base = chunk(1L, 0, "[{\"r\":0,\"c\":0,\"v\":{\"v\":1}}]");
        when(chunkMapper.selectList(any())).thenReturn(new ArrayList<>(List.of(base)));
        when(deltaMapper.selectList(any())).thenReturn(new ArrayList<>(List.of(delta(0, 0, "{\"v\":9}"))));
        // 用真实缓存实例验证「缓存存原始值、返回值是合并值」的快照语义（mock 无法反映写盘时序）
        RedisChunkCacheService realCache = new RedisChunkCacheService();
        ExcelSheetService svc = new ExcelSheetService(sheetMapper, chunkMapper, deltaMapper,
                documentService, realCache, new ExcelDocumentLockService());

        List<ExcelSheetChunk> result = svc.listChunksBySheetId(1L);

        @SuppressWarnings("unchecked")
        Map<String, Object> mergedCell = (Map<String, Object>) com.argus.rag.common.util.JsonCompat.parseArray(
                result.get(0).getCelldataJson()).get(0);
        @SuppressWarnings("unchecked")
        Map<String, Object> vObj = (Map<String, Object>) mergedCell.get("v");
        assertEquals(9, vObj.get("v"));
        // P1-05：缓存里是「原始 celldata」快照（合并前），保证命中缓存时 delta 仍以 DB 为准
        List<ExcelSheetChunk> cached = realCache.getSheetChunks(1L, 1L);
        assertNotNull(cached);
        assertTrue(cached.get(0).getCelldataJson().contains("\"v\":1"),
                "缓存应保存合并前的原始值，实际: " + cached.get(0).getCelldataJson());
    }

    @Test
    @DisplayName("读路径命中分块缓存时不再查询 chunk 表（P1-05 真实使用缓存）")
    void listChunksServesFromCacheWithoutChunkQuery() {
        when(sheetMapper.selectById(1L)).thenReturn(sheet(1L, 1));
        when(cacheService.getSheetChunks(1L, 1L))
                .thenReturn(new ArrayList<>(List.of(chunk(1L, 0, "[{\"r\":0,\"c\":0,\"v\":{\"v\":7}}]"))));
        when(deltaMapper.selectList(any())).thenReturn(new ArrayList<>());

        List<ExcelSheetChunk> result = service.listChunksBySheetId(1L);

        assertEquals(1, result.size());
        verify(chunkMapper, never()).selectList(any());
    }

    // ---------- loadCellSnapshot ----------

    @Test
    void loadCellSnapshotMissingSheetReturnsNull() {
        when(sheetMapper.selectById(1L)).thenReturn(null);
        assertNull(service.loadCellSnapshot(1L, 1L, 0, 0));
    }

    @Test
    void loadCellSnapshotMissingChunkReturnsNull() {
        when(sheetMapper.selectById(1L)).thenReturn(sheet(1L, 1));
        when(chunkMapper.selectOne(any())).thenReturn(null);
        assertNull(service.loadCellSnapshot(1L, 1L, 0, 0));
    }

    @Test
    void loadCellSnapshotReturnsMatchingCell() {
        when(sheetMapper.selectById(1L)).thenReturn(sheet(1L, 1));
        when(chunkMapper.selectOne(any())).thenReturn(
                chunk(1L, 0, "[{\"r\":1,\"c\":2,\"v\":{\"v\":\"文本\"}}]"));
        when(deltaMapper.selectList(any())).thenReturn(List.of());

        @SuppressWarnings("unchecked")
        Map<String, Object> cell = (Map<String, Object>) service.loadCellSnapshot(1L, 1L, 1, 2);
        assertEquals(2, cell.get("c"));
        @SuppressWarnings("unchecked")
        Map<String, Object> vObj = (Map<String, Object>) cell.get("v");
        assertEquals("文本", vObj.get("v"));
    }

    // ---------- batchUpdateCells ----------

    @Test
    void batchUpdateCreatesNewChunkAndBumpsCount() {
        Map<String, Object> update = update(1, 5, 0); // v=1, r=5, c=0
        when(chunkMapper.selectOne(any())).thenReturn(null); // 目标块不存在 → new
        ExcelSheet existing = sheet(1L, 1);
        existing.setChunkCount(0);
        when(sheetMapper.selectById(any())).thenReturn(existing);
        when(deltaMapper.selectList(any())).thenReturn(List.of());
        when(deltaMapper.selectCount(any())).thenReturn(0L);

        service.batchUpdateCells(1L, List.of(update));

        ArgumentCaptor<ExcelSheetChunk> chunkCap = ArgumentCaptor.forClass(ExcelSheetChunk.class);
        verify(chunkMapper).insert(chunkCap.capture());
        ExcelSheetChunk created = chunkCap.getValue();
        assertEquals(0, created.getRowStart());
        assertEquals(999, created.getRowEnd());
        assertEquals("[]", created.getCelldataJson());

        ArgumentCaptor<ExcelSheet> sheetCap = ArgumentCaptor.forClass(ExcelSheet.class);
        verify(sheetMapper).updateById(sheetCap.capture());
        assertEquals(1, sheetCap.getValue().getChunkCount());

        ArgumentCaptor<ExcelSheetChunkDelta> deltaCap =
                ArgumentCaptor.forClass(ExcelSheetChunkDelta.class);
        verify(deltaMapper).insert(deltaCap.capture());
        assertEquals(5, deltaCap.getValue().getCellRow());
        assertEquals(0, deltaCap.getValue().getCellCol());

        verify(cacheService).invalidateSheet(1L, 1L);
    }

    @Test
    void batchUpdateReusesExistingDeltaCell() {
        Map<String, Object> update = update(7, 5, 0); // v=7, r=5, c=0
        when(chunkMapper.selectOne(any())).thenReturn(chunk(1L, 0, "[]"));
        when(sheetMapper.selectById(any())).thenReturn(sheet(1L, null));
        when(deltaMapper.selectList(any())).thenReturn(new ArrayList<>(List.of(delta(5, 0, "{\"v\":1}"))));
        when(deltaMapper.selectCount(any())).thenReturn(0L);

        service.batchUpdateCells(1L, List.of(update));

        ArgumentCaptor<ExcelSheetChunkDelta> cap =
                ArgumentCaptor.forClass(ExcelSheetChunkDelta.class);
        verify(deltaMapper).updateById(cap.capture());
        assertEquals("{\"v\":7}", cap.getValue().getCellValue());
        verify(deltaMapper, never()).insert(any(ExcelSheetChunkDelta.class));
    }

    // ---------- hardDeleteByDocumentId / deleteByDocumentId ----------

    @Test
    void hardDeleteCleansChunksAndSoftDeletesSheets() {
        service.hardDeleteByDocumentId(1L);
        verify(chunkMapper).delete(any(Wrapper.class));
        verify(deltaMapper).delete(any(Wrapper.class));
        ArgumentCaptor<ExcelSheet> cap = ArgumentCaptor.forClass(ExcelSheet.class);
        verify(sheetMapper).update(cap.capture(), any());
        assertEquals(3, cap.getValue().getStatus());
    }

    @Test
    void deleteByDocumentIdSoftDeletesAndCleans() {
        service.deleteByDocumentId(1L);
        verify(sheetMapper).update(any(ExcelSheet.class), any());
        verify(chunkMapper).delete(any(Wrapper.class));
        verify(deltaMapper).delete(any(Wrapper.class));
    }

    // ---------- replaceWorkbook（重建） ----------

    private Map<String, Object> workbookSheet() {
        Map<String, Object> raw = new HashMap<>();
        raw.put("name", "Sheet1");
        raw.put("celldata", List.of(cell(0, 0, "A1"), cell(0, 1, "B1")));
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("merge", new LinkedHashMap<>());
        config.put("columnlen", new LinkedHashMap<>());
        config.put("rowlen", new LinkedHashMap<>());
        raw.put("config", config);
        raw.put("row", 1);
        raw.put("column", 2);
        raw.put("status", 1);
        return raw;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> cell(long r, long c, Object v) {
        Map<String, Object> cell = new LinkedHashMap<>();
        cell.put("r", r);
        cell.put("c", c);
        Map<String, Object> vObj = new LinkedHashMap<>();
        vObj.put("v", v);
        cell.put("v", vObj);
        return cell;
    }

    @Test
    void replaceWorkbookRejectsEmpty() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.replaceWorkbook(1L, List.of()));
        assertEquals(ErrorCodes.EXCEL_PARSE_ERROR, ex.getCode());
    }

    @Test
    void replaceWorkbookRebuildsSheetsAndChunks() {
        when(sheetMapper.insert(any(ExcelSheet.class))).thenAnswer(inv -> {
            ExcelSheet s = inv.getArgument(0);
            s.setId(10L + s.getSheetIndex());
            return 0;
        });

        Map<Integer, Long> mapping = service.replaceWorkbook(1L, List.of(workbookSheet()));

        verify(chunkMapper).delete(any(Wrapper.class));
        verify(deltaMapper).delete(any(Wrapper.class));
        // 新 sheet 落库
        ArgumentCaptor<ExcelSheet> sheetCap = ArgumentCaptor.forClass(ExcelSheet.class);
        verify(sheetMapper).insert(sheetCap.capture());
        ExcelSheet inserted = sheetCap.getValue();
        assertEquals("Sheet1", inserted.getSheetName());
        assertEquals(1, inserted.getTotalRows());
        assertEquals(2, inserted.getTotalCols());
        assertEquals(1, inserted.getStatus());
        // 分块落库
        ArgumentCaptor<ExcelSheetChunk> chunkCap = ArgumentCaptor.forClass(ExcelSheetChunk.class);
        verify(chunkMapper).insert(chunkCap.capture());
        assertEquals(0, chunkCap.getValue().getChunkIndex());
        assertEquals(10L, mapping.get(0));
        // chunkCount 回写
        ArgumentCaptor<ExcelSheet> updateCap = ArgumentCaptor.forClass(ExcelSheet.class);
        verify(sheetMapper).updateById(updateCap.capture());
        assertEquals(1, updateCap.getValue().getChunkCount());
        // meta 更新
        verify(documentService).updateSheetMeta(1L, 1, "[\"Sheet1\"]");
    }

    @Test
    void replaceWorkbookRejectsAllNullSheets() {
        when(sheetMapper.insert(any(ExcelSheet.class))).thenReturn(0);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.replaceWorkbook(1L, new ArrayList<>(java.util.Arrays.asList((Map<String, Object>) null))));
        assertEquals(ErrorCodes.EXCEL_PARSE_ERROR, ex.getCode());
    }
}