package com.argus.rag.excel.service;

import com.argus.rag.excel.entity.ExcelAuditLog;
import com.argus.rag.excel.entity.ExcelCollabLog;
import com.argus.rag.excel.entity.ExcelCollabRoom;
import com.argus.rag.excel.entity.ExcelDocument;
import com.argus.rag.excel.entity.ExcelDocumentVersion;
import com.argus.rag.excel.entity.ExcelExportTask;
import com.argus.rag.excel.mapper.ExcelAuditLogMapper;
import com.argus.rag.excel.mapper.ExcelCollabLogMapper;
import com.argus.rag.excel.mapper.ExcelCollabRoomMapper;
import com.argus.rag.excel.mapper.ExcelDocumentMapper;
import com.argus.rag.excel.mapper.ExcelDocumentVersionMapper;
import com.argus.rag.excel.mapper.ExcelExportTaskMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 运营中心后台查询：审计日志、协同日志、在线房间、版本策略与概览。
 */
@Service
public class OperationAdminService {

    private final ExcelAuditLogMapper auditLogMapper;
    private final ExcelCollabLogMapper collabLogMapper;
    private final ExcelCollabRoomMapper collabRoomMapper;
    private final ExcelDocumentMapper documentMapper;
    private final ExcelDocumentVersionMapper versionMapper;
    private final ExcelExportTaskMapper exportTaskMapper;

    public OperationAdminService(ExcelAuditLogMapper auditLogMapper,
                                 ExcelCollabLogMapper collabLogMapper,
                                 ExcelCollabRoomMapper collabRoomMapper,
                                 ExcelDocumentMapper documentMapper,
                                 ExcelDocumentVersionMapper versionMapper,
                                 ExcelExportTaskMapper exportTaskMapper) {
        this.auditLogMapper = auditLogMapper;
        this.collabLogMapper = collabLogMapper;
        this.collabRoomMapper = collabRoomMapper;
        this.documentMapper = documentMapper;
        this.versionMapper = versionMapper;
        this.exportTaskMapper = exportTaskMapper;
    }

    public Map<String, Object> summary() {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("docCount", documentMapper.selectCount(new QueryWrapper<ExcelDocument>().eq("status", 1)));
        summary.put("recycleCount", documentMapper.selectCount(new QueryWrapper<ExcelDocument>().eq("status", 2)));
        summary.put("versionCount", versionMapper.selectCount(null));
        summary.put("collabLogCount", collabLogMapper.selectCount(null));
        summary.put("onlineRoomCount", collabRoomMapper.selectCount(null));
        summary.put("exportTaskCount", exportTaskMapper.selectCount(null));
        summary.put("auditLogCount", auditLogMapper.selectCount(null));
        return summary;
    }

    public Page<ExcelAuditLog> pageAudit(int pageNum, int pageSize,
                                         String username, String action, String targetType,
                                         LocalDateTime start, LocalDateTime end) {
        Page<ExcelAuditLog> page = new Page<>(Math.max(pageNum, 1), Math.max(pageSize, 1));
        QueryWrapper<ExcelAuditLog> qw = new QueryWrapper<>();
        if (username != null && !username.isBlank()) qw.like("username", username.trim());
        if (action != null && !action.isBlank()) qw.eq("action", action.trim());
        if (targetType != null && !targetType.isBlank()) qw.eq("target_type", targetType.trim());
        if (start != null) qw.ge("create_time", start);
        if (end != null) qw.le("create_time", end);
        qw.orderByDesc("id");
        return auditLogMapper.selectPage(page, qw);
    }

    public Page<ExcelCollabLog> pageCollabLog(int pageNum, int pageSize,
                                               Long documentId, String username,
                                               Boolean merged, Boolean conflict) {
        Page<ExcelCollabLog> page = new Page<>(Math.max(pageNum, 1), Math.max(pageSize, 1));
        QueryWrapper<ExcelCollabLog> qw = new QueryWrapper<>();
        if (documentId != null) qw.eq("document_id", documentId);
        if (username != null && !username.isBlank()) qw.like("actor_name", username.trim());
        if (merged != null) qw.eq("merged", merged ? 1 : 0);
        if (conflict != null) qw.eq("conflict", conflict ? 1 : 0);
        qw.orderByDesc("id");
        return collabLogMapper.selectPage(page, qw);
    }

    public Page<ExcelCollabRoom> pageRooms(int pageNum, int pageSize,
                                            Long documentId, String username) {
        Page<ExcelCollabRoom> page = new Page<>(Math.max(pageNum, 1), Math.max(pageSize, 1));
        QueryWrapper<ExcelCollabRoom> qw = new QueryWrapper<>();
        if (documentId != null) qw.eq("document_id", documentId);
        if (username != null && !username.isBlank()) qw.like("actor_name", username.trim());
        qw.orderByDesc("update_time");
        return collabRoomMapper.selectPage(page, qw);
    }

    public Map<String, Object> roomView(ExcelCollabRoom room) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", room.getId());
        view.put("documentId", room.getDocumentId());
        view.put("actorId", room.getActorId());
        view.put("actorName", room.getActorName());
        view.put("sessionId", room.getSessionId());
        view.put("canEdit", room.getCanEdit());
        view.put("sheetId", room.getSheetId());
        view.put("cursorRow", room.getCursorRow());
        view.put("cursorCol", room.getCursorCol());
        view.put("createTime", room.getCreateTime());
        view.put("updateTime", room.getUpdateTime());
        return view;
    }

    public void kickRoom(Long roomId) {
        if (roomId == null) return;
        collabRoomMapper.deleteById(roomId);
    }

    public Map<String, Object> versionStats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("versionCount", versionMapper.selectCount(null));
        stats.put("versionRows", versionMapper.selectCount(new QueryWrapper<ExcelDocumentVersion>()
                .isNotNull("cell_count")));
        return stats;
    }
}
