package com.argus.rag.excel.audit;

import com.argus.rag.common.util.JsonCompat;
import com.argus.rag.excel.mapper.OperationLogMapper;
import com.argus.rag.excel.model.dto.CollabMessage;
import com.argus.rag.excel.service.ExcelSheetService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link OperationLogService} 操作日志/撤销重做契约（mock mapper + {@code ExcelSheetService}，纯 JVM/零 DB；
 * 查询用字符串 QueryWrapper 并经 {@code getCustomSqlSegment()} 断 SQL 片段，避开 MP 运行时缓存）：
 * - recordCellEdit / recordBatchEdit / recordBatchEditAndApply：落库字段、batchId 分组、空 updates 短路；
 * - snapshotCellValue 读前值失败兜底 "null"（不阻断入库）；
 * - getRecentLogs 按 document_id 过滤 + 按 id 降序 + limit 500；
 * - undoLast：无最新→null；CELL_EDIT 单行 swap UNDO；BATCH_EDIT 按 batchId 取全部逐行 UNDO；
 * - redoLast：无 UNDO→null；有→swap 写回 + REDO 行。
 */
@DisplayName("操作日志/撤销（OperationLogService）")
class OperationLogServiceTest {

    private OperationLogMapper mapper;
    private ExcelSheetService sheetService;
    private OperationLogService svc;

    @BeforeEach
    void setUp() {
        mapper = mock(OperationLogMapper.class);
        sheetService = mock(ExcelSheetService.class);
        svc = new OperationLogService(mapper, sheetService, new com.fasterxml.jackson.databind.ObjectMapper());
    }

    private static CollabMessage msg(int r, int c, Object v, Long userId) {
        Map<String, Object> vm = new HashMap<>();
        vm.put("v", v);
        return new CollabMessage("CELL_EDIT", 10L, 20L, r, c, vm,
                userId, "DEV" + userId, 100L, 0L, "REQ-1", null);
    }

    private static void assertEqualsCell(OperationLog e, Long doc, Long sheet, int r, int c) {
        assertEquals(doc, e.getDocumentId());
        assertEquals(sheet, e.getSheetId());
        assertEquals(r, e.getRowIndex());
        assertEquals(c, e.getColIndex());
    }

    private static Map<String, Object> beforeCell(Object v) {
        if (v == null) return null;
        Map<String, Object> m = new HashMap<>();
        m.put("v", v);
        return m;
    }

    // ---------- recordCellEdit ----------

    @Test
    void recordCellEditPersistsCellEditLog() {
        CollabMessage m = msg(3, 4, "NEW", 7L);
        OperationLog out = svc.recordCellEdit(m, "\"OLD\"");
        ArgumentCaptor<OperationLog> cap = ArgumentCaptor.forClass(OperationLog.class);
        verify(mapper).insert(cap.capture());
        OperationLog e = cap.getValue();
        assertEqualsCell(e, 10L, 20L, 3, 4);
        assertEquals("CELL_EDIT", e.getOperationType());
        assertEquals("\"OLD\"", e.getOldValue());
        assertEquals(JsonCompat.toJSONString(Map.of("v", "NEW")), e.getNewValue());
        assertEquals(7L, e.getUserId());
        assertEquals("DEV7", e.getUserDisplayName());
        assertEquals(out, e);
    }

    // ---------- recordBatchEdit ----------

    @Test
    void recordBatchEditGroupsAllRowsUnderOneBatchId() {
        List<CollabMessage> msgs = List.of(msg(1, 1, "A", 7L), msg(2, 2, "B", 8L));
        String batchId = svc.recordBatchEdit(msgs, 9L, "批量");
        assertNotNull(batchId);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<OperationLog> cap = ArgumentCaptor.forClass(OperationLog.class);
        verify(mapper, times(2)).insert(cap.capture());
        List<OperationLog> all = cap.getAllValues();
        assertEquals(2, all.size());
        assertEquals(batchId, all.get(0).getBatchId());
        assertEquals(batchId, all.get(1).getBatchId());
        for (OperationLog e : all) {
            assertEquals("BATCH_EDIT", e.getOperationType());
            assertEquals(9L, e.getUserId());           // 取参数，非 msg
            assertEquals("批量", e.getUserDisplayName());
        }
        assertEquals(JsonCompat.toJSONString(Map.of("v", "A")), all.get(0).getNewValue());
        assertEquals(JsonCompat.toJSONString(Map.of("v", "B")), all.get(1).getNewValue());
    }

