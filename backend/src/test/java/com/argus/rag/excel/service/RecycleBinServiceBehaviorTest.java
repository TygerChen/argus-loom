package com.argus.rag.excel.service;

import com.argus.rag.auth.CurrentUserService;
import com.argus.rag.common.enums.SystemRole;
import com.argus.rag.common.exception.BusinessException;
import com.argus.rag.common.exception.ErrorCodes;
import com.argus.rag.excel.entity.ExcelDocument;
import com.argus.rag.excel.mapper.ExcelDocumentPermissionMapper;
import com.argus.rag.excel.mapper.OperationLogMapper;
import com.argus.rag.excel.model.dto.BatchCountResult;
import com.argus.rag.excel.model.dto.BatchOperationResult;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link RecycleBinService} 行为契约（mock 5 协作方：document/sheet/version service + 2 mapper）：
 * - 校验：null/空 ids → BusinessException("ids 不能为空")；去空并去重；
 * - 批量恢复/彻底删除：逐 id 委托，异常 → 明细拒绝（reason 用 msg，空则回落默认文案）；
 * - purgeOne：缺失 no-op、非回收站态→EXCEL_TRASH_STATE、正常级联清 version/sheet/permission/log + purge；
 * - listRecycleByPage：status=2 + 财务可见 finance_required 过滤（非财务）、非 ADMIN 加 creator_id、keyword trim like、
 *   order deleted_at DESC,id DESC、页码夹取 >=1；
 * - clearRecycle：空 → (0,0) 不 purge；非空 → 委派 batchPurge 计数。
 * JSON 形状由 {@code RecycleBinServiceContractTest} 守护，本类只锁行为与桩接线。
 */
@DisplayName("回收站行为（RecycleBinService）")
class RecycleBinServiceBehaviorTest {

    private final ExcelDocumentService documentService = mock(ExcelDocumentService.class);
    private final ExcelSheetService sheetService = mock(ExcelSheetService.class);
    private final ExcelVersionService versionService = mock(ExcelVersionService.class);
    private final ExcelDocumentPermissionMapper permissionMapper = mock(ExcelDocumentPermissionMapper.class);
    private final OperationLogMapper operationLogMapper = mock(OperationLogMapper.class);

    private final RecycleBinService svc = new RecycleBinService(
            documentService, sheetService, versionService, permissionMapper, operationLogMapper);

    private static final CurrentUserService.CurrentUser USER =
            new CurrentUserService.CurrentUser(7L, "U7", "普通", SystemRole.USER, false);
    private static final CurrentUserService.CurrentUser FINANCE =
            new CurrentUserService.CurrentUser(8L, "F8", "会计", SystemRole.FINANCE, false);
    private static final CurrentUserService.CurrentUser ADMIN =
            new CurrentUserService.CurrentUser(9L, "A9", "admin", SystemRole.ADMIN, false);

    private static ExcelDocument doc(Long id, Integer status) {
        ExcelDocument d = new ExcelDocument();
        d.setId(id);
        if (status != null) d.setStatus(status);
        return d;
    }

    private static String seg(QueryWrapper qw) {
        String s = qw.getCustomSqlSegment();
        return s == null ? "" : s;
    }

    private static boolean paramHas(QueryWrapper<?> qw, Object v) {
        return qw.getParamNameValuePairs().containsValue(v);
    }

    // ---------- validateIds ----------

    @Test
    void rejectsNullOrEmptyIds() {
        BusinessException e1 = assertThrows(BusinessException.class, () -> svc.batchRestore(null));
        assertEquals("ids 不能为空", e1.getMessage());
        BusinessException e2 = assertThrows(BusinessException.class, () -> svc.batchRestore(List.of()));
        assertEquals("ids 不能为空", e2.getMessage());
        verify(documentService, never()).restoreFromTrash(anyLong());
    }

    @Test
    void batchRestoreDedupesAndIgnoresNull() {
        BatchOperationResult r = svc.batchRestore(Arrays.asList(1L, null, 1L, 3L, 3L));
        assertEquals(2, r.total());
        assertEquals(List.of(1L, 3L), r.successIds());
        verify(documentService).restoreFromTrash(1L);
        verify(documentService).restoreFromTrash(3L);
        verify(documentService, times(2)).restoreFromTrash(anyLong());
    }

    // ---------- 批量拒绝 ----------

    @Test
    void batchRestoreRecordsRejectWithMessage() {
        doThrow(new RuntimeException("超时")).when(documentService).restoreFromTrash(2L);
        BatchOperationResult r = svc.batchRestore(List.of(1L, 2L));
        assertEquals(1, r.successCount());
        assertEquals(1, r.rejectedCount());
        assertEquals(2L, r.rejected().get(0).id());
        assertEquals("超时", r.rejected().get(0).reason());
    }

