package com.argus.rag.excel.service;

import com.argus.rag.auth.CurrentUserService;
import com.argus.rag.common.exception.BusinessException;
import com.argus.rag.common.exception.ErrorCodes;
import com.argus.rag.excel.entity.ExcelDocument;
import com.argus.rag.excel.entity.ExcelDocumentVersion;
import com.argus.rag.excel.entity.ExcelDocumentVersionChunk;
import com.argus.rag.excel.entity.ExcelDocumentVersionSheet;
import com.argus.rag.excel.entity.ExcelSheet;
import com.argus.rag.excel.entity.ExcelSheetChunk;
import com.argus.rag.excel.mapper.ExcelDocumentVersionChunkMapper;
import com.argus.rag.excel.mapper.ExcelDocumentVersionMapper;
import com.argus.rag.excel.mapper.ExcelDocumentVersionSheetMapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ExcelVersionService} 版本快照 / 回滚 / 预览 / 删除契约（全 mock / 零 DB / 零字体）：
 * - 快照创建：存在性守卫、回收站态拒绝、type/summary 归一与截断、sheet/chunk 逐层落库、cellCount 累计、保留裁剪；
 * - 自动快照守卫：非法入参 / 近期已有 → 跳过；到期或无 → 创建；失败吞掉不阻断；
 * - 查询（page/latest 委托；detail/preview 校验版本归属——缺失或跨文档一律 EXCEL_NOT_FOUND，坏 JSON 兜底）；
 * - 一键回滚：目标校验、空内容拒绝、前置自动保存 → 硬删 → 重建 → 更新 meta → 推进版本号；
 * - 整体删除：无版本 no-op、有版本清三层。
 * 私有工具经公共路径间接断言。
 */
@DisplayName("Excel 版本快照与回滚（ExcelVersionService）")
class ExcelVersionServiceTest {

    private ExcelDocumentVersionMapper versionMapper;
    private ExcelDocumentVersionSheetMapper versionSheetMapper;
    private ExcelDocumentVersionChunkMapper versionChunkMapper;
    private ExcelDocumentService documentService;
    private ExcelSheetService sheetService;
    private ExcelSystemConfigService configService;
    private ExcelDocumentLockService lockService;
    private CurrentUserService currentUserService;
    private ExcelVersionService service;

    private final CurrentUserService.CurrentUser user =
            new CurrentUserService.CurrentUser(7L, "U7", "会计");

    @BeforeEach
    void setUp() {
        versionMapper = mock(ExcelDocumentVersionMapper.class);
        versionSheetMapper = mock(ExcelDocumentVersionSheetMapper.class);
        versionChunkMapper = mock(ExcelDocumentVersionChunkMapper.class);
        documentService = mock(ExcelDocumentService.class);
        sheetService = mock(ExcelSheetService.class);
        configService = mock(ExcelSystemConfigService.class);
        lockService = mock(ExcelDocumentLockService.class);
        currentUserService = mock(CurrentUserService.class);
        service = new ExcelVersionService(versionMapper, versionSheetMapper, versionChunkMapper,
                documentService, sheetService, configService, lockService, currentUserService,
                new com.fasterxml.jackson.databind.ObjectMapper());
        when(currentUserService.getRequiredCurrentUser()).thenReturn(user);
        when(configService.getRetentionCount()).thenReturn(10);
    }

    // ---------- fixtures ----------

    private ExcelDocument live() {
        ExcelDocument doc = new ExcelDocument();
        doc.setStatus(1);
        doc.setVersion(3L);
        return doc;
    }

    private ExcelSheet sheet() {
        ExcelSheet s = new ExcelSheet();
        s.setId(1L);
        s.setSheetIndex(0);
        s.setSheetName("S1");
        s.setTotalRows(5);
        s.setTotalCols(3);
        s.setConfigJson("{\"k\":1}");
        return s;
    }

