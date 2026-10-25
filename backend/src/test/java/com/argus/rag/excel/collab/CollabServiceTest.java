package com.argus.rag.excel.collab;

import com.argus.rag.common.util.JsonCompat;
import com.argus.rag.excel.audit.OperationLogService;
import com.argus.rag.excel.entity.ExcelCollabLog;
import com.argus.rag.excel.entity.ExcelCollabRoom;
import com.argus.rag.excel.mapper.ExcelCollabLogMapper;
import com.argus.rag.excel.mapper.ExcelCollabRoomMapper;
import com.argus.rag.excel.model.dto.CollabMessage;
import com.argus.rag.excel.service.ExcelSheetService;
import com.argus.rag.excel.service.SnapshotService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link CollabService} 实时协作契约（mock 5 协作方：sheet/operationLog/snapshot service + 2 collab mapper，
 * 纯 JVM/零 DB；房间查询用字符串 QueryWrapper 避开 MP 运行时缓存）：
 * - LWW：同格更晚或同时间戳编辑被晚前者覆盖（ts<=lastTs 拒绝）；更高时间戳接受并更新缓存；
 * - 持久化失败回滚时间戳（下次同 ts 重试可再进）；SQL 0 行与 open 操作失败均拒并广播拒绝原因；
 * - 成功→ 记录操作日志（含变更前值 JSON）、快照触达、batchUpdateCells、协同日志入库、广播包内容齐全，
 *   携带 senderSessionId；
 * - operationLog/collabLog 写入失败不影响主流程；joinRoom 无占用 insert / 有则 updateById；leaveRoom 删记录。
 */
@DisplayName("实时协作（CollabService）")
class CollabServiceTest {

    private ExcelSheetService sheetService;
    private OperationLogService operationLogService;
    private SnapshotService snapshotService;
    private ExcelCollabRoomMapper roomMapper;
    private ExcelCollabLogMapper logMapper;
    private com.argus.rag.excel.service.ExcelDocumentService documentService;
    private CollabService svc;

    @BeforeEach
    void setUp() {
        sheetService = mock(ExcelSheetService.class);
        operationLogService = mock(OperationLogService.class);
        snapshotService = mock(SnapshotService.class);
        roomMapper = mock(ExcelCollabRoomMapper.class);
        logMapper = mock(ExcelCollabLogMapper.class);
        documentService = mock(com.argus.rag.excel.service.ExcelDocumentService.class);
        svc = new CollabService(sheetService, documentService, operationLogService, snapshotService, roomMapper, logMapper,
                org.mockito.Mockito.mock(com.argus.rag.excel.service.ExcelCollabOutboxService.class),
                new com.fasterxml.jackson.databind.ObjectMapper());
    }

    private static CollabMessage msg(int r, int c, long ts, Long sheetId, String type) {
        Map<String, Object> v = new HashMap<>();
        v.put("v", "val");
        return new CollabMessage(
                type, 10L, sheetId, r, c, v,
                7L, "U7", ts, 0L, "REQ-1", null);
    }

    private static String cellKey(int r, int c) {
        return "10_" + (20L) + "_" + r + "_" + c;
    }

    private static Map<String, Object> beforeCell(Object v) {
        if (v == null) return null;
        Map<String, Object> m = new HashMap<>();
        m.put("v", v);
        return m;
    }

    // ---------- LWW 冲突解决 ----------

    @Test
    void handleEditAcceptsHigherTimestamp() {
        when(sheetService.loadCellSnapshot(anyLong(), anyLong(), anyInt(), anyInt())).thenReturn(null);
        CollabService.HandleEditOutcome out = svc.handleEdit(msg(1, 2, 100, 20L, "CELL_EDIT"), 7L, "DEV7", "S1");
        assertTrue(out.accepted());
        assertNotNull(out.broadcast());
    }

    @Test
    void handleEditRejectsEqualOrOlderTimestamp() {
        when(sheetService.loadCellSnapshot(anyLong(), anyLong(), anyInt(), anyInt())).thenReturn(null);
        svc.handleEdit(msg(1, 2, 100, 20L, "CELL_EDIT"), 7L, "DEV7", "S1");
        // 同时间戳 → LWW 拒绝
        CollabService.HandleEditOutcome same = svc.handleEdit(msg(1, 2, 100, 20L, "CELL_EDIT"), 8L, "DEV8", "S2");
        assertFalse(same.accepted());
        assertEquals("LWW_REJECTED", same.rejectionReason());
        assertEquals("您的编辑已被另一个用户的更新覆盖", same.message());
        verify(sheetService, times(1)).batchUpdateCells(anyLong(), any());
        // 更旧时间戳 → 仍拒绝
        CollabService.HandleEditOutcome older = svc.handleEdit(msg(1, 2, 99, 20L, "CELL_EDIT"), 8L, "DEV8", "S2");
        assertFalse(older.accepted());
        verify(sheetService, times(1)).batchUpdateCells(anyLong(), any());
    }

