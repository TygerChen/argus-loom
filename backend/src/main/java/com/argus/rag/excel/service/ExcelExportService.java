package com.argus.rag.excel.service;

import com.argus.rag.auth.CurrentUserService;
import com.argus.rag.common.enums.SystemRole;
import com.argus.rag.common.exception.BusinessException;
import com.argus.rag.common.exception.ErrorCodes;
import com.argus.rag.common.util.JsonCompat;
import com.argus.rag.excel.entity.ExcelDocument;
import com.argus.rag.excel.entity.ExcelExportTask;
import com.argus.rag.excel.entity.ExcelSheet;
import com.argus.rag.excel.entity.ExcelSheetChunk;
import com.argus.rag.excel.mapper.ExcelExportTaskMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;

/**
 * 异步导出：把文档 Sheet/Chunk 数据还原为 xlsx，任务状态落库，产物存本地上传目录。
 *
 * <p>P1-06：任务投递到专用的 {@code excelExportExecutor}（有界队列 + CallerRuns，不再占用
 * ForkJoinPool 公共池），并按 taskId 持有 {@link Future}，使 {@code cancelTask} 能真正中断
 * 执行线程；执行期间每个阶段前复查 DB 状态，取消结果不会被后写的 SUCCESS 覆盖。</p>
 * <p>P2-10：任务句柄只存在内存，进程重启后 DB 中残留的 PENDING/RUNNING 任务由定时清扫置为失败，
 * 避免「永久进行中」的悬挂任务。</p>
 */
@Service
public class ExcelExportService {

    private static final Logger log = LoggerFactory.getLogger(ExcelExportService.class);

    private final ExcelExportTaskMapper taskMapper;
    private final ExcelDocumentService documentService;
    private final ExcelSheetService sheetService;
    private final CurrentUserService currentUserService;
    /** 文档权限计算器：下载前重新校验 view 权限（任务 7）；测试构造可为 null 以保持旧行为。 */
    private final ExcelPermissionService permissionService;
    private final ExecutorService exportExecutor;

    /** 运行中任务的句柄（taskId → Future），取消/清扫据此判断与打断。 */
    private final Map<Long, Future<?>> runningFutures = new ConcurrentHashMap<>();

    /**
     * 上传/导出根目录：生产由共享配置 {@code argus.rag.excel.upload-path} 绑定（构造时快照），
     * 单元测试可反射重定向到临时目录（字段非 final，供既有测试沿用）。
     */
    private String uploadPath;

    /** 悬挂任务判定阈值（分钟）：超过该时长未更新的 PENDING/RUNNING 视为中断遗留。 */
    @Value("${argus.rag.excel.export.stale-minutes:30}")
    private int staleExportMinutes = 30;

    /** 导出产物保留小时数：成功任务超过该时长被清扫为 EXPIRED，产物不再可下载。 */
    @Value("${argus.rag.excel.export.retention-hours:24}")
    private int retentionHours = 24;

    /** 测试/缺省构造：使用默认上传路径（./upload）。 */
    public ExcelExportService(ExcelExportTaskMapper taskMapper,
                              ExcelDocumentService documentService,
                              ExcelSheetService sheetService,
                              CurrentUserService currentUserService,
                              @Qualifier("excelExportExecutor") ExecutorService exportExecutor) {
        this(taskMapper, documentService, sheetService, currentUserService, exportExecutor,
                new com.argus.rag.common.config.ArgusRagProperties(), null);
    }

    /** 生产构造：注入共享配置 bean（上传路径收口于 argus.rag.excel.upload-path）。 */
    public ExcelExportService(ExcelExportTaskMapper taskMapper,
                              ExcelDocumentService documentService,
                              ExcelSheetService sheetService,
                              CurrentUserService currentUserService,
                              @Qualifier("excelExportExecutor") ExecutorService exportExecutor,
                              com.argus.rag.common.config.ArgusRagProperties argusProperties) {
        this(taskMapper, documentService, sheetService, currentUserService, exportExecutor,
                argusProperties, null);
    }