    // ---------- recordBatchEditAndApply ----------

    @Test
    void recordBatchEditAndApplyEmptyReturnsNull() {
        assertNull(svc.recordBatchEditAndApply(10L, null, 1L, "x"));
        assertNull(svc.recordBatchEditAndApply(10L, List.of(), 1L, "x"));
        verify(mapper, never()).insert(any(OperationLog.class));
        verify(sheetService, never()).batchUpdateCells(anyLong(), any());
    }

    @Test
    void recordBatchEditAndApplyPersistsLogsThenApplies() {
        Map<String, Object> u1 = new HashMap<>();
        u1.put("sheetId", 20L);
        u1.put("r", 1);
        u1.put("c", 2);
        u1.put("v", "X");
        List<Map<String, Object>> updates = List.of(u1);

        String batchId = svc.recordBatchEditAndApply(10L, updates, 7L, "DEV7");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<OperationLog> cap = ArgumentCaptor.forClass(OperationLog.class);
        verify(mapper).insert(cap.capture());
        OperationLog e = cap.getValue();
        assertEqualsCell(e, 10L, 20L, 1, 2);
        assertEquals("BATCH_EDIT", e.getOperationType());
        assertEquals("null", e.getOldValue());             // 快照为空兜底
        assertEquals(JsonCompat.toJSONString("X"), e.getNewValue());
        assertEquals(7L, e.getUserId());
        assertEquals("DEV7", e.getUserDisplayName());
        assertNotNull(e.getBatchId());
        assertEquals(batchId, e.getBatchId());
        verify(sheetService).batchUpdateCells(10L, updates);
    }

    @Test
    void recordBatchEditAndApplyUsesSnapshotOldValue() {
        when(sheetService.loadCellSnapshot(10L, 20L, 1, 2)).thenReturn(beforeCell("OLD"));
        Map<String, Object> u1 = new HashMap<>();
        u1.put("sheetId", 20L);
        u1.put("r", 1);
        u1.put("c", 2);
        u1.put("v", "X");
        svc.recordBatchEditAndApply(10L, List.of(u1), 7L, "DEV7");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<OperationLog> cap = ArgumentCaptor.forClass(OperationLog.class);
        verify(mapper).insert(cap.capture());
        assertEquals(JsonCompat.toJSONString("OLD"), cap.getValue().getOldValue());
    }

    @Test
    void snapshotReadFailureFallsBackToNullString() {
        doThrow(new RuntimeException("sheet down")).when(sheetService)
                .loadCellSnapshot(anyLong(), anyLong(), anyInt(), anyInt());
        Map<String, Object> u1 = new HashMap<>();
        u1.put("sheetId", 20L);
        u1.put("r", 1);
        u1.put("c", 2);
        u1.put("v", "X");
        String batchId = svc.recordBatchEditAndApply(10L, List.of(u1), 7L, "DEV7");
        assertNotNull(batchId);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<OperationLog> cap = ArgumentCaptor.forClass(OperationLog.class);
        verify(mapper).insert(cap.capture());
        assertEquals("null", cap.getValue().getOldValue());
        verify(sheetService).batchUpdateCells(10L, List.of(u1));
    }

    // ---------- getRecentLogs ----------

    @Test
    void getRecentLogsFiltersByDocumentAndOrdersDesc() {
        when(mapper.selectList(any())).thenReturn(List.of());
        svc.getRecentLogs(77L);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<QueryWrapper<OperationLog>> cap = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(mapper).selectList(cap.capture());
        String sql = cap.getValue().getCustomSqlSegment();
        assertTrue(sql.contains("document_id"), sql);
        assertTrue(sql.toLowerCase().contains("order by id desc"), sql);
        assertTrue(sql.contains("500"), sql);
    }

    // ---------- undoLast ----------