    private ExcelSheetChunk chunkOf3() {
        ExcelSheetChunk c = new ExcelSheetChunk();
        c.setChunkIndex(0);
        c.setRowStart(0);
        c.setRowEnd(999);
        c.setCelldataJson("[{\"v\":1},{\"v\":2},{\"v\":3}]");
        return c;
    }

    private ExcelDocumentVersion version() {
        ExcelDocumentVersion v = new ExcelDocumentVersion();
        v.setId(1L);
        v.setDocumentId(1L);
        v.setSnapshotType("CELL");
        v.setCreateTime(LocalDateTime.now());
        return v;
    }

    private ExcelDocumentVersionSheet versionSheet() {
        ExcelDocumentVersionSheet vs = new ExcelDocumentVersionSheet();
        vs.setId(10L);
        vs.setVersionId(5L);
        vs.setSheetIndex(0);
        vs.setSheetName("S1");
        vs.setTotalRows(5);
        vs.setTotalCols(3);
        vs.setChunkCount(1);
        vs.setActive(1);
        vs.setConfigJson("{\"k\":1}");
        return vs;
    }

    private ExcelDocumentVersionChunk versionChunk() {
        ExcelDocumentVersionChunk vc = new ExcelDocumentVersionChunk();
        vc.setVersionSheetId(10L);
        vc.setChunkIndex(0);
        vc.setRowStart(0);
        vc.setRowEnd(999);
        vc.setCelldataJson("[{\"v\":\"a\"},{\"v\":\"b\"}]");
        return vc;
    }

    // ---------- createSnapshot ----------