    /** 全参构造：可注入权限计算器（生产 Spring 自动装配；单测按需传 mock）。 */
    @org.springframework.beans.factory.annotation.Autowired
    public ExcelExportService(ExcelExportTaskMapper taskMapper,
                              ExcelDocumentService documentService,
                              ExcelSheetService sheetService,
                              CurrentUserService currentUserService,
                              @Qualifier("excelExportExecutor") ExecutorService exportExecutor,
                              com.argus.rag.common.config.ArgusRagProperties argusProperties,
                              ExcelPermissionService permissionService) {
        this.taskMapper = taskMapper;
        this.documentService = documentService;
        this.sheetService = sheetService;
        this.currentUserService = currentUserService;
        this.permissionService = permissionService;
        this.exportExecutor = exportExecutor;
        this.uploadPath = argusProperties.getUploadPath();
    }

    /**
     * 创建导出任务（06 篇的"餐厅等位取号"）：立刻落库一条 PENDING 任务并返回，
     * 真正的生成在专用线程池异步跑（09 篇线程池隔离：导出再慢也不占业务线程）。
     * runningFutures 里登记的 Future 句柄让 cancelTask 能真正中断执行中的线程——
     * 光改状态不打断，线程还会傻傻跑完几十万行。
     */
    @Transactional(rollbackFor = Exception.class)
    public ExcelExportTask createTask(Long documentId) {
        ExcelDocument doc = documentService.getById(documentId);
        if (doc == null) throw new BusinessException(ErrorCodes.EXCEL_NOT_FOUND, "文档不存在");
        CurrentUserService.CurrentUser user = currentUserService.getRequiredCurrentUser();
        if (Boolean.TRUE.equals(doc.getFinanceRequired())
                && !CurrentUserService.isFinanceUser(user)) {
            throw new BusinessException(ErrorCodes.EXCEL_FINANCE_FORBIDDEN, "无权导出财务文件");
        }
        // 创建与下载同口径：对源文档没有 view 权限（含 ACL 收回后）不允许发起导出任务，
        // 防止任意登录用户对他人文档投递导出、消耗导出线程池并探测文档存在性。
        if (permissionService != null) {
            permissionService.requireView(documentId, user);
        }
        ExcelExportTask task = new ExcelExportTask();
        task.setDocumentId(documentId);
        task.setTaskNo(UUID.randomUUID().toString().replace("-", "").substring(0, 20));
        task.setUserId(user.userId());
        task.setUsername(user.userCode());
        task.setStatus(ExcelExportTask.STATUS_PENDING);
        task.setProgress(0);
        task.setFileName(doc.getName() == null ? "export.xlsx" : doc.getName());
        taskMapper.insert(task);
        // P1-06：投递到导出专用池并登记 Future（不再使用公共 ForkJoinPool）
        submitTask(task.getId());
        return task;
    }

    /** 投递任务到专用线程池；线程池已关闭等极端情况下直接置失败，避免任务永久悬挂。 */
    private void submitTask(Long taskId) {
        try {
            Future<?> future = exportExecutor.submit(() -> executeTask(taskId));
            runningFutures.put(taskId, future);
        } catch (RejectedExecutionException e) {
            log.error("导出任务投递被拒绝: taskId={}", taskId, e);
            markTerminal(taskId, ExcelExportTask.STATUS_FAILED, "系统繁忙，导出任务未能启动，请稍后重试");
        }
    }

