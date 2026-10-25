package com.argus.rag.excel.service;

import com.argus.rag.auth.CurrentUserService;
import com.argus.rag.common.enums.SystemRole;
import com.argus.rag.common.exception.BusinessException;
import com.argus.rag.common.exception.ErrorCodes;
import com.argus.rag.excel.audit.OperationLog;
import com.argus.rag.excel.entity.ExcelDocument;
import com.argus.rag.excel.entity.ExcelDocumentPermission;
import com.argus.rag.excel.mapper.ExcelDocumentPermissionMapper;
import com.argus.rag.excel.mapper.OperationLogMapper;
import com.argus.rag.excel.model.dto.BatchCountResult;
import com.argus.rag.excel.model.dto.BatchOperationResult;
import com.argus.rag.excel.model.dto.BatchRejectEntry;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 回收站：恢复、批量彻底删除、一键清空，并级联清理 Sheet / Chunk / 版本 / ACL / 操作日志。
 *
 * <p><b>软删与硬删的分界线</b>（06 篇"后悔药"第三件套）：移入回收站只是把
 * status 改成 2（软删，ExcelDocumentService 里能看到状态机）；在回收站里
 * 点"彻底删除"才走到本类的硬删——把一个文档在六张表里的痕迹全部抹掉
 * （主表、Sheet、分块、版本三件套、ACL、操作日志），且整批包在事务里，
 * 删一半失败会整体回滚，绝不留"幽灵文档"。</p>
 *
 * <p><b>批量操作的结果结构</b>（BatchOperationResult）：彻底删除逐个尝试，
 * 单个失败不影响其他，最后返回"成功几个 + 失败清单（带原因）"——
 * 批量操作的 UX 铁律：能做的做完，做不了的讲清楚，而不是一个失败全部回滚。</p>
 */
@Service
public class RecycleBinService {

    /** 回收站文档保留期（仅用于展示）。 */
    public static final int RETENTION_DAYS = 30;

    private final ExcelDocumentService documentService;
    private final ExcelSheetService sheetService;
    private final ExcelVersionService versionService;
    private final ExcelDocumentPermissionMapper permissionMapper;
    private final OperationLogMapper operationLogMapper;

    public RecycleBinService(ExcelDocumentService documentService,
                             ExcelSheetService sheetService,
                             ExcelVersionService versionService,
                             ExcelDocumentPermissionMapper permissionMapper,
                             OperationLogMapper operationLogMapper) {
        this.documentService = documentService;
        this.sheetService = sheetService;
        this.versionService = versionService;
        this.permissionMapper = permissionMapper;
        this.operationLogMapper = operationLogMapper;
    }

    public Page<ExcelDocument> listRecycleByPage(int pageNum, int pageSize,
                                                 String keyword,
                                                 CurrentUserService.CurrentUser user) {
        Page<ExcelDocument> page = new Page<>(Math.max(pageNum, 1), Math.max(pageSize, 1));
        QueryWrapper<ExcelDocument> qw = new QueryWrapper<>();
        qw.eq("status", 2);
        // 财务/领导可看到回收站中的财务文件；普通用户与系统管理员均不可见。
        if (!CurrentUserService.isFinanceUser(user)) {
            qw.eq("finance_required", false);
        }
        if (keyword != null && !keyword.isBlank()) {
            qw.like("name", keyword.trim());
        }
        if (user != null && user.systemRole() != SystemRole.ADMIN) {
            qw.eq("creator_id", String.valueOf(user.userId()));
        }
        qw.orderByDesc("deleted_at").orderByDesc("id");
        return documentService.pageList(page, qw);
    }

    @Transactional(rollbackFor = Exception.class)
    public BatchOperationResult batchRestore(List<Long> ids) {
        List<Long> validated = validateIds(ids);
        List<Long> success = new ArrayList<>();
        List<BatchRejectEntry> rejected = new ArrayList<>();
        for (Long id : validated) {
            try {
                documentService.restoreFromTrash(id);
                success.add(id);
            } catch (Exception e) {
                rejected.add(reject(id, e.getMessage() == null ? "恢复失败" : e.getMessage()));
            }
        }
        return result(validated, success, rejected);
    }