    @Test
    void createSnapshotRejectsMissingDocument() {
        when(documentService.optionalById(1L)).thenReturn(null);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.createSnapshot(1L, "CELL", "s"));
        assertEquals(ErrorCodes.EXCEL_NOT_FOUND, ex.getCode());
    }

    @Test
    void createSnapshotRejectsTrashed() {
        ExcelDocument doc = new ExcelDocument();
        doc.setStatus(2);
        when(documentService.optionalById(1L)).thenReturn(doc);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.createSnapshot(1L, "CELL", "s"));
        assertEquals(ErrorCodes.EXCEL_TRASH_STATE, ex.getCode());
    }

    @Test
    void createSnapshotPersistsFullSnapshot() {
        when(documentService.optionalById(1L)).thenReturn(live());
        when(versionMapper.insert(any(ExcelDocumentVersion.class))).thenAnswer(inv -> {
            ExcelDocumentVersion v = inv.getArgument(0);
            v.setId(5L);
            return 1;
        });
        when(sheetService.listSheetsByDocumentId(1L)).thenReturn(List.of(sheet()));
        // P2-07：createSnapshot 改用批量分组读取（一次查询替代每 Sheet 一次）
        when(sheetService.listChunksByDocumentGrouped(1L))
                .thenReturn(Map.of(sheet().getId(), List.of(chunkOf3())));
        when(versionSheetMapper.insert(any(ExcelDocumentVersionSheet.class))).thenAnswer(inv -> {
            ExcelDocumentVersionSheet vs = inv.getArgument(0);
            vs.setId(10L);
            return 1;
        });

        ExcelDocumentVersion result = service.createSnapshot(1L, "CELL", "  批量改  ");

        // 主表：type、docVersion、creator、summary trim
        ArgumentCaptor<ExcelDocumentVersion> verCap =
                ArgumentCaptor.forClass(ExcelDocumentVersion.class);
        verify(versionMapper).insert(verCap.capture());
        ExcelDocumentVersion inserted = verCap.getValue();
        assertEquals("CELL", inserted.getSnapshotType());
        assertEquals(3L, inserted.getDocVersion());
        assertEquals(7L, inserted.getCreatorId());
        assertEquals("U7", inserted.getCreatorName());
        assertEquals("批量改", inserted.getSummary());

        // 版本 chunk 落库
        ArgumentCaptor<ExcelDocumentVersionChunk> chunkCap =
                ArgumentCaptor.forClass(ExcelDocumentVersionChunk.class);
        verify(versionChunkMapper).insert(chunkCap.capture());
        assertEquals(0, chunkCap.getValue().getChunkIndex());
        assertEquals(10L, chunkCap.getValue().getVersionSheetId());

        // 汇总回写：sheetCount=1、cellCount=3
        ArgumentCaptor<ExcelDocumentVersion> sumCap =
                ArgumentCaptor.forClass(ExcelDocumentVersion.class);
        verify(versionMapper).updateById(sumCap.capture());
        assertEquals(1, sumCap.getValue().getSheetCount());
        assertEquals(3L, sumCap.getValue().getCellCount());

        assertEquals(5L, result.getId());
    }

    @Test
    void createSnapshotClampsAndDefaultsSummary() {
        when(documentService.optionalById(1L)).thenReturn(live());
        when(sheetService.listSheetsByDocumentId(1L)).thenReturn(List.of());

        service.createSnapshot(1L, null, null);
        ArgumentCaptor<ExcelDocumentVersion> cap =
                ArgumentCaptor.forClass(ExcelDocumentVersion.class);
        verify(versionMapper).insert(cap.capture());
        assertEquals(ExcelVersionService.TYPE_MANUAL, cap.getValue().getSnapshotType());
        assertEquals("版本快照", cap.getValue().getSummary());
    }

    @Test
    void createSnapshotTruncatesLongSummaryTo500() {
        when(documentService.optionalById(1L)).thenReturn(live());
        when(sheetService.listSheetsByDocumentId(1L)).thenReturn(List.of());
        StringBuilder longSummary = new StringBuilder();
        for (int i = 0; i < 600; i++) longSummary.append('x');
        service.createSnapshot(1L, "CELL", longSummary.toString());
        ArgumentCaptor<ExcelDocumentVersion> cap =
                ArgumentCaptor.forClass(ExcelDocumentVersion.class);
        verify(versionMapper).insert(cap.capture());
        assertEquals(500, cap.getValue().getSummary().length());
    }

    @Test
    void createSnapshotToleratesNoCurrentUser() {
        when(currentUserService.getRequiredCurrentUser())
                .thenThrow(new RuntimeException("no login"));
        when(documentService.optionalById(1L)).thenReturn(live());
        when(sheetService.listSheetsByDocumentId(1L)).thenReturn(List.of());
        service.createSnapshot(1L, "CELL", "s");
        ArgumentCaptor<ExcelDocumentVersion> cap =
                ArgumentCaptor.forClass(ExcelDocumentVersion.class);
        verify(versionMapper).insert(cap.capture());
        assertNull(cap.getValue().getCreatorId());
    }

    // ---------- 自动快照守卫 ----------

    @Test
    void autoSnapshotNoopOnInvalidArgs() {
        service.tryAutoCellSnapshot(null, 5);
        service.tryAutoCellSnapshot(1L, 0);
        verify(documentService, never()).optionalById(any());
    }

    @Test
    void autoSnapshotSkipsWhenRecent() {
        ExcelDocumentVersion recent = new ExcelDocumentVersion();
        recent.setCreateTime(LocalDateTime.now());
        when(versionMapper.selectOne(any())).thenReturn(recent);
        when(configService.getAutoCellIntervalMinutes()).thenReturn(60);
        service.tryAutoCellSnapshot(1L, 10);
        verify(documentService, never()).optionalById(any());
    }

    @Test
    void autoSnapshotCreatesWhenExpiredOrNone() {
        when(versionMapper.selectOne(any())).thenReturn(null);
        when(documentService.optionalById(1L)).thenReturn(live());
        when(sheetService.listSheetsByDocumentId(1L)).thenReturn(List.of());
        service.tryAutoCellSnapshot(1L, 5);
        verify(versionMapper).insert(any(ExcelDocumentVersion.class));
    }

    @Test
    void autoSnapshotSwallowsFailure() {
        when(versionMapper.selectOne(any())).thenReturn(null);
        when(documentService.optionalById(1L)).thenReturn(null); // createSnapshot 抛 EXCEL_NOT_FOUND
        // 失败吞掉，不阻断外层保存
        service.tryAutoCellSnapshot(1L, 5);
        service.tryAfterStructureSave(1L);
    }

    // ---------- 查询 ----------

    @Test
    void pageVersionsClampsAndDelegates() {
        Page<ExcelDocumentVersion> expected = new Page<>(1, 20);
        when(versionMapper.selectPage(any(), any())).thenReturn(expected);
        assertEquals(expected, service.pageVersions(1L, -5, 0));
    }

    @Test
    void latestVersionDelegates() {
        ExcelDocumentVersion v = version();
        when(versionMapper.selectOne(any())).thenReturn(v);
        assertSame(v, service.latestVersion(1L));
    }

    @Test
    void versionDetailRejectsMissingVersion() {
        when(versionMapper.selectById(1L)).thenReturn(null);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.versionDetail(1L, 1L));
        assertEquals(ErrorCodes.EXCEL_NOT_FOUND, ex.getCode());
    }

    @Test
    void versionDetailRejectsForeignDocument() {
        // version.documentId=1，却以文档 2 的身份读取 → 跨文档访问按不存在处理
        when(versionMapper.selectById(1L)).thenReturn(version());
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.versionDetail(2L, 1L));
        assertEquals(ErrorCodes.EXCEL_NOT_FOUND, ex.getCode());
    }

    @Test
    void versionDetailBuildsSheetMetadata() {
        ExcelDocumentVersion v = version();
        when(versionMapper.selectById(1L)).thenReturn(v);
        when(versionSheetMapper.selectList(any())).thenReturn(List.of(versionSheet()));

        Map<String, Object> detail = service.versionDetail(1L, 1L);

        assertEquals("CELL", detail.get("snapshotType"));
        Map<?, ?> sheet = (Map<?, ?>) ((List<?>) detail.get("sheets")).get(0);
        assertEquals("S1", sheet.get("sheetName"));
        assertEquals(1, sheet.get("chunkCount"));
    }

    @Test
    void previewVersionRejectsMissingVersion() {
        when(versionMapper.selectById(1L)).thenReturn(null);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.previewVersion(1L, 1L));
        assertEquals(ErrorCodes.EXCEL_NOT_FOUND, ex.getCode());
    }

    @Test
    void previewVersionRejectsForeignDocument() {
        when(versionMapper.selectById(1L)).thenReturn(version());
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.previewVersion(2L, 1L));
        assertEquals(ErrorCodes.EXCEL_NOT_FOUND, ex.getCode());
    }

    @Test
    void previewVersionRestoresWorkbookShape() {
        when(versionMapper.selectById(1L)).thenReturn(version());
        when(versionSheetMapper.selectList(any())).thenReturn(List.of(versionSheet()));
        when(versionChunkMapper.selectList(any())).thenReturn(List.of(versionChunk()));

        Map<String, Object> preview = service.previewVersion(1L, 1L);

        Map<String, Object> sheet = ((List<Map<String, Object>>) preview.get("sheets")).get(0);
        assertEquals("S1", sheet.get("name"));
        assertEquals(5, sheet.get("row"));
        assertEquals(3, sheet.get("column"));
        assertEquals(1, ((Map<?, ?>) sheet.get("config")).get("k"));
        assertEquals(2, ((List<?>) sheet.get("celldata")).size());
    }

    @Test
    void previewVersionDefaultsInvalidJsonToEmpty() {
        when(versionMapper.selectById(1L)).thenReturn(version());
        ExcelDocumentVersionSheet vs = versionSheet();
        vs.setConfigJson("not-json");
        when(versionSheetMapper.selectList(any())).thenReturn(List.of(vs));
        when(versionChunkMapper.selectList(any())).thenReturn(List.of());

        Map<String, Object> preview = service.previewVersion(1L, 1L);
        Map<String, Object> sheet = ((List<Map<String, Object>>) preview.get("sheets")).get(0);
        assertEquals(0, ((Map<?, ?>) sheet.get("config")).size());
    }

    // ---------- 一键回滚 ----------

    @Test
    void restoreVersionRejectsWrongDocument() {
        ExcelDocumentVersion target = version();
        target.setDocumentId(99L);
        when(versionMapper.selectById(1L)).thenReturn(target);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.restoreVersion(1L, 1L));
        assertEquals(ErrorCodes.EXCEL_NOT_FOUND, ex.getCode());
    }

    @Test
    void restoreVersionRejectsEmptyContent() {
        when(versionMapper.selectById(1L)).thenReturn(version());
        when(versionSheetMapper.selectList(any())).thenReturn(List.of());
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.restoreVersion(1L, 1L));
        assertEquals(ErrorCodes.EXCEL_NOT_FOUND, ex.getCode());
        assertEquals("版本内容为空，无法回滚", ex.getMessage());
    }

    @Test
    void restoreVersionSnapshotsDeletesAndRebuilds() {
        when(versionMapper.selectById(1L)).thenReturn(version());
        when(versionSheetMapper.selectList(any())).thenReturn(List.of(versionSheet()));
        when(versionChunkMapper.selectList(any())).thenReturn(List.of(versionChunk()));
        // 前置自动保存：快照当前状态
        when(documentService.optionalById(1L)).thenReturn(live());
        when(versionMapper.insert(any(ExcelDocumentVersion.class))).thenAnswer(inv -> {
            ExcelDocumentVersion r = inv.getArgument(0);
            r.setId(99L);
            return 1;
        });
        when(sheetService.listSheetsByDocumentId(1L)).thenReturn(List.of());
        // 重建：sheet 落库须返回带 id 的实体，供后续 insertChunk 使用
        when(sheetService.insertSheetWithoutHistory(any())).thenAnswer(inv -> {
            ExcelSheet s = inv.getArgument(0);
            s.setId(1L);
            return s;
        });
        when(sheetService.insertChunkWithoutHistory(any(), any(), any(), any(), any(), any()))
                .thenAnswer(inv -> new ExcelSheetChunk());
        when(documentService.bumpVersion(1L)).thenReturn(4L);

        Map<String, Object> result = service.restoreVersion(1L, 1L);

        assertEquals(1L, result.get("documentId"));
        assertEquals(1L, result.get("versionId"));
        assertEquals(4L, result.get("version"));
        assertEquals(1, result.get("sheetCount"));
        verify(sheetService).hardDeleteByDocumentId(1L);
        verify(documentService).updateSheetMeta(1L, 1, "[\"S1\"]");
        verify(sheetService).insertChunkWithoutHistory(1L, 1L, 0, 0, 999,
                "[{\"v\":\"a\"},{\"v\":\"b\"}]");
    }

    // ---------- 整体删除 ----------

    @Test
    void deleteByDocumentIdNoopWhenNoVersions() {
        when(versionMapper.selectObjs(any())).thenReturn(List.of());
        service.deleteByDocumentId(1L);
        verify(versionMapper, never()).delete(any());
    }

    @Test
    void deleteByDocumentIdCleansThreeTiers() {
        when(versionMapper.selectObjs(any())).thenReturn(List.of(1L, 2L));
        service.deleteByDocumentId(1L);
        verify(versionChunkMapper).delete(any(Wrapper.class));
        verify(versionSheetMapper).delete(any(Wrapper.class));
        verify(versionMapper).delete(any(Wrapper.class));
    }
}