    /**
     * 执行导出任务。每个阶段前复查 DB 状态：一旦被取消就停止推进，
     * 且不再写 SUCCESS（避免覆盖取消结果）。
     */
    public void executeTask(Long taskId) {
        Path filePath = null;
        try {
            ExcelExportTask task = taskMapper.selectById(taskId);
            if (task == null) return;
            if (isCancelled(taskId)) {
                log.info("导出任务已取消，跳过执行: taskId={}", taskId);
                return;
            }
            ExcelExportTask running = new ExcelExportTask();
            running.setId(taskId);
            running.setStatus(ExcelExportTask.STATUS_RUNNING);
            running.setProgress(10);
            running.setUpdateTime(LocalDateTime.now());
            taskMapper.updateById(running);

            if (isCancelled(taskId)) {
                log.info("导出任务在写盘前被取消: taskId={}", taskId);
                return;
            }
            filePath = buildWorkbookFile(task.getDocumentId(), task.getTaskNo());

            if (isCancelled(taskId) || Thread.currentThread().isInterrupted()) {
                deleteQuietly(filePath);
                log.info("导出任务在写盘后被取消，产物已清理: taskId={}", taskId);
                return;
            }
            ExcelExportTask update = new ExcelExportTask();
            update.setId(taskId);
            update.setStatus(ExcelExportTask.STATUS_SUCCESS);
            update.setProgress(100);
            update.setObjectKey(task.getTaskNo() + ".xlsx");
            update.setDownloadUrl("/api/excel/export/tasks/" + taskId + "/download");
            update.setExpireAt(LocalDateTime.now().plusHours(Math.max(retentionHours, 1)));
            update.setFinishTime(LocalDateTime.now());
            update.setUpdateTime(LocalDateTime.now());
            taskMapper.updateById(update);
            log.info("导出任务完成: taskId={}, file={}", taskId, filePath);
        } catch (Exception e) {
            if (isCancelled(taskId) || Thread.currentThread().isInterrupted()) {
                deleteQuietly(filePath);
                log.info("导出任务已取消，忽略执行异常: taskId={}, error={}", taskId, e.getMessage());
                return;
            }
            log.error("导出任务失败: taskId={}, error={}", taskId, e.getMessage(), e);
            markTerminal(taskId, ExcelExportTask.STATUS_FAILED,
                    e.getMessage() == null ? "导出失败" : e.getMessage());
        } finally {
            runningFutures.remove(taskId);
        }
    }

    /** 以 DB 状态为准判定取消（取消可能来自其他请求/进程），线程被中断同样视为取消。 */
    private boolean isCancelled(Long taskId) {
        if (Thread.currentThread().isInterrupted()) {
            return true;
        }
        ExcelExportTask current = taskMapper.selectById(taskId);
        return current != null && current.getStatus() != null
                && current.getStatus() == ExcelExportTask.STATUS_CANCELLED;
    }

    /** 落终态（失败/取消）：统一写状态、错误文案与完成时间。 */
    private void markTerminal(Long taskId, int status, String errorMessage) {
        ExcelExportTask update = new ExcelExportTask();
        update.setId(taskId);
        update.setStatus(status);
        if (errorMessage != null) {
            update.setErrorMessage(errorMessage);
        }
        update.setFinishTime(LocalDateTime.now());
        update.setUpdateTime(LocalDateTime.now());
        taskMapper.updateById(update);
    }

    /** 取消后清理半成品文件（失败只记日志，不掩盖取消语义）。 */
    private void deleteQuietly(Path filePath) {
        if (filePath == null) {
            return;
        }
        try {
            Files.deleteIfExists(filePath);
        } catch (Exception e) {
            log.warn("清理被取消任务的导出文件失败: path={}", filePath, e);
        }
    }