    @Test
    void handleEditAcceptsHigherTimestampAfterReject() {
        when(sheetService.loadCellSnapshot(anyLong(), anyLong(), anyInt(), anyInt())).thenReturn(null);
        svc.handleEdit(msg(3, 5, 200, 20L, "CELL_EDIT"), 7L, "DEV7", "S1");
        svc.handleEdit(msg(3, 5, 150, 20L, "CELL_EDIT"), 8L, "DEV8", "S2");   // 旧 → 拒
        CollabService.HandleEditOutcome higher = svc.handleEdit(msg(3, 5, 300, 20L, "CELL_EDIT"), 8L, "DEV8", "S2");
        assertTrue(higher.accepted());
        verify(sheetService, times(2)).batchUpdateCells(anyLong(), anyList());
    }

    // ---------- 持久化 / 快照 / 协同日志 ----------

    @Test
    void acceptedEditPersistsSnapshotAndCollabLog() {
        when(sheetService.loadCellSnapshot(anyLong(), anyLong(), anyInt(), anyInt()))
                .thenReturn(beforeCell("OLD"));
        svc.handleEdit(msg(1, 2, 100, 20L, "CELL_EDIT"), 7L, "DEV7", "S1");
        verify(snapshotService).touch(10L);
        // 先存后广播：batchUpdateCells 在 collab 日志前调用
        verify(sheetService).batchUpdateCells(anyLong(), anyList());
        ArgumentCaptor<ExcelCollabLog> logCap = ArgumentCaptor.forClass(ExcelCollabLog.class);
        verify(logMapper).insert(logCap.capture());
        ExcelCollabLog l = logCap.getValue();
        assertEquals(10L, l.getDocumentId());
        assertEquals(20L, l.getSheetId());
        assertEquals(1, l.getRowNo());
        assertEquals(2, l.getColNo());
        assertEquals(7L, l.getActorId());
        assertEquals("DEV7", l.getActorName());
        assertEquals("S1", l.getRequestId());
        assertEquals(1, l.getMerged());
        assertEquals(0, l.getConflict());
        assertEquals(JsonCompat.toJSONString(msg(1, 2, 100, 20L, "CELL_EDIT").v()), l.getValueText());
    }

    @Test
    void operationLogFailureDoesNotBlockEdit() {
        when(sheetService.loadCellSnapshot(anyLong(), anyLong(), anyInt(), anyInt())).thenReturn(null);
        doThrow(new RuntimeException("log down")).when(operationLogService)
                .recordCellEdit(any(CollabMessage.class), anyString());
        CollabService.HandleEditOutcome out = svc.handleEdit(msg(4, 4, 100, 20L, "CELL_EDIT"), 7L, "DEV7", "S1");
        assertTrue(out.accepted());   // 操作日志失败不影响编辑
        verify(snapshotService).touch(10L);
    }

    @Test
    void persistFailureRollbackLwwAndRejectSaveFailed() {
        when(sheetService.loadCellSnapshot(anyLong(), anyLong(), anyInt(), anyInt())).thenReturn(null);
        doThrow(new RuntimeException("db down")).when(sheetService)
                .batchUpdateCells(anyLong(), anyList());
        CollabService.HandleEditOutcome out = svc.handleEdit(msg(1, 6, 100, 20L, "CELL_EDIT"), 7L, "DEV7", "S1");
        assertFalse(out.accepted());
        assertEquals("SAVE_FAILED", out.rejectionReason());
        // 时间戳已回滚：重试同格相同时间戳再次进入（不被 LWW 拦截）
        CollabService.HandleEditOutcome retry = svc.handleEdit(msg(1, 6, 100, 20L, "CELL_EDIT"), 7L, "DEV7", "S1");
        assertFalse(retry.accepted());   // 仍是 SAVE_FAILED（持久化继续失败），但未被 LWW 拒绝
        assertEquals("SAVE_FAILED", retry.rejectionReason());
        verify(sheetService, times(2)).batchUpdateCells(anyLong(), anyList());
    }

