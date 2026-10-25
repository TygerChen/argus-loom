package com.argus.rag.excel.service;

import com.argus.rag.auth.CurrentUserService;
import com.argus.rag.excel.entity.ExcelAuditLog;
import com.argus.rag.excel.mapper.ExcelAuditLogMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 审计日志写入与查询。
 */
@Service
public class ExcelAuditService {

    private static final Logger log = LoggerFactory.getLogger(ExcelAuditService.class);

    private final ExcelAuditLogMapper auditLogMapper;
    private final CurrentUserService currentUserService;
    private final HttpServletRequest request;

    public ExcelAuditService(ExcelAuditLogMapper auditLogMapper,
                             CurrentUserService currentUserService,
                             HttpServletRequest request) {
        this.auditLogMapper = auditLogMapper;
        this.currentUserService = currentUserService;
        this.request = request;
    }

    public void record(String action, String targetType, String targetId, String detail) {
        try {
            var user = currentUserService.getRequiredCurrentUser();
            ExcelAuditLog auditLog = new ExcelAuditLog();
            auditLog.setUserId(user.userId());
            auditLog.setUsername(user.userCode());
            auditLog.setAction(action);
            auditLog.setTargetType(targetType);
            auditLog.setTargetId(targetId);
            auditLog.setDetail(detail == null || detail.length() <= 2000 ? detail
                    : detail.substring(0, 2000));
            auditLog.setIp(clientIp());
            auditLogMapper.insert(auditLog);
        } catch (Exception e) {
            log.warn("审计日志写入失败: action={}, targetId={}", action, targetId, e);
        }
    }

    private String clientIp() {
        try {
            String forwarded = request.getHeader("X-Forwarded-For");
            if (forwarded != null && !forwarded.isBlank()) return forwarded.split(",")[0].trim();
            return request.getRemoteAddr();
        } catch (Exception e) {
            return "unknown";
        }
    }
}