    /**
     * P2-10：清扫悬挂导出任务。
     * <p>任务句柄仅存于内存，进程重启后 DB 中的 PENDING/RUNNING 任务不会再有人推进；
     * 超过 {@code argus.rag.excel.export.stale-minutes}（默认 30 分钟）未更新的悬挂任务置为失败，
     * 本进程仍在执行的任务（持有 Future）不动。</p>
     */
    @Scheduled(fixedDelayString = "${argus.rag.excel.export.sweep-interval-ms:300000}",
            initialDelayString = "${argus.rag.excel.export.sweep-initial-delay-ms:60000}")
    public void sweepStaleTasks() {
        LocalDateTime deadline = LocalDateTime.now().minusMinutes(Math.max(staleExportMinutes, 1));
        try {
            List<ExcelExportTask> stale = taskMapper.selectList(new QueryWrapper<ExcelExportTask>()
                    .in("status", ExcelExportTask.STATUS_PENDING, ExcelExportTask.STATUS_RUNNING)
                    .lt("update_time", deadline));
            for (ExcelExportTask task : stale) {
                if (task.getId() == null || runningFutures.containsKey(task.getId())) {
                    continue;
                }
                markTerminal(task.getId(), ExcelExportTask.STATUS_FAILED,
                        "导出任务中断（服务重启或超时），请重新发起导出");
                log.warn("清扫悬挂导出任务: taskId={}", task.getId());
            }
        } catch (Exception e) {
            log.warn("清扫悬挂导出任务失败: {}", e.getMessage());
        }
    }

    public Page<ExcelExportTask> listTasks(int pageNum, int pageSize, String keyword) {
        CurrentUserService.CurrentUser user = currentUserService.getRequiredCurrentUser();
        Page<ExcelExportTask> page = new Page<>(Math.max(pageNum, 1), Math.max(pageSize, 1));
        QueryWrapper<ExcelExportTask> qw = new QueryWrapper<>();
        if (!CurrentUserService.isFinanceUser(user)) {
            qw.inSql("document_id", "SELECT id FROM excel_document WHERE finance_required = FALSE");
        }
        if (user.systemRole() != SystemRole.ADMIN) {
            qw.eq("user_id", user.userId());
        }
        if (keyword != null && !keyword.isBlank()) {
            qw.like("file_name", keyword.trim());
        }
        qw.orderByDesc("id");
        return taskMapper.selectPage(page, qw);
    }

    public ExcelExportTask getTaskForCurrentUser(Long taskId) {
        ExcelExportTask task = taskMapper.selectById(taskId);
        if (task == null) throw new BusinessException(ErrorCodes.EXCEL_NOT_FOUND, "导出任务不存在");
        CurrentUserService.CurrentUser user = currentUserService.getRequiredCurrentUser();
        if (!CurrentUserService.isFinanceUser(user) && isFinanceExportTask(task)) {
            throw new BusinessException(ErrorCodes.PERMISSION_DENIED, "无权访问该导出任务");
        }
        if (user.systemRole() != SystemRole.ADMIN && !user.userId().equals(task.getUserId())) {
            throw new BusinessException(ErrorCodes.PERMISSION_DENIED, "无权访问该导出任务");
        }
        // 任务 7 下载重鉴权：任务归属校验通过后，再确认当前用户对源文档仍有 view 权限。
        // ACL 收回/角色变化后，此前创建的导出产物也不能继续下载（fail-closed）。
        if (permissionService != null) {
            permissionService.requireView(task.getDocumentId(), user);
        }
        return task;
    }

    private boolean isFinanceExportTask(ExcelExportTask task) {
        ExcelDocument doc = documentService.optionalById(task.getDocumentId());
        return doc != null && Boolean.TRUE.equals(doc.getFinanceRequired());
    }

    @Transactional(rollbackFor = Exception.class)
    public ExcelExportTask cancelTask(Long taskId) {
        ExcelExportTask task = getTaskForCurrentUser(taskId);
        if (task.getStatus() == ExcelExportTask.STATUS_SUCCESS) {
            throw new BusinessException(ErrorCodes.EXCEL_EXPORT_STATE, "任务已完成，无法取消");
        }
        ExcelExportTask update = new ExcelExportTask();
        update.setId(taskId);
        update.setStatus(ExcelExportTask.STATUS_CANCELLED);
        update.setErrorMessage("用户取消");
        update.setFinishTime(LocalDateTime.now());
        update.setUpdateTime(LocalDateTime.now());
        taskMapper.updateById(update);
        // P1-06：真正打断正在执行的导出线程（此前只改状态，任务会继续跑完并把状态覆盖成 SUCCESS）。
        Future<?> future = runningFutures.remove(taskId);
        if (future != null) {
            future.cancel(true);
        }
        return taskMapper.selectById(taskId);
    }

