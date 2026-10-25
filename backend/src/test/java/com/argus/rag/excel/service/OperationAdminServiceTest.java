package com.argus.rag.excel.service;

import com.argus.rag.excel.entity.ExcelCollabRoom;
import com.argus.rag.excel.mapper.ExcelAuditLogMapper;
import com.argus.rag.excel.mapper.ExcelCollabLogMapper;
import com.argus.rag.excel.mapper.ExcelCollabRoomMapper;
import com.argus.rag.excel.mapper.ExcelDocumentMapper;
import com.argus.rag.excel.mapper.ExcelDocumentVersionMapper;
import com.argus.rag.excel.mapper.ExcelExportTaskMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link OperationAdminService} 运营后台查询契约（全 mock / 零 DB）：
 * - 概览 {@code summary}：7 键计数，status=1 计入 docCount、status=2 计入 recycleCount、其余全量；
 * - 分页：夹取 pageNum/pageSize 到 >=1、降序排序，空白过滤条件不产生 WHERE；
 * - 审计分页：username/action/targetType 条件 + create_time 区间；
 * - 协同分页：documentId / actor_name / merged / conflict；
 * - 房间分页：documentId / actor_name / ORDER BY update_time DESC；
 * - roomView：11 键值快照；kickRoom null 空操作 / 非空 deleteById；
 * - versionStats：versionCount 全量 + versionRows 过滤 cell_count IS NOT NULL。
 * 过滤断言提取构造后 QueryWrapper 的 {@link QueryWrapper#getCustomSqlSegment()} 锁列名与排序，
 * 计数类断言以 Long 匹配（MyBatis-Plus selectCount 返回 Long）。
 */
@DisplayName("运营中心后台查询（OperationAdminService）")
class OperationAdminServiceTest {

    private final ExcelAuditLogMapper auditLogMapper = mock(ExcelAuditLogMapper.class);
    private final ExcelCollabLogMapper collabLogMapper = mock(ExcelCollabLogMapper.class);
    private final ExcelCollabRoomMapper collabRoomMapper = mock(ExcelCollabRoomMapper.class);
    private final ExcelDocumentMapper documentMapper = mock(ExcelDocumentMapper.class);
    private final ExcelDocumentVersionMapper versionMapper = mock(ExcelDocumentVersionMapper.class);
    private final ExcelExportTaskMapper exportTaskMapper = mock(ExcelExportTaskMapper.class);

    private final OperationAdminService service = new OperationAdminService(
            auditLogMapper, collabLogMapper, collabRoomMapper,
            documentMapper, versionMapper, exportTaskMapper);

    private static String seg(QueryWrapper qw) {
        String s = qw.getCustomSqlSegment();
        return s == null ? "" : s;
    }

    // ---------- summary ----------

    @Test
    void summaryCountsAllSevenBuckets() {
        when(documentMapper.selectCount(any())).thenReturn(12L, 3L); // status=1→12, status=2→3
        when(versionMapper.selectCount(any())).thenReturn(40L);
        when(collabLogMapper.selectCount(any())).thenReturn(5L);
        when(collabRoomMapper.selectCount(any())).thenReturn(2L);
        when(exportTaskMapper.selectCount(any())).thenReturn(9L);
        when(auditLogMapper.selectCount(any())).thenReturn(77L);

        Map<String, Object> s = service.summary();

        assertEquals(7, s.size());
        assertEquals(12L, s.get("docCount"));
        assertEquals(3L, s.get("recycleCount"));
        assertEquals(40L, s.get("versionCount"));
        assertEquals(5L, s.get("collabLogCount"));
        assertEquals(2L, s.get("onlineRoomCount"));
        assertEquals(9L, s.get("exportTaskCount"));
        assertEquals(77L, s.get("auditLogCount"));

        // 两个 status 条件：一个值为 1，一个值为 2
        ArgumentCaptor<QueryWrapper> cap = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(documentMapper, times(2)).selectCount(cap.capture());
        String s0 = seg(cap.getAllValues().get(0));
        String s1 = seg(cap.getAllValues().get(1));
        assertTrue(s0.contains("status"), s0);
        assertTrue(s1.contains("status"), s1);
        assertTrue((s0.contains("status") && paramHas(cap.getAllValues().get(0), 1L))
                || paramHas(cap.getAllValues().get(0), 1));
        assertTrue(paramHas(cap.getAllValues().get(1), 2) || paramHas(cap.getAllValues().get(1), 2L));
    }

    private static boolean paramHas(QueryWrapper<?> qw, Object value) {
        Map<String, Object> p = qw.getParamNameValuePairs();
        return p.containsValue(value);
    }

    // ---------- pageAudit ----------

    @Test
    void pageAuditClampsPageAndSize() {
        Page<Object> expected = new Page<>(1, 1);
        when(auditLogMapper.selectPage(any(), any())).thenReturn(expected);
        assertSame(expected, service.pageAudit(-3, 0, null, null, null, null, null));
        ArgumentCaptor<Page> pageCap = ArgumentCaptor.forClass(Page.class);
        verify(auditLogMapper).selectPage(pageCap.capture(), any());
        assertEquals(1, pageCap.getValue().getCurrent());
        assertEquals(1, pageCap.getValue().getSize());
    }

    @Test
    void pageAuditNoFilterBareOrderByIdDesc() {
        when(auditLogMapper.selectPage(any(), any())).thenReturn(new Page<>(1, 2));
        service.pageAudit(1, 2, null, null, null, null, null);
        ArgumentCaptor<QueryWrapper> cap = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(auditLogMapper).selectPage(any(), cap.capture());
        String sql = seg(cap.getValue());
        assertFalse(sql.contains("WHERE"), sql);
        assertTrue(sql.contains("ORDER BY id DESC"), sql);
    }

    @Test
    void pageAuditAppliesAllFilters() {
        when(auditLogMapper.selectPage(any(), any())).thenReturn(new Page<>(1, 2));
        LocalDateTime start = LocalDateTime.of(2026, 1, 1, 0, 0);
        LocalDateTime end = LocalDateTime.of(2026, 2, 1, 0, 0);
        service.pageAudit(1, 2, "  alice ", "EXPORT", "Sheet", start, end);
        ArgumentCaptor<QueryWrapper> cap = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(auditLogMapper).selectPage(any(), cap.capture());
        String sql = seg(cap.getValue());
        assertTrue(sql.contains("username"), sql);
        assertTrue(sql.contains("action"), sql);
        assertTrue(sql.contains("target_type"), sql);
        assertTrue(sql.contains("create_time"), sql);
        // trim 后 like 值落在参数上
        assertTrue(paramHas(cap.getValue(), "%alice%"), sql);
    }

    @Test
    void pageAuditIgnoresBlankFilters() {
        when(auditLogMapper.selectPage(any(), any())).thenReturn(new Page<>(1, 2));
        service.pageAudit(1, 2, "   ", "", null, null, null);
        ArgumentCaptor<QueryWrapper> cap = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(auditLogMapper).selectPage(any(), cap.capture());
        String sql = seg(cap.getValue());
        assertFalse(sql.contains("username"), sql);
        assertFalse(sql.contains("action"), sql);
        assertFalse(sql.contains("target_type"), sql);
    }

    // ---------- pageCollabLog ----------

    @Test
    void pageCollabLogAppliesFiltersAndOrder() {
        service.pageCollabLog(1, 2, 9L, "  bob  ", true, false);
        ArgumentCaptor<QueryWrapper> cap = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(collabLogMapper).selectPage(any(), cap.capture());
        String sql = seg(cap.getValue());
        assertTrue(sql.contains("document_id"), sql);
        assertTrue(sql.contains("actor_name"), sql);
        assertTrue(sql.contains("merged"), sql);
        assertTrue(sql.contains("conflict"), sql);
        assertTrue(sql.toLowerCase().contains("order by id desc"), sql);
        assertTrue(paramHas(cap.getValue(), 9L) || paramHas(cap.getValue(), 9));
        assertTrue(paramHas(cap.getValue(), "%bob%"));
    }

    @Test
    void pageCollabLogMapsBooleanToInts() {
        service.pageCollabLog(1, 2, null, null, false, true);
        ArgumentCaptor<QueryWrapper> cap = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(collabLogMapper).selectPage(any(), cap.capture());
        String sql = seg(cap.getValue());
        assertTrue(sql.contains("merged"), sql);
        assertTrue(sql.contains("conflict"), sql);
        // merged=false→0、conflict=true→1
        assertTrue(paramHas(cap.getValue(), 0) || paramHas(cap.getValue(), 0L));
        assertTrue(paramHas(cap.getValue(), 1) || paramHas(cap.getValue(), 1L));
    }

    @Test
    void pageCollabLogNullOptionsNoWhere() {
        when(collabLogMapper.selectPage(any(), any())).thenReturn(new Page<>(1, 2));
        service.pageCollabLog(1, 2, null, null, null, null);
        ArgumentCaptor<QueryWrapper> cap = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(collabLogMapper).selectPage(any(), cap.capture());
        String sql = seg(cap.getValue());
        assertFalse(sql.contains("document_id"), sql);
        assertFalse(sql.contains("actor_name"), sql);
        assertTrue(sql.contains("ORDER BY id DESC"), sql);
    }

    // ---------- pageRooms ----------

    @Test
    void pageRoomsAppliesDocumentAndActor() {
        service.pageRooms(1, 2, 3L, "  carol  ");
        ArgumentCaptor<QueryWrapper> cap = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(collabRoomMapper).selectPage(any(), cap.capture());
        String sql = seg(cap.getValue());
        assertTrue(sql.contains("document_id"), sql);
        assertTrue(sql.contains("actor_name"), sql);
        assertTrue(sql.contains("update_time"), sql);
        assertTrue(paramHas(cap.getValue(), 3L) || paramHas(cap.getValue(), 3));
    }

    @Test
    void pageRoomsBlankActorOmitted() {
        service.pageRooms(1, 2, null, "   ");
        ArgumentCaptor<QueryWrapper> cap = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(collabRoomMapper).selectPage(any(), cap.capture());
        String sql = seg(cap.getValue());
        assertTrue(sql.contains("update_time DESC"), sql);
        assertFalse(sql.contains("document_id"), sql);
        assertFalse(sql.contains("actor_name"), sql);
    }

    // ---------- roomView ----------

    @Test
    void roomViewMapsAllFields() {
        ExcelCollabRoom room = new ExcelCollabRoom();
        room.setId(11L);
        room.setDocumentId(2L);
        room.setActorId(3L);
        room.setActorName("alice");
        room.setSessionId("sess-1");
        room.setCanEdit(1);
        room.setSheetId(5L);
        room.setCursorRow(7);
        room.setCursorCol(8);
        LocalDateTime now = LocalDateTime.now();
        room.setCreateTime(now);
        room.setUpdateTime(now);

        Map<String, Object> v = service.roomView(room);

        assertEquals(11, v.size());
        assertEquals(11L, v.get("id"));
        assertEquals(2L, v.get("documentId"));
        assertEquals(3L, v.get("actorId"));
        assertEquals("alice", v.get("actorName"));
        assertEquals("sess-1", v.get("sessionId"));
        assertEquals(1, v.get("canEdit"));
        assertEquals(5L, v.get("sheetId"));
        assertEquals(7, v.get("cursorRow"));
        assertEquals(8, v.get("cursorCol"));
        assertSame(now, v.get("createTime"));
        assertSame(now, v.get("updateTime"));
    }

    // ---------- kickRoom ----------

    @Test
    void kickRoomNoopOnNull() {
        service.kickRoom(null);
        verify(collabRoomMapper, never()).deleteById(anyLong());
    }

    @Test
    void kickRoomDeletes() {
        service.kickRoom(4L);
        verify(collabRoomMapper).deleteById(4L);
    }

    // ---------- versionStats ----------

    @Test
    void versionStatsCountsVersionsAndWithCellCount() {
        when(versionMapper.selectCount(any())).thenReturn(30L);
        Map<String, Object> stats = service.versionStats();
        assertEquals(30L, stats.get("versionCount"));
        assertEquals(30L, stats.get("versionRows"));
        ArgumentCaptor<QueryWrapper> cap = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(versionMapper, times(2)).selectCount(cap.capture());
        // versionCount 先以 null 全量计数，versionRows 的 cell_count 过滤 wrapper 在第二次调用
        String second = seg(cap.getAllValues().get(1));
        assertTrue(second.contains("cell_count"), second);
    }
}