    @Test
    void batchRestoreRejectFallsBackWhenNoMessage() {
        doThrow(new RuntimeException()).when(documentService).restoreFromTrash(2L);
        BatchOperationResult r = svc.batchRestore(List.of(2L));
        assertEquals("恢复失败", r.rejected().get(0).reason());
    }

    // ---------- purgeOne ----------

    @Test
    void purgeOneNoopWhenMissing() {
        when(documentService.optionalById(42L)).thenReturn(null);
        svc.purgeOne(42L);
        verify(versionService, never()).deleteByDocumentId(anyLong());
        verify(sheetService, never()).hardDeleteByDocumentId(anyLong());
        verify(documentService, never()).purge(anyLong());
    }

    @Test
    void purgeOneThrowsWhenNotTrashStatus() {
        when(documentService.optionalById(42L)).thenReturn(doc(42L, 1));
        BusinessException e = assertThrows(BusinessException.class, () -> svc.purgeOne(42L));
        assertEquals(ErrorCodes.EXCEL_TRASH_STATE, e.getCode());
        verify(documentService, never()).purge(anyLong());
    }

    @Test
    void purgeOneCascadesFullStackTrace() {
        when(documentService.optionalById(42L)).thenReturn(doc(42L, 2));
        svc.purgeOne(42L);
        verify(versionService).deleteByDocumentId(42L);
        verify(sheetService).hardDeleteByDocumentId(42L);
        verify(permissionMapper).delete(any(QueryWrapper.class));
        verify(operationLogMapper).delete(any(QueryWrapper.class));
        verify(documentService).purge(42L);
    }

    // ---------- listRecycleByPage ----------

    @Test
    void listRecycleByPageClampsAndBuildsWrapper() {
        when(documentService.pageList(any(Page.class), any(QueryWrapper.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        Page<ExcelDocument> out = svc.listRecycleByPage(-3, -5, "  产品表  ", USER);

        ArgumentCaptor<Page> pCap = ArgumentCaptor.forClass(Page.class);
        ArgumentCaptor<QueryWrapper> qCap = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(documentService).pageList(pCap.capture(), qCap.capture());
        assertEquals(1, pCap.getValue().getCurrent());
        assertEquals(1, pCap.getValue().getSize());
        String sql = seg(qCap.getValue());
        assertTrue(sql.contains("status"), sql);
        assertTrue(sql.contains("creator_id"), sql);
        assertTrue(sql.contains("finance_required"), sql);   // 非财务用户隐藏财务档
        assertTrue(sql.contains("name"), sql);
        assertTrue(sql.toLowerCase().contains("order by deleted_at desc"), sql);
        assertTrue(paramHas(qCap.getValue(), "%产品表%"), sql);
    }

    @Test
    void listRecycleByPageFinanceSkipsFilterAndAdminOmitsCreator() {
        when(documentService.pageList(any(Page.class), any(QueryWrapper.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        svc.listRecycleByPage(1, 10, null, FINANCE);
        ArgumentCaptor<QueryWrapper> qCap = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(documentService).pageList(any(Page.class), qCap.capture());
        String sql = seg(qCap.getValue());
        assertTrue(!sql.contains("finance_required"), "财务用户不隐藏财务档: " + sql);

        svc.listRecycleByPage(1, 10, null, ADMIN);
        ArgumentCaptor<QueryWrapper> aCap = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(documentService, times(2)).pageList(any(Page.class), aCap.capture());
        String adminSql = seg(aCap.getAllValues().get(1));
        assertTrue(!adminSql.contains("creator_id"), "管理员不按 creator 过滤: " + adminSql);
    }

    // ---------- clearRecycle ----------

    @Test
    void clearRecycleEmptyYieldsZeroNoPurge() {
        when(documentService.listIds(any(QueryWrapper.class))).thenReturn(List.of());
        BatchCountResult r = svc.clearRecycle(USER);
        assertEquals(0, r.total());
        assertEquals(0, r.successCount());
        assertEquals(0, r.rejectedCount());
        verify(documentService, never()).purge(anyLong());
    }

    @Test
    void clearRecycleDelegatesPurgeAndCounts() {
        when(documentService.listIds(any(QueryWrapper.class))).thenReturn(List.of(1L, 2L));
        when(documentService.optionalById(1L)).thenReturn(doc(1L, 2));
        when(documentService.optionalById(2L)).thenReturn(doc(2L, 2));
        BatchCountResult r = svc.clearRecycle(USER);
        assertEquals(2, r.total());
        assertEquals(2, r.successCount());
        assertEquals(0, r.rejectedCount());
        verify(documentService, times(2)).purge(anyLong());

        ArgumentCaptor<QueryWrapper> cap = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(documentService).listIds(cap.capture());
        String sql = seg(cap.getValue());
        assertTrue(sql.contains("status"), sql);
        assertTrue(sql.contains("finance_required"), sql);    // 普通用户清空仅非财务
        assertTrue(sql.contains("creator_id"), sql);
    }
}