    public InputStream openResult(ExcelExportTask task) {
        if (task.getStatus() != ExcelExportTask.STATUS_SUCCESS) {
            throw new BusinessException(ErrorCodes.EXCEL_EXPORT_STATE, "任务尚未完成，无法下载");
        }
        if (task.getExpireAt() != null && task.getExpireAt().isBefore(LocalDateTime.now())) {
            throw new BusinessException(ErrorCodes.EXCEL_EXPORT_STATE, "导出文件已过期，请重新导出");
        }
        try {
            Path path = Paths.get(uploadPath).toAbsolutePath().normalize()
                    .resolve("export").resolve(task.getTaskNo() + ".xlsx");
            if (!Files.exists(path)) throw new BusinessException(ErrorCodes.EXCEL_EXPORT_STATE, "导出文件不存在或已被清理");
            return new FileInputStream(path.toFile());
        } catch (java.io.IOException e) {
            throw new BusinessException(ErrorCodes.EXCEL_EXPORT_STATE, "读取导出文件失败");
        }
    }

    /**
     * 重试失败任务：复用校验逻辑确认任务可见且属于当前用户，仅 FAILED 状态可重试；
     * 重试语义 = 以同一文档新建一个导出任务（保留原任务作为失败记录与审计痕迹）。
     */
    @Transactional(rollbackFor = Exception.class)
    public ExcelExportTask retryTask(Long taskId) {
        ExcelExportTask task = getTaskForCurrentUser(taskId);
        if (task.getStatus() != ExcelExportTask.STATUS_FAILED) {
            throw new BusinessException(ErrorCodes.EXCEL_EXPORT_STATE, "只有失败的任务可以重试");
        }
        return createTask(task.getDocumentId());
    }

    /**
     * 任务 7：导出产物过期清扫。
     * <p>成功任务超过 {@code expire_at} 后置为 EXPIRED 并删除产物文件；
     * 失败/取消/已过期任务不动。产物文件删除失败只记日志（状态已不可下载，文件留待磁盘清理）。</p>
     */
    @Scheduled(fixedDelayString = "${argus.rag.excel.export.expire-sweep-interval-ms:900000}",
            initialDelayString = "${argus.rag.excel.export.expire-sweep-initial-delay-ms:120000}")
    public void sweepExpiredTasks() {
        try {
            List<ExcelExportTask> expired = taskMapper.selectList(new QueryWrapper<ExcelExportTask>()
                    .eq("status", ExcelExportTask.STATUS_SUCCESS)
                    .isNotNull("expire_at")
                    .lt("expire_at", LocalDateTime.now()));
            for (ExcelExportTask task : expired) {
                if (task.getId() == null) {
                    continue;
                }
                // 防御性复查：select 到 update 之间状态可能已变化，且过期时间未到则跳过
                if (task.getExpireAt() == null || task.getExpireAt().isAfter(LocalDateTime.now())) {
                    continue;
                }
                ExcelExportTask update = new ExcelExportTask();
                update.setId(task.getId());
                update.setStatus(ExcelExportTask.STATUS_EXPIRED);
                update.setErrorMessage("导出文件已过期");
                update.setUpdateTime(LocalDateTime.now());
                taskMapper.updateById(update);
                deleteQuietly(buildExportPath(task.getTaskNo()));
                log.info("清扫过期导出任务: taskId={}", task.getId());
            }
        } catch (Exception e) {
            log.warn("清扫过期导出任务失败: {}", e.getMessage());
        }
    }

