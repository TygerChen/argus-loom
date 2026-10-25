package com.argus.rag.excel.service;

import com.argus.rag.common.exception.BusinessException;
import com.argus.rag.common.exception.ErrorCodes;
import com.argus.rag.excel.entity.ExcelDocument;
import com.argus.rag.excel.mapper.ExcelDocumentMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Excel 文档主表服务：CRUD、回收站状态迁移、版本号与可见性。
 *
 * <p><b>status 状态机</b>（贯穿回收站模块，06 篇）：
 * 1 = 正常 → 2 = 在回收站（可还原）→ 3 = 已删除（软删标记，
 * 所有查询接口都把 3 当"不存在"处理——数据还在数据库里，
 * 这是"回收站"能找回的根本原因）。</p>
 *
 * <p><b>version 字段</b>是协同编辑乐观锁的计数器（05 篇）：
 * 每次成功编辑 +1，编辑请求带的 baseRevision 与它对不上就报
 * REVISION_CONFLICT。bumpVersion 是唯一的推进入口。</p>
 */
@Service
public class ExcelDocumentService {

    private static final Logger log = LoggerFactory.getLogger(ExcelDocumentService.class);

    private final ExcelDocumentMapper documentMapper;

    public ExcelDocumentService(ExcelDocumentMapper documentMapper) {
        this.documentMapper = documentMapper;
    }

    @Transactional(rollbackFor = Exception.class)
    public ExcelDocument create(ExcelDocument doc) {
        doc.setStatus(1);
        doc.setVersion(1L);
        doc.setSheetCount(0);
        documentMapper.insert(doc);
        return doc;
    }

    @Transactional(rollbackFor = Exception.class)
    public void updateSheetMeta(Long docId, int sheetCount, String sheetNames) {
        ExcelDocument exist = documentMapper.selectById(docId);
        if (exist == null) return;
        ExcelDocument update = new ExcelDocument();
        update.setId(docId);
        update.setVersion(exist.getVersion());
        update.setSheetCount(sheetCount);
        update.setSheetNames(sheetNames);
        update.setUpdateTime(LocalDateTime.now());
        documentMapper.updateById(update);
    }

    /** 严格读取：不存在或已软删（status=3）一律抛业务异常——面向"必须存在"的业务路径。 */
    public ExcelDocument getById(Long id) {
        ExcelDocument doc = documentMapper.selectById(id);
        if (doc == null || (doc.getStatus() != null && doc.getStatus() == 3)) {
            throw new BusinessException(ErrorCodes.EXCEL_NOT_FOUND, "文档不存在");
        }
        return doc;
    }

    /** 宽松读取：拿不到就返回 null，调用方自行决定怎么处理——面向"可有可无"的检查路径。 */
    public ExcelDocument optionalById(Long id) {
        return id == null ? null : documentMapper.selectById(id);
    }

    public Page<ExcelDocument> listByPage(int pageNum, int pageSize) {
        Page<ExcelDocument> page = new Page<>(pageNum, pageSize);
        QueryWrapper<ExcelDocument> qw = new QueryWrapper<>();
        qw.eq("status", 1).orderByDesc("update_time");
        return documentMapper.selectPage(page, qw);
    }

    /** 通用分页查询入口（回收站 / 运营中心等模块复用）。 */
    public Page<ExcelDocument> pageList(Page<ExcelDocument> page, QueryWrapper<ExcelDocument> qw) {
        return documentMapper.selectPage(page, qw);
    }

    /** 按查询条件仅返回 ID 列表。 */
    public List<Long> listIds(QueryWrapper<ExcelDocument> qw) {
        List<Long> ids = new ArrayList<>();
        for (Object value : documentMapper.selectObjs(qw)) {
            if (value instanceof Number number) ids.add(number.longValue());
        }
        return ids;
    }