    @Test
    void collabLogInsertFailureDoesNotBlockBroadcast() {
        when(sheetService.loadCellSnapshot(anyLong(), anyLong(), anyInt(), anyInt())).thenReturn(null);
        doThrow(new RuntimeException("collab down")).when(logMapper).insert(any(ExcelCollabLog.class));
        CollabService.HandleEditOutcome out = svc.handleEdit(msg(1, 2, 100, 20L, "CELL_EDIT"), 7L, "DEV7", "S1");
        assertTrue(out.accepted());
        verify(snapshotService).touch(10L);
    }

    @Test
    void broadcastCarriesDemuxFields() {
        when(sheetService.loadCellSnapshot(anyLong(), anyLong(), anyInt(), anyInt())).thenReturn(null);
        CollabService.HandleEditOutcome out = svc.handleEdit(msg(2, 3, 55, 20L, "CELL_EDIT"), 9L, "DEV9", "SESS-X");
        Map<String, Object> b = out.broadcast();
        assertEquals("CELL_EDIT", b.get("type"));
        assertEquals(10L, b.get("documentId"));
        assertEquals(20L, b.get("sheetId"));
        assertEquals(2, b.get("r"));
        assertEquals(3, b.get("c"));
        assertNotNull(b.get("v"));
        assertEquals(9L, b.get("userId"));
        assertEquals("DEV9", b.get("displayName"));
        assertEquals(55L, b.get("timestamp"));
        assertEquals("SESS-X", b.get("senderSessionId"));
        assertEquals("REQ-1", b.get("requestId"));
    }

    @Test
    void broadcastIgnoresClientForgedIdentity() {
        // 客户端把 userId/displayName 伪造成管理员，广播与审计必须仍用会话身份。
        when(sheetService.loadCellSnapshot(anyLong(), anyLong(), anyInt(), anyInt())).thenReturn(null);
        CollabMessage forged = new CollabMessage(
                "CELL_EDIT", 10L, 20L, 8, 8, new HashMap<>(Map.of("v", "x")),
                999L, "ADMIN-FORGED", 400L, 0L, "REQ-F", null);
        CollabService.HandleEditOutcome out = svc.handleEdit(forged, 7L, "DEV7", "S1");
        assertTrue(out.accepted());
        assertEquals(7L, out.broadcast().get("userId"), "广播身份必须是会话用户，不得采信负载");
        assertEquals("DEV7", out.broadcast().get("displayName"));
        verify(operationLogService).recordCellEdit(any(CollabMessage.class), anyString());
        ArgumentCaptor<ExcelCollabLog> logCap = ArgumentCaptor.forClass(ExcelCollabLog.class);
        verify(logMapper).insert(logCap.capture());
        assertEquals(7L, logCap.getValue().getActorId(), "协同日志 actor 必须是会话用户");
    }

    // ---------- join / leave room ----------

    @Test
    void joinRoomInsertsWhenNoExistingRecord() {
        when(roomMapper.selectOne(any())).thenReturn(null);
        svc.joinRoom(10L, 7L, "DEV7", "SESS1", true);
        ArgumentCaptor<ExcelCollabRoom> cap = ArgumentCaptor.forClass(ExcelCollabRoom.class);
        verify(roomMapper).insert(cap.capture());
        ExcelCollabRoom r = cap.getValue();
        assertEquals(10L, r.getDocumentId());
        assertEquals(7L, r.getActorId());
        assertEquals("DEV7", r.getActorName());
        assertEquals("SESS1", r.getSessionId());
        assertEquals(1, r.getCanEdit());
        verify(roomMapper, never()).updateById(any(ExcelCollabRoom.class));
    }

    @Test
    void joinRoomIsViewerOnlyWhenCanEditFalse() {
        when(roomMapper.selectOne(any())).thenReturn(null);
        svc.joinRoom(10L, 7L, "DEV7", "SESS1", false);
        ArgumentCaptor<ExcelCollabRoom> cap = ArgumentCaptor.forClass(ExcelCollabRoom.class);
        verify(roomMapper).insert(cap.capture());
        assertEquals(0, cap.getValue().getCanEdit(), "只读查看者不应被记为可编辑");
    }