    private Path buildExportPath(String taskNo) {
        Path dir = Paths.get(uploadPath).toAbsolutePath().normalize().resolve("export");
        try {
            Files.createDirectories(dir);
        } catch (java.io.IOException e) {
            throw new BusinessException(ErrorCodes.EXCEL_EXPORT_STATE, "创建导出目录失败");
        }
        return dir.resolve(taskNo + ".xlsx");
    }

    @SuppressWarnings("unchecked")
    private Path buildWorkbookFile(Long documentId, String taskNo) throws Exception {
        Path filePath = buildExportPath(taskNo);
        try (Workbook workbook = new XSSFWorkbook()) {
            List<ExcelSheet> sheets = sheetService.listSheetsByDocumentId(documentId);
            // P2-07：一次批量取回全文档 chunk（原实现每个 Sheet 查一次，N+1）
            Map<Long, List<ExcelSheetChunk>> chunksBySheet =
                    sheetService.listChunksByDocumentGrouped(documentId);
            for (ExcelSheet sheetEntity : sheets) {
                Sheet sheet = workbook.createSheet(safeSheetName(sheetEntity.getSheetName(), workbook));
                int maxRows = sheetEntity.getTotalRows() == null ? 0 : sheetEntity.getTotalRows();
                int maxCols = sheetEntity.getTotalCols() == null ? 0 : sheetEntity.getTotalCols();
                Map<String, Object>[][] grid = new LinkedHashMap[Math.max(maxRows, 1)][Math.max(maxCols, 1)];
                for (ExcelSheetChunk chunk : chunksBySheet.getOrDefault(sheetEntity.getId(), List.of())) {
                    List<Object> cells = JsonCompat.parseArray(chunk.getCelldataJson());
                    if (cells == null) continue;
                    for (int i = 0; i < cells.size(); i++) {
                        Object item = cells.get(i);
                        if (!(item instanceof Map)) continue;
                        Map<String, Object> cell = (Map<String, Object>) item;
                        int r = ((Number) cell.get("r")).intValue();
                        int c = ((Number) cell.get("c")).intValue();
                        if (r >= 0 && c >= 0 && r < grid.length && c < grid[0].length) {
                            Object vObj = cell.get("v");
                            grid[r][c] = vObj instanceof Map ? (Map<String, Object>) vObj : null;
                        }
                    }
                }
                for (int r = 0; r < grid.length; r++) {
                    Row row = sheet.createRow(r);
                    for (int c = 0; c < grid[r].length; c++) {
                        Map<String, Object> value = grid[r][c];
                        if (value == null) continue;
                        Cell cell = row.createCell(c);
                        Object raw = value.get("v");
                        if (raw == null) raw = value.get("m");
                        if (raw == null) raw = value;
                        writeCell(cell, raw);
                    }
                }
            }
            try (FileOutputStream out = new FileOutputStream(filePath.toFile())) {
                workbook.write(out);
            }
        }
        return filePath;
    }

    private static String safeSheetName(String name, Workbook workbook) {
        String clean = name == null || name.isBlank() ? "Sheet" : name.replaceAll("[\\\\/?*\\[\\]:]", "_");
        clean = clean.length() > 31 ? clean.substring(0, 31) : clean;
        if (workbook.getSheet(clean) != null) clean = clean + "_" + UUID.randomUUID().toString().substring(0, 4);
        return clean;
    }

    private static void writeCell(Cell cell, Object raw) {
        try {
            if (raw == null) {
                cell.setBlank();
            } else if (raw instanceof Number number) {
                cell.setCellValue(number.doubleValue());
            } else if (raw instanceof Boolean bool) {
                cell.setCellValue(bool);
            } else {
                cell.setCellValue(raw.toString());
            }
        } catch (Exception e) {
            cell.setCellValue(raw == null ? "" : raw.toString());
        }
    }
}