    /** 当前用户可访问列表：财务角色可见全部财务文件及本人/共享文件；普通用户仅见非财务文件。 */
    public Page<ExcelDocument> listAccessibleByPage(int pageNum, int pageSize,
                                                     Long userId, boolean admin,
                                                     List<Long> sharedDocumentIds,
                                                     boolean financeUser) {
        Page<ExcelDocument> page = new Page<>(Math.max(pageNum, 1), Math.max(pageSize, 1));
        QueryWrapper<ExcelDocument> qw = new QueryWrapper<>();
        qw.eq("status", 1);
        if (financeUser) {
            // 财务角色：全部财务文件 + 本人创建/被分享的普通文件。
            qw.and(wrapper -> wrapper.eq("finance_required", true)
                    .or(ordinary -> ordinary.eq("finance_required", false)
                            .and(own -> {
                                own.eq("creator_id", String.valueOf(userId));
                                if (sharedDocumentIds != null && !sharedDocumentIds.isEmpty()) {
                                    own.or().in("id", sharedDocumentIds);
                                }
                            })));
        } else {
            qw.eq("finance_required", false);
            if (!admin) {
                qw.and(wrapper -> {
                    wrapper.eq("creator_id", String.valueOf(userId));
                    if (sharedDocumentIds != null && !sharedDocumentIds.isEmpty()) {
                        wrapper.or().in("id", sharedDocumentIds);
                    }
                });
            }
        }
        qw.orderByDesc("update_time");
        return documentMapper.selectPage(page, qw);
    }

    @Transactional(rollbackFor = Exception.class)
    public void moveToTrash(Long id, String operator) {
        ExcelDocument exist = documentMapper.selectById(id);
        if (exist == null || Integer.valueOf(3).equals(exist.getStatus())) {
            throw new BusinessException(ErrorCodes.EXCEL_NOT_FOUND, "文档不存在或已永久删除");
        }
        if (Integer.valueOf(2).equals(exist.getStatus())) {
            throw new BusinessException(ErrorCodes.EXCEL_TRASH_STATE, "文档已移入回收站，不能重复操作");
        }
        ExcelDocument update = new ExcelDocument();
        update.setId(id);
        update.setVersion(exist.getVersion());
        update.setStatus(2);
        update.setDeletedBy(operator);
        update.setDeletedAt(LocalDateTime.now());
        update.setUpdateTime(LocalDateTime.now());
        documentMapper.updateById(update);
    }

