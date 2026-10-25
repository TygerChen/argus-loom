package com.argus.rag.excel.service;

import com.argus.rag.auth.CurrentUserService;
import com.argus.rag.common.enums.SystemRole;
import com.argus.rag.common.exception.BusinessException;
import com.argus.rag.common.exception.ErrorCodes;
import com.argus.rag.excel.entity.ExcelDocument;
import com.argus.rag.excel.entity.ExcelExportTask;
import com.argus.rag.excel.mapper.ExcelExportTaskMapper;
import com.argus.rag.excel.service.ExcelPermissionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ExcelExportService} 导出任务契约（mock mapper / 服务，写入路径反射到临时目录）：
 * - 创建：文档缺失拒、财务文档非财务用户拒（{@code EXCEL_FINANCE_FORBIDDEN}）、财务用户放行、正常落 PENDING 任务（taskNo 20 位、缺省文件名）；
 * - 执行失败：工作簿构建抛错 → 状态 FAILED + 错误信息落库（成功写盘依赖 POI/真实磁盘，同 FinancePdfRenderer 先例不做单测）；
 * - 访问控制：财务任务非财务拒、非本人非 ADMIN 拒、本人放行；
 * - 取消：已完成拒绝、可取消置 CANCELLED；
 * - 下载守卫：未完成任务拒绝。
 * 财务判定只用 {@code CurrentUserService.isFinanceUser}，不触碰判定实现/角色表。
 */
@DisplayName("Excel 异步导出（ExcelExportService）")
class ExcelExportServiceTest {

    @TempDir
    Path uploadDir;

    private ExcelExportTaskMapper taskMapper;
    private ExcelDocumentService documentService;
    private ExcelSheetService sheetService;
    private CurrentUserService currentUserService;
    private ExecutorService exportExecutor;
    private Future<?> exportFuture;
    private ExcelExportService service;

    private final CurrentUserService.CurrentUser user =
            new CurrentUserService.CurrentUser(7L, "U7", "会计", SystemRole.USER, false);
    private final CurrentUserService.CurrentUser financeUser =
            new CurrentUserService.CurrentUser(8L, "F8", "财务", SystemRole.FINANCE, false);
    private final CurrentUserService.CurrentUser admin =
            new CurrentUserService.CurrentUser(9L, "A9", "管理", SystemRole.ADMIN, false);

    @BeforeEach
    void setUp() throws Exception {
        taskMapper = mock(ExcelExportTaskMapper.class);
        documentService = mock(ExcelDocumentService.class);
        sheetService = mock(ExcelSheetService.class);
        currentUserService = mock(CurrentUserService.class);
        // P1-06：导出线程池以 mock 注入，submit 只登记不真跑，用例保持确定性
        exportExecutor = mock(ExecutorService.class);
        exportFuture = mock(Future.class);
        doReturn(exportFuture).when(exportExecutor).submit(any(Runnable.class));
        service = new ExcelExportService(taskMapper, documentService, sheetService,
                currentUserService, exportExecutor);
        // 重定向 uploadPath 到临时目录，避免在工作目录写引导产物/目录
        Field f = ExcelExportService.class.getDeclaredField("uploadPath");
        f.setAccessible(true);
        f.set(service, uploadDir.resolve("up").toString());
        // createTask 异步 worker 会真实还原 xlsx；一律让其失败，避开文件系统竞态（失败路径即为单测目标）
        when(sheetService.listSheetsByDocumentId(any())).thenThrow(new RuntimeException("shunt"));
        // MyBatis-Plus 会在 insert 后回填自增主键；mock 里显式模拟，createTask 投递任务时 ID 才有值
        doAnswer(inv -> {
            ExcelExportTask inserted = inv.getArgument(0);
            if (inserted.getId() == null) {
                inserted.setId(1L);
            }
            return 1;
        }).when(taskMapper).insert(any(ExcelExportTask.class));
    }

    private ExcelDocument doc(boolean finance, String name) {
        ExcelDocument doc = new ExcelDocument();
        doc.setId(1L);
        doc.setFinanceRequired(finance);
        doc.setName(name);
        return doc;
    }

    private ExcelExportTask task(int status, Long ownerUserId) {
        ExcelExportTask t = new ExcelExportTask();
        t.setId(1L);
        t.setDocumentId(1L);
        t.setUserId(ownerUserId);
        t.setStatus(status);
        return t;
    }

    // ---------- createTask ----------

