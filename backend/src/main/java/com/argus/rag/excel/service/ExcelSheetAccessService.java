package com.argus.rag.excel.service;

import com.argus.rag.auth.CurrentUserService;
import com.argus.rag.common.exception.ForbiddenException;
import com.argus.rag.excel.entity.ExcelSheet;
import com.argus.rag.excel.mapper.ExcelSheetMapper;
import org.springframework.stereotype.Service;

/**
 * 工作表（Sheet）级访问校验的唯一入口。
 * <p>
 * Excel 的 URL 同时携带工作簿 ID 和工作表 ID，但两者来自客户端、可以任意组合。
 * 只校验工作簿权限会造成“持有 A 工作簿查看权，却能读写挂在 A 路由下的 B 工作表”
 * 的越权（IDOR）。本服务强制两步校验：
 * <ol>
 *   <li>Sheet 必须真实存在且归属于该工作簿（{@code sheet.documentId == workbookId}）；</li>
 *   <li>用户必须拥有该工作簿的对应权限（委托 {@link ExcelPermissionService}，含财务隔离）。</li>
 * </ol>
 * 任何 Controller / WebSocket / Service 层的 Sheet 级操作都应经由本类，不得自行查询后判断。
 */
@Service
public class ExcelSheetAccessService {

    private final ExcelSheetMapper sheetMapper;
    private final ExcelPermissionService permissionService;

    public ExcelSheetAccessService(ExcelSheetMapper sheetMapper,
                                   ExcelPermissionService permissionService) {
        this.sheetMapper = sheetMapper;
        this.permissionService = permissionService;
    }

    /**
     * 断言当前用户可以查看工作簿下的指定工作表，否则抛出 403。
     */
    public void requireSheetView(Long workbookId, Long sheetId,
                                 CurrentUserService.CurrentUser user) {
        requireSheetPermission(workbookId, sheetId, user, false);
    }

    /**
     * 断言当前用户可以编辑工作簿下的指定工作表，否则抛出 403。
     */
    public void requireSheetEdit(Long workbookId, Long sheetId,
                                 CurrentUserService.CurrentUser user) {
        requireSheetPermission(workbookId, sheetId, user, true);
    }

    private void requireSheetPermission(Long workbookId, Long sheetId,
                                        CurrentUserService.CurrentUser user,
                                        boolean edit) {
        ExcelSheet sheet = sheetId == null ? null : sheetMapper.selectById(sheetId);
        // Sheet 不存在、已删除或挂错工作簿，一律按无权处理，不泄露存在性。
        if (workbookId == null || sheet == null
                || !workbookId.equals(sheet.getDocumentId())) {
            throw new ForbiddenException("没有访问该工作表的权限");
        }
        boolean allowed = edit
                ? permissionService.canEdit(workbookId, user)
                : permissionService.canView(workbookId, user);
        if (!allowed) {
            throw new ForbiddenException("没有访问该工作表的权限");
        }
    }
}