    @Transactional(rollbackFor = Exception.class)
    public BatchOperationResult batchPurge(List<Long> ids) {
        List<Long> validated = validateIds(ids);
        List<Long> success = new ArrayList<>();
        List<BatchRejectEntry> rejected = new ArrayList<>();
        for (Long id : validated) {
            try {
                purgeOne(id);
                success.add(id);
            } catch (Exception e) {
                rejected.add(reject(id, e.getMessage() == null ? "彻底删除失败" : e.getMessage()));
            }
        }
        return result(validated, success, rejected);
    }

    @Transactional(rollbackFor = Exception.class)
    public BatchCountResult clearRecycle(CurrentUserService.CurrentUser operator) {
        QueryWrapper<ExcelDocument> qw = new QueryWrapper<>();
        qw.eq("status", 2);
        if (!CurrentUserService.isFinanceUser(operator)) {
            qw.eq("finance_required", false);
        }
        if (operator != null && operator.systemRole() != SystemRole.ADMIN) {
            qw.eq("creator_id", String.valueOf(operator.userId()));
        }
        qw.select("id");
        List<Long> ids = documentService.listIds(qw);
        if (ids.isEmpty()) {
            return new BatchCountResult(0, 0, 0);
        }
        BatchOperationResult inner = batchPurge(ids);
        return new BatchCountResult(ids.size(), inner.successCount(), inner.rejectedCount());
    }

    @Transactional(rollbackFor = Exception.class)
    public void purgeOne(Long documentId) {
        ExcelDocument doc = documentService.optionalById(documentId);
        if (doc == null) return;
        if (doc.getStatus() == null || doc.getStatus() != 2) {
            throw new BusinessException(ErrorCodes.EXCEL_TRASH_STATE, "只有回收站内的文档才能彻底删除");
        }
        versionService.deleteByDocumentId(documentId);
        sheetService.hardDeleteByDocumentId(documentId);
        permissionMapper.delete(new QueryWrapper<ExcelDocumentPermission>()
                .eq("document_id", documentId));
        operationLogMapper.delete(new QueryWrapper<OperationLog>()
                .eq("document_id", documentId));
        documentService.purge(documentId);
    }

    /**
     * 任务 7：回收站保留期定时清理。
     * <p>软删除超过 {@link #RETENTION_DAYS} 天（且无保留标记：finance/管理员未加锁的普通过期文档）
     * 的回收站文档自动彻底删除，级联清理 Sheet/Chunk/版本/ACL/日志。
     * 清理范围与手动清理一致：非财务用户视角只清非财务文件，财务文件仅财务/领导可清。</p>
     * <p>当前系统没有「保留标记」字段，所有过期文档一律清理；后续若要支持法务留存，
     * 加 retain 标记列并在查询中排除即可。</p>
     */
    @org.springframework.scheduling.annotation.Scheduled(
            fixedDelayString = "${argus.rag.excel.recycle.purge-interval-ms:3600000}",
            initialDelayString = "${argus.rag.excel.recycle.purge-initial-delay-ms:180000}")
    public void purgeExpiredTrash() {
        java.time.LocalDateTime deadline = java.time.LocalDateTime.now().minusDays(RETENTION_DAYS);
        try {
            List<Long> expiredIds = documentService.listIds(new QueryWrapper<ExcelDocument>()
                    .eq("status", 2)
                    .isNotNull("deleted_at")
                    .lt("deleted_at", deadline)
                    .eq("finance_required", false)
                    .select("id"));
            for (Long id : expiredIds) {
                try {
                    purgeOne(id);
                } catch (Exception e) {
                    org.slf4j.LoggerFactory.getLogger(RecycleBinService.class)
                            .warn("回收站过期清理失败: docId={}, error={}", id, e.getMessage());
                }
            }
            if (!expiredIds.isEmpty()) {
                org.slf4j.LoggerFactory.getLogger(RecycleBinService.class)
                        .info("回收站保留期清理完成: count={}", expiredIds.size());
            }
        } catch (Exception e) {
            org.slf4j.LoggerFactory.getLogger(RecycleBinService.class)
                    .warn("回收站保留期清理任务失败: {}", e.getMessage());
        }
    }

    private List<Long> validateIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) throw new BusinessException("ids 不能为空");
        return ids.stream().filter(Objects::nonNull).distinct().toList();
    }

    private BatchOperationResult result(List<Long> ids, List<Long> success,
                                        List<BatchRejectEntry> rejected) {
        return new BatchOperationResult(ids.size(), success.size(), rejected.size(),
                success, rejected);
    }

    private BatchRejectEntry reject(Long id, String reason) {
        return new BatchRejectEntry(id, reason);
    }
}