    @Test
    void createTaskRejectsMissingDocument() {
        when(documentService.getById(1L))
                .thenThrow(new BusinessException(ErrorCodes.EXCEL_NOT_FOUND, "文档不存在"));
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.createTask(1L));
        assertEquals(ErrorCodes.EXCEL_NOT_FOUND, ex.getCode());
    }

    @Test
    void createTaskRejectsFinanceForNonFinanceUser() {
        when(currentUserService.getRequiredCurrentUser()).thenReturn(user);
        when(documentService.getById(1L)).thenReturn(doc(true, "财务.xlsx"));
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.createTask(1L));
        assertEquals(ErrorCodes.EXCEL_FINANCE_FORBIDDEN, ex.getCode());
        // 未落库
        verify(taskMapper, never()).insert(any(ExcelExportTask.class));
    }

    @Test
    void createTaskAllowsFinanceForFinanceUser() {
        when(currentUserService.getRequiredCurrentUser()).thenReturn(financeUser);
        when(documentService.getById(1L)).thenReturn(doc(true, "财务.xlsx"));

        ExcelExportTask created = service.createTask(1L);

        assertNotNull(created);
        verify(taskMapper).insert(any(ExcelExportTask.class));
    }

    @Test
    void createTaskPersistsPendingTask() {
        when(currentUserService.getRequiredCurrentUser()).thenReturn(user);
        when(documentService.getById(1L)).thenReturn(doc(false, "报告.xlsx"));
        ArgumentCaptor<ExcelExportTask> cap = ArgumentCaptor.forClass(ExcelExportTask.class);
        service.createTask(1L);
        verify(taskMapper).insert(cap.capture());
        ExcelExportTask t = cap.getValue();
        assertEquals(1L, t.getDocumentId());
        assertEquals(7L, t.getUserId());
        assertEquals("U7", t.getUsername());
        assertEquals(ExcelExportTask.STATUS_PENDING, t.getStatus());
        assertEquals(0, t.getProgress());
        assertEquals("报告.xlsx", t.getFileName());
        // taskNo：20 位、无连字符
        assertEquals(20, t.getTaskNo().length());
        assertEquals(-1, t.getTaskNo().indexOf('-'));
    }

    @Test
    @org.junit.jupiter.api.DisplayName("对源文档无 view 权限的用户不能创建导出任务")
    void createTaskRejectsWhenDocumentViewLost() {
        ExcelPermissionService permissions = mock(ExcelPermissionService.class);
        doThrow(new BusinessException(ErrorCodes.PERMISSION_DENIED, "没有查看该文档的权限"))
                .when(permissions).requireView(any(), any());
        ExcelExportService guarded = new ExcelExportService(taskMapper, documentService,
                sheetService, currentUserService, exportExecutor,
                new com.argus.rag.common.config.ArgusRagProperties(), permissions);
        when(currentUserService.getRequiredCurrentUser()).thenReturn(user);
        when(documentService.getById(1L)).thenReturn(doc(false, "报告.xlsx"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> guarded.createTask(1L));
        assertEquals(ErrorCodes.PERMISSION_DENIED, ex.getCode());
        verify(taskMapper, never()).insert(any(ExcelExportTask.class));
    }

    @Test
    @org.junit.jupiter.api.DisplayName("创建导出任务前会重鉴权源文档 view 权限（与下载侧同口径）")
    void createTaskRechecksDocumentViewBeforeEnqueue() {
        ExcelPermissionService permissions = mock(ExcelPermissionService.class);
        ExcelExportService guarded = new ExcelExportService(taskMapper, documentService,
                sheetService, currentUserService, exportExecutor,
                new com.argus.rag.common.config.ArgusRagProperties(), permissions);
        when(currentUserService.getRequiredCurrentUser()).thenReturn(user);
        when(documentService.getById(1L)).thenReturn(doc(false, "报告.xlsx"));

        guarded.createTask(1L);

        verify(permissions).requireView(1L, user);
        verify(taskMapper).insert(any(ExcelExportTask.class));
    }

    // ---------- executeTask 失败路径 ----------

    @Test
    void executeTaskFailureMarksTaskFailed() {
        // setUp 已令 listSheetsByDocumentId 抛 "shunt"，工作簿构建必失败 → 任务置 FAILED 并回写错误文案
        when(taskMapper.selectById(1L)).thenReturn(task(ExcelExportTask.STATUS_PENDING, 7L));

        service.executeTask(1L);

        // RUNNING(10) 更新先落，FAILED 更新最后落
        ArgumentCaptor<ExcelExportTask> cap = ArgumentCaptor.forClass(ExcelExportTask.class);
        verify(taskMapper, times(2)).updateById(cap.capture());

        ExcelExportTask first = cap.getAllValues().get(0);
        assertEquals(ExcelExportTask.STATUS_RUNNING, first.getStatus());
        assertEquals(10, first.getProgress());

        ExcelExportTask last = cap.getValue();
        assertEquals(ExcelExportTask.STATUS_FAILED, last.getStatus());
        assertEquals("shunt", last.getErrorMessage());
    }

    // ---------- 访问控制 ----------

    @Test
    void getTaskForCurrentUserMissingThrows() {
        when(taskMapper.selectById(1L)).thenReturn(null);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.getTaskForCurrentUser(1L));
        assertEquals(ErrorCodes.EXCEL_NOT_FOUND, ex.getCode());
    }

    @Test
    void financeTaskDeniedForNonFinanceUser() {
        when(currentUserService.getRequiredCurrentUser()).thenReturn(user);
        when(taskMapper.selectById(1L)).thenReturn(task(0, 7L));
        when(documentService.optionalById(1L)).thenReturn(doc(true, "财务.xlsx"));
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.getTaskForCurrentUser(1L));
        assertEquals(ErrorCodes.PERMISSION_DENIED, ex.getCode());
    }

    @Test
    void nonOwnerNonAdminDenied() {
        when(currentUserService.getRequiredCurrentUser()).thenReturn(user);
        when(taskMapper.selectById(1L)).thenReturn(task(0, 999L)); // 他人任务
        when(documentService.optionalById(1L)).thenReturn(doc(false, "a.xlsx"));
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.getTaskForCurrentUser(1L));
        assertEquals(ErrorCodes.PERMISSION_DENIED, ex.getCode());
    }

    @Test
    void ownerCanAccessOwnTask() {
        when(currentUserService.getRequiredCurrentUser()).thenReturn(user);
        when(taskMapper.selectById(1L)).thenReturn(task(0, 7L)); // 本人
        when(documentService.optionalById(1L)).thenReturn(doc(false, "a.xlsx"));
        ExcelExportTask t = service.getTaskForCurrentUser(1L);
        assertEquals(1L, t.getId());
    }

    @Test
    void adminCanAccessAnyNonFinanceTask() {
        when(currentUserService.getRequiredCurrentUser()).thenReturn(admin);
        when(taskMapper.selectById(1L)).thenReturn(task(0, 999L));
        when(documentService.optionalById(1L)).thenReturn(doc(false, "a.xlsx"));
        assertEquals(1L, service.getTaskForCurrentUser(1L).getId());
    }

    // ---------- 取消 ----------

    @Test
    void cancelTaskRejectsCompleted() {
        when(currentUserService.getRequiredCurrentUser()).thenReturn(user);
        when(taskMapper.selectById(1L)).thenReturn(task(ExcelExportTask.STATUS_SUCCESS, 7L));
        when(documentService.optionalById(1L)).thenReturn(doc(false, "a.xlsx"));
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.cancelTask(1L));
        assertEquals(ErrorCodes.EXCEL_EXPORT_STATE, ex.getCode());
    }

    @Test
    void cancelTaskMarksCancelled() {
        when(currentUserService.getRequiredCurrentUser()).thenReturn(user);
        when(taskMapper.selectById(1L)).thenReturn(task(ExcelExportTask.STATUS_PENDING, 7L));
        when(documentService.optionalById(1L)).thenReturn(doc(false, "a.xlsx"));
        service.cancelTask(1L);
        ArgumentCaptor<ExcelExportTask> cap = ArgumentCaptor.forClass(ExcelExportTask.class);
        verify(taskMapper).updateById(cap.capture());
        assertEquals(ExcelExportTask.STATUS_CANCELLED, cap.getValue().getStatus());
        assertEquals("用户取消", cap.getValue().getErrorMessage());
    }

    // ---------- P1-06：专用线程池 / 真取消 ----------

    @Test
    @DisplayName("创建任务投递到导出专用线程池（不再落公共 ForkJoinPool）")
    void createTaskSubmitsToDedicatedExecutor() {
        when(currentUserService.getRequiredCurrentUser()).thenReturn(user);
        when(documentService.getById(1L)).thenReturn(doc(false, "报告.xlsx"));

        service.createTask(1L);

        verify(exportExecutor).submit(any(Runnable.class));
    }

    @Test
    @DisplayName("取消会真正 cancel 运行中任务的 Future（此前只改状态）")
    void cancelTaskCancelsRunningFuture() {
        when(currentUserService.getRequiredCurrentUser()).thenReturn(user);
        when(documentService.getById(1L)).thenReturn(doc(false, "报告.xlsx"));
        when(documentService.optionalById(1L)).thenReturn(doc(false, "报告.xlsx"));
        when(taskMapper.selectById(1L)).thenReturn(task(ExcelExportTask.STATUS_PENDING, 7L));

        service.createTask(1L);      // 登记 Future
        service.cancelTask(1L);      // 应打断执行线程

        verify(exportFuture).cancel(true);
    }

    @Test
    @DisplayName("投递被拒绝（池关闭/饱和）时任务直接置失败，不悬挂")
    void rejectedSubmissionMarksTaskFailed() {
        when(currentUserService.getRequiredCurrentUser()).thenReturn(user);
        when(documentService.getById(1L)).thenReturn(doc(false, "报告.xlsx"));
        doThrow(new RejectedExecutionException("pool closed"))
                .when(exportExecutor).submit(any(Runnable.class));

        service.createTask(1L);

        ArgumentCaptor<ExcelExportTask> cap = ArgumentCaptor.forClass(ExcelExportTask.class);
        verify(taskMapper).updateById(cap.capture());
        assertEquals(ExcelExportTask.STATUS_FAILED, cap.getValue().getStatus());
        assertNotNull(cap.getValue().getErrorMessage());
    }

    @Test
    @DisplayName("已取消任务不再执行，也不会被后写的 SUCCESS 覆盖")
    void executeTaskStopsWhenCancelledMidFlight() {
        // 第一、二次读取见 PENDING（任务读取 + 取消复查），RUNNING 落库后第三次即见 CANCELLED → 不再写 SUCCESS
        when(taskMapper.selectById(1L))
                .thenReturn(task(ExcelExportTask.STATUS_PENDING, 7L))
                .thenReturn(task(ExcelExportTask.STATUS_PENDING, 7L))
                .thenReturn(task(ExcelExportTask.STATUS_CANCELLED, 7L));

        service.executeTask(1L);

        ArgumentCaptor<ExcelExportTask> cap = ArgumentCaptor.forClass(ExcelExportTask.class);
        verify(taskMapper, times(1)).updateById(cap.capture());
        assertEquals(ExcelExportTask.STATUS_RUNNING, cap.getValue().getStatus());
        assertEquals(ExcelExportTask.STATUS_RUNNING, cap.getAllValues().get(0).getStatus());
    }

    @Test
    @DisplayName("已取消任务在开始前直接跳过（零状态写入）")
    void executeTaskSkipsAlreadyCancelledTask() {
        when(taskMapper.selectById(1L)).thenReturn(task(ExcelExportTask.STATUS_CANCELLED, 7L));

        service.executeTask(1L);

        verify(taskMapper, never()).updateById(any(ExcelExportTask.class));
    }

    // ---------- P2-10：悬挂任务清扫 ----------

    @Test
    @DisplayName("悬挂任务（重启后残留 PENDING/RUNNING）被清扫为失败")
    void sweepStaleTasksMarksStaleAsFailed() {
        ExcelExportTask stale = task(ExcelExportTask.STATUS_RUNNING, 7L);
        stale.setUpdateTime(java.time.LocalDateTime.now().minusHours(2));
        when(taskMapper.selectList(any())).thenReturn(java.util.List.of(stale));

        service.sweepStaleTasks();

        ArgumentCaptor<ExcelExportTask> cap = ArgumentCaptor.forClass(ExcelExportTask.class);
        verify(taskMapper).updateById(cap.capture());
        assertEquals(ExcelExportTask.STATUS_FAILED, cap.getValue().getStatus());
        assertNotNull(cap.getValue().getErrorMessage());
    }

    @Test
    @DisplayName("本进程仍在执行的导出任务不被清扫（不清扫自己的 Future）")
    void sweepStaleTasksSkipsLocallyRunningTasks() {
        when(currentUserService.getRequiredCurrentUser()).thenReturn(user);
        when(documentService.getById(1L)).thenReturn(doc(false, "报告.xlsx"));
        service.createTask(1L);   // 本进程持有 taskId=1 的 Future
        when(taskMapper.selectList(any())).thenReturn(java.util.List.of(task(ExcelExportTask.STATUS_RUNNING, 7L)));

        service.sweepStaleTasks();

        verify(taskMapper, never()).updateById(any(ExcelExportTask.class));
    }

    // ---------- 下载守卫 ----------

    @Test
    void openResultRejectsIncompleteTask() {
        ExcelExportTask t = task(ExcelExportTask.STATUS_PENDING, 7L);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.openResult(t));
        assertEquals(ErrorCodes.EXCEL_EXPORT_STATE, ex.getCode());
    }

    // ---------- 任务 7：导出文件过期（EXPIRED 状态） ----------

    @Test
    @org.junit.jupiter.api.DisplayName("成功任务超过保留期后被清扫为 EXPIRED")
    void sweepExpiredTasksMarksSuccessAsExpired() {
        ExcelExportTask done = task(ExcelExportTask.STATUS_SUCCESS, 7L);
        done.setFinishTime(java.time.LocalDateTime.now().minusHours(25));
        done.setExpireAt(java.time.LocalDateTime.now().minusHours(1));
        when(taskMapper.selectList(any())).thenReturn(java.util.List.of(done));

        service.sweepExpiredTasks();

        org.mockito.ArgumentCaptor<ExcelExportTask> cap =
                org.mockito.ArgumentCaptor.forClass(ExcelExportTask.class);
        verify(taskMapper).updateById(cap.capture());
        assertEquals(ExcelExportTask.STATUS_EXPIRED, cap.getValue().getStatus());
    }

    @Test
    @org.junit.jupiter.api.DisplayName("未过期任务与近期完成任务不受过期清扫影响")
    void sweepExpiredTasksKeepsFreshTasks() {
        ExcelExportTask fresh = task(ExcelExportTask.STATUS_SUCCESS, 7L);
        fresh.setFinishTime(java.time.LocalDateTime.now().minusHours(1));
        fresh.setExpireAt(java.time.LocalDateTime.now().plusHours(23));
        when(taskMapper.selectList(any())).thenReturn(java.util.List.of(fresh));

        service.sweepExpiredTasks();

        verify(taskMapper, never()).updateById(any(ExcelExportTask.class));
    }

    @Test
    @org.junit.jupiter.api.DisplayName("EXPIRED 任务的下载被拒绝")
    void openResultRejectsExpiredTask() {
        ExcelExportTask t = task(ExcelExportTask.STATUS_EXPIRED, 7L);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.openResult(t));
        assertEquals(ErrorCodes.EXCEL_EXPORT_STATE, ex.getCode());
    }

    // ---------- 任务 7：下载前重新校验文档 view 权限 ----------

    @Test
    @org.junit.jupiter.api.DisplayName("用户失去文档 view 权限后无法下载导出产物")
    void openResultRejectsWhenDocumentViewLost() {
        // 通过 controller 侧守卫验证：getTaskForCurrentUser 正常返回，但文档已不可读
        ExcelExportTask t = task(ExcelExportTask.STATUS_SUCCESS, 7L);
        when(currentUserService.getRequiredCurrentUser()).thenReturn(user);
        when(taskMapper.selectById(1L)).thenReturn(t);
        when(documentService.optionalById(1L)).thenReturn(doc(false, "a.xlsx"));
        ExcelPermissionService permissions = mock(ExcelPermissionService.class);
        doThrow(new BusinessException(ErrorCodes.PERMISSION_DENIED, "无权访问该文档"))
                .when(permissions).requireView(any(), any());
        ExcelExportService guarded = new ExcelExportService(taskMapper, documentService,
                sheetService, currentUserService, exportExecutor,
                new com.argus.rag.common.config.ArgusRagProperties(), permissions);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> guarded.getTaskForCurrentUser(1L));
        assertEquals(ErrorCodes.PERMISSION_DENIED, ex.getCode());
    }

    // ---------- 任务 7：失败任务重试 ----------

    @Test
    @org.junit.jupiter.api.DisplayName("失败任务可由原用户重试，生成新任务")
    void retryFailedTaskCreatesNewTask() {
        when(currentUserService.getRequiredCurrentUser()).thenReturn(user);
        when(taskMapper.selectById(1L)).thenReturn(task(ExcelExportTask.STATUS_FAILED, 7L));
        when(documentService.getById(1L)).thenReturn(doc(false, "报告.xlsx"));

        ExcelExportTask retry = service.retryTask(1L);

        assertNotNull(retry);
        verify(taskMapper, times(1)).insert(any(ExcelExportTask.class));
    }

    @Test
    @org.junit.jupiter.api.DisplayName("只有失败任务可以重试，已完成任务拒绝")
    void retryRejectsNonFailedTask() {
        when(currentUserService.getRequiredCurrentUser()).thenReturn(user);
        when(taskMapper.selectById(1L)).thenReturn(task(ExcelExportTask.STATUS_SUCCESS, 7L));
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.retryTask(1L));
        assertEquals(ErrorCodes.EXCEL_EXPORT_STATE, ex.getCode());
    }
}