    /** 兼容旧调用：单文档删除 = 移入回收站（不物理清除）。 */
    public void delete(Long id) {
        moveToTrash(id, "system");
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> batchMoveToTrash(List<Long> ids, String operator) {
        if (ids == null || ids.isEmpty()) throw new BusinessException("ids 不能为空");
        List<Long> successIds = new ArrayList<>();
        List<Map<String, Object>> rejected = new ArrayList<>();
        for (Long id : ids) {
            try {
                ExcelDocument exist = documentMapper.selectById(id);
                if (exist == null || Integer.valueOf(3).equals(exist.getStatus())) {
                    rejected.add(reject(id, "文档不存在或已永久删除"));
                    continue;
                }
                if (Integer.valueOf(2).equals(exist.getStatus())) {
                    rejected.add(reject(id, "文档已在回收站中"));
                    continue;
                }
                ExcelDocument update = new ExcelDocument();
                update.setId(id);
                update.setVersion(exist.getVersion());
                update.setStatus(2);
                update.setDeletedBy(operator);
                update.setDeletedAt(LocalDateTime.now());
                update.setUpdateTime(LocalDateTime.now());
                documentMapper.updateById(update);
                successIds.add(id);
            } catch (Exception e) {
                rejected.add(reject(id, e.getMessage() == null ? "移入回收站失败" : e.getMessage()));
            }
        }
        return operationResult(ids, successIds, rejected);
    }

    @Transactional(rollbackFor = Exception.class)
    public void restoreFromTrash(Long id) {
        ExcelDocument exist = documentMapper.selectById(id);
        if (exist == null || Integer.valueOf(3).equals(exist.getStatus())) {
            throw new BusinessException(ErrorCodes.EXCEL_NOT_FOUND, "文档不存在或已永久删除");
        }
        if (!Integer.valueOf(2).equals(exist.getStatus())) {
            throw new BusinessException(ErrorCodes.EXCEL_TRASH_STATE, "文档不在回收站中");
        }
        ExcelDocument update = new ExcelDocument();
        update.setId(id);
        update.setVersion(exist.getVersion());
        update.setStatus(1);
        update.setDeletedBy(null);
        update.setDeletedAt(null);
        update.setUpdateTime(LocalDateTime.now());
        if (documentMapper.updateById(update) == 0) {
            throw new BusinessException(ErrorCodes.CONCURRENT_MODIFIED, "文档已被其他操作修改，请刷新后重试");
        }
    }

    /** 彻底删除：状态置墓碑 3，物理文件清理。Sheet/Chunk/版本由调用方级联。 */
    @Transactional(rollbackFor = Exception.class)
    public void purge(Long id) {
        ExcelDocument exist = documentMapper.selectById(id);
        if (exist == null) return;
        LambdaUpdateWrapper<ExcelDocument> uw = new LambdaUpdateWrapper<>();
        uw.eq(ExcelDocument::getId, id)
                .set(ExcelDocument::getStatus, 3)
                .set(ExcelDocument::getUpdateTime, LocalDateTime.now());
        documentMapper.update(null, uw);
        if (exist.getFilePath() != null && !exist.getFilePath().isBlank()) {
            try {
                Files.deleteIfExists(Path.of(exist.getFilePath()));
            } catch (Exception ignored) {
            }
        }
    }

    public void rename(Long id, String newName) {
        ExcelDocument exist = documentMapper.selectById(id);
        if (exist == null) throw new BusinessException(ErrorCodes.EXCEL_NOT_FOUND, "文档不存在");
        ExcelDocument update = new ExcelDocument();
        update.setId(id);
        update.setVersion(exist.getVersion());
        update.setName(newName);
        update.setUpdateTime(LocalDateTime.now());
        documentMapper.updateById(update);
    }

    /** 转移文档所有者：仅改写 creatorId，ACL 维护由调用方完成。 */
    @Transactional(rollbackFor = Exception.class)
    public void transferOwner(Long id, Long newOwnerId) {
        ExcelDocument exist = documentMapper.selectById(id);
        if (exist == null) throw new BusinessException(ErrorCodes.EXCEL_NOT_FOUND, "文档不存在");
        ExcelDocument update = new ExcelDocument();
        update.setId(id);
        update.setVersion(exist.getVersion());
        update.setCreatorId(String.valueOf(newOwnerId));
        update.setUpdateTime(LocalDateTime.now());
        documentMapper.updateById(update);
    }

    /** 结构保存 / 版本回滚 / 恢复后推进文档版本号。 */
    @Transactional(rollbackFor = Exception.class)
    public long bumpVersion(Long id) {
        ExcelDocument exist = documentMapper.selectById(id);
        if (exist == null) throw new BusinessException(ErrorCodes.EXCEL_NOT_FOUND, "文档不存在");
        ExcelDocument update = new ExcelDocument();
        update.setId(id);
        update.setVersion(exist.getVersion() == null ? 1L : exist.getVersion());
        documentMapper.updateById(update);
        ExcelDocument refreshed = documentMapper.selectById(id);
        return refreshed == null || refreshed.getVersion() == null ? 1L : refreshed.getVersion();
    }

    public void deleteFileQuietly(Long id) {
        try {
            ExcelDocument exist = documentMapper.selectById(id);
            if (exist != null && exist.getFilePath() != null) {
                Files.deleteIfExists(Path.of(exist.getFilePath()));
            }
        } catch (Exception ignored) {
        }
    }

    private Map<String, Object> operationResult(List<Long> ids, List<Long> successIds,
                                                List<Map<String, Object>> rejected) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", ids.size());
        result.put("successCount", successIds.size());
        result.put("rejectedCount", rejected.size());
        result.put("successIds", successIds);
        result.put("rejected", rejected);
        return result;
    }

    private Map<String, Object> reject(Long id, String reason) {
        Map<String, Object> entry = new HashMap<>();
        entry.put("id", id);
        entry.put("reason", reason);
        return entry;
    }
}