    @Test
    void undoLastNoLatestReturnsNull() {
        when(mapper.selectOne(any())).thenReturn(null);
        assertNull(svc.undoLast(10L, 7L));
        verify(sheetService, never()).applyHistoryBatch(eq(10L), any());
    }

    private static OperationLog logRow(Long id, Integer r, Integer c, String type, String batchId,
                                       String oldV, String newV) {
        OperationLog o = new OperationLog();
        o.setId(id);
        o.setDocumentId(10L);
        o.setSheetId(20L);
        o.setRowIndex(r);
        o.setColIndex(c);
        o.setOperationType(type);
        o.setOldValue(oldV);
        o.setNewValue(newV);
        o.setUserId(7L);
        o.setUserDisplayName("DEV7");
        o.setBatchId(batchId);
        return o;
    }

    @Test
    void undoLastSingleCellSwapsValuesAndApplies() {
        OperationLog latest = logRow(5L, 1, 2, "CELL_EDIT", null, "\"OLD\"", "\"NEW\"");
        when(mapper.selectOne(any())).thenReturn(latest);
        OperationLog out = svc.undoLast(10L, 7L);
        assertEquals(latest, out);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<OperationLog> cap = ArgumentCaptor.forClass(OperationLog.class);
        verify(mapper, times(1)).insert(cap.capture());
        OperationLog undo = cap.getValue();
        assertEquals("UNDO", undo.getOperationType());
        assertEquals("\"NEW\"", undo.getOldValue());   // 撤销行的旧值=原新值
        assertEquals("\"OLD\"", undo.getNewValue());
        assertEquals(7L, undo.getUserId());
        assertEquals("DEV7", undo.getUserDisplayName());
        // 写回值为校验过的旧值（解析 JSON → 字符串 "OLD"）
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List> applyCap = ArgumentCaptor.forClass(List.class);
        verify(sheetService).applyHistoryBatch(eq(10L), applyCap.capture());
        assertEquals("OLD", ((Map<String, Object>) applyCap.getValue().get(0)).get("v"));
    }

    @Test
    void undoLastBatchEditUndoesAllRowsOfBatch() {
        OperationLog batchRow = logRow(5L, 1, 2, "BATCH_EDIT", "B-1", "\"OLD1\"", "\"NEW1\"");
        when(mapper.selectOne(any())).thenReturn(batchRow);
        when(mapper.selectList(any())).thenReturn(List.of(
                logRow(4L, 1, 2, "BATCH_EDIT", "B-1", "\"OLD1\"", "\"NEW1\""),
                logRow(6L, 9, 9, "BATCH_EDIT", "B-1", "\"OLD2\"", "\"NEW2\"")));
        svc.undoLast(10L, 7L);
        verify(mapper, times(2)).insert(any(OperationLog.class));   // 两条都补 UNDO
        verify(sheetService, times(2)).applyHistoryBatch(eq(10L), any());
    }

    // ---------- redoLast ----------

    @Test
    void redoLastNoUndoReturnsNull() {
        when(mapper.selectOne(any())).thenReturn(null);
        assertNull(svc.redoLast(10L, 7L));
        verify(sheetService, never()).applyHistoryBatch(eq(10L), any());
    }

    @Test
    void redoLastRestoresUndoRow() {
        OperationLog undo = logRow(7L, 1, 2, "UNDO", null, "\"NEW\"", "\"OLD\"");
        when(mapper.selectOne(any())).thenReturn(undo);
        OperationLog out = svc.redoLast(10L, 7L);
        assertEquals(undo, out);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<OperationLog> cap = ArgumentCaptor.forClass(OperationLog.class);
        verify(mapper).insert(cap.capture());
        OperationLog redo = cap.getValue();
        assertEquals("REDO", redo.getOperationType());
        assertEquals("\"OLD\"", redo.getOldValue());
        assertEquals("\"NEW\"", redo.getNewValue());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List> applyCap = ArgumentCaptor.forClass(List.class);
        verify(sheetService).applyHistoryBatch(eq(10L), applyCap.capture());
        assertEquals("NEW",
                ((Map<String, Object>) applyCap.getValue().get(0)).get("v"));
    }
}