    @Test
    void joinRoomRefreshesExistingRecord() {
        ExcelCollabRoom existing = new ExcelCollabRoom();
        existing.setId(9L);
        when(roomMapper.selectOne(any())).thenReturn(existing);
        svc.joinRoom(1L, 2L, "DEV2", "SESS2", true);
        ArgumentCaptor<ExcelCollabRoom> cap = ArgumentCaptor.forClass(ExcelCollabRoom.class);
        verify(roomMapper).updateById(cap.capture());
        assertEquals(9L, cap.getValue().getId());
        assertEquals(2L, cap.getValue().getActorId());
        verify(roomMapper, never()).insert(any(ExcelCollabRoom.class));
    }

    @Test
    void joinRoomSwallowsPersistenceFailure() {
        doThrow(new RuntimeException("down")).when(roomMapper).insert(any(ExcelCollabRoom.class));
        when(roomMapper.selectOne(any())).thenReturn(null);
        svc.joinRoom(1L, 1L, "D", "S", false);
        // 不抛异常即通过
    }

    @Test
    void leaveRoomDeletesBySessionWrapper() {
        svc.leaveRoom("SESS1");
        verify(roomMapper).delete(any(QueryWrapper.class));
    }

    // ---------- 任务 8：revision 乐观锁 ----------

    @Test
    @org.junit.jupiter.api.DisplayName("baseRevision 落后于文档当前版本时返回冲突且不写入")
    void staleBaseRevisionReturnsConflictWithoutWrite() {
        when(documentService.optionalById(10L)).thenReturn(docWithVersion(5L));
        CollabMessage m = msgWithBaseRevision(1, 2, 100, 20L, 4L);

        CollabService.HandleEditOutcome out = svc.handleEdit(m, 7L, "DEV7", "S1");

        assertFalse(out.accepted());
        assertEquals("REVISION_CONFLICT", out.rejectionReason());
        assertEquals(5L, out.serverRevision());
        verify(sheetService, never()).batchUpdateCells(anyLong(), anyList());
    }

    @Test
    @org.junit.jupiter.api.DisplayName("baseRevision 一致时接受编辑，写入后推进版本并广播新 revision")
    void matchingBaseRevisionBumpsVersionAndBroadcasts() {
        when(documentService.optionalById(10L)).thenReturn(docWithVersion(5L));
        when(documentService.bumpVersion(10L)).thenReturn(6L);
        when(sheetService.loadCellSnapshot(anyLong(), anyLong(), anyInt(), anyInt())).thenReturn(null);
        CollabMessage m = msgWithBaseRevision(1, 2, 100, 20L, 5L);

        CollabService.HandleEditOutcome out = svc.handleEdit(m, 7L, "DEV7", "S1");

        assertTrue(out.accepted());
        assertEquals(6L, out.broadcast().get("revision"));
        verify(documentService).bumpVersion(10L);
    }

    @Test
    @org.junit.jupiter.api.DisplayName("光标事件不产生操作日志与协同日志")
    void cursorEventsSkipAuditLogs() {
        when(documentService.optionalById(10L)).thenReturn(docWithVersion(5L));
        CollabMessage cursor = msgWithBaseRevision(0, 0, 100, 20L, 5L);
        cursor = new CollabMessage("CURSOR", cursor.documentId(), cursor.sheetId(),
                cursor.r(), cursor.c(), cursor.v(), cursor.userId(), cursor.displayName(),
                cursor.timestamp(), cursor.serverTimestamp(), cursor.requestId(), cursor.baseRevision());

        CollabService.HandleEditOutcome out = svc.handleEdit(cursor, 7L, "DEV7", "S1");

        assertTrue(out.accepted());
        verify(operationLogService, never()).recordCellEdit(any(), anyString());
        verify(logMapper, never()).insert(any(ExcelCollabLog.class));
        verify(sheetService, never()).batchUpdateCells(anyLong(), anyList());
        verify(documentService, never()).bumpVersion(anyLong());
    }

    private com.argus.rag.excel.entity.ExcelDocument docWithVersion(Long version) {
        com.argus.rag.excel.entity.ExcelDocument doc = new com.argus.rag.excel.entity.ExcelDocument();
        doc.setId(10L);
        doc.setVersion(version);
        return doc;
    }

    private CollabMessage msgWithBaseRevision(int r, int c, long ts, Long sheetId, Long baseRevision) {
        Map<String, Object> v = new HashMap<>();
        v.put("v", "val");
        return new CollabMessage("CELL_EDIT", 10L, sheetId, r, c, v,
                7L, "U7", ts, 0L, "REQ-1", baseRevision);
    }
}
