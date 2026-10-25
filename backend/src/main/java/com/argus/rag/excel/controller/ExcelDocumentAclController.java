package com.argus.rag.excel.controller;

import com.argus.rag.common.api.ApiResponse;
import com.argus.rag.excel.model.dto.AclGrantRequest;
import com.argus.rag.excel.model.vo.AclEntryVO;
import com.argus.rag.excel.model.vo.GrantResultVO;
import com.argus.rag.excel.model.vo.TransferOwnerVO;
import com.argus.rag.excel.service.DocumentAccessService;
import com.argus.rag.excel.service.ExcelPermissionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 文档分享与所有权（ACL）。
 */
@Tag(name = "文档分享")
@RestController
@RequestMapping("/api/excel/document")
public class ExcelDocumentAclController {

    private final DocumentAccessService accessService;
    private final ExcelPermissionService permissionService;

    public ExcelDocumentAclController(DocumentAccessService accessService,
                                      ExcelPermissionService permissionService) {
        this.accessService = accessService;
        this.permissionService = permissionService;
    }

    @GetMapping("/{id}/acl")
    @Operation(summary = "查看文档分享列表")
    public ApiResponse<List<AclEntryVO>> listAcl(@PathVariable Long id) {
        permissionService.requireManage(id);
        return ApiResponse.success(accessService.listAcl(id));
    }

    @PutMapping("/{id}/acl/share")
    @Operation(summary = "分享文档")
    public ApiResponse<GrantResultVO> share(@PathVariable Long id,
                                                 @RequestBody AclGrantRequest request) {
        permissionService.requireManage(id);
        return ApiResponse.success(accessService.grant(id, request));
    }

    @DeleteMapping("/{id}/acl/{userId}")
    @Operation(summary = "移除文档分享")
    public ApiResponse<String> revoke(@PathVariable Long id,
                                     @PathVariable Long userId) {
        permissionService.requireManage(id);
        accessService.revoke(id, userId);
        return ApiResponse.success("已移除分享");
    }

    @PutMapping("/{id}/transfer")
    @Operation(summary = "转移文档所有权")
    public ApiResponse<TransferOwnerVO> transfer(@PathVariable Long id,
                                                    @RequestBody Map<String, Object> body) {
        permissionService.requireManage(id);
        Object userIdValue = body.get("userId") == null ? body.get("ownerId") : body.get("userId");
        Long userId = userIdValue == null ? null : Long.parseLong(userIdValue.toString());
        return ApiResponse.success(accessService.transferOwner(id, userId));
    }
}
