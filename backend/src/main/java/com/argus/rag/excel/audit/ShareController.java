package com.argus.rag.excel.audit;

import com.argus.rag.common.api.ApiResponse;
import com.argus.rag.excel.service.ShareLinkService;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 分享链接控制器。
 * <p>薄层：仅做参数绑定与响应组装，SQL 持久化与财务门控下沉到 {@link ShareLinkService}。</p>
 */
@RestController
@RequestMapping("/api/excel/document/{documentId}/share")
@Tag(name = "分享链接管理", description = "生成/撤销/解析分享链接（DB 持久化 + 7天过期）")
public class ShareController {

    private final ShareLinkService shareLinkService;

    public ShareController(ShareLinkService shareLinkService) {
        this.shareLinkService = shareLinkService;
    }

    @PostMapping
    public ApiResponse<Map<String, String>> createShare(
            @PathVariable Long documentId,
            @RequestParam(defaultValue = "VIEWER") String role) {
        ShareLinkService.ShareLink share = shareLinkService.createShare(documentId, role);
        return ApiResponse.success(Map.of("token", share.token(), "role", share.role()));
    }

    @DeleteMapping("/{token}")
    public ApiResponse<String> revokeShare(@PathVariable Long documentId,
                                           @PathVariable String token) {
        shareLinkService.revokeShare(documentId, token);
        return ApiResponse.success("分享链接已撤销");
    }

    @GetMapping("/{token}")
    public ApiResponse<Map<String, Object>> resolveShare(@PathVariable String token) {
        ShareLinkService.ShareResolved resolved = shareLinkService.resolveShare(token);
        return ApiResponse.success(Map.of(
                "documentId", resolved.documentId(),
                "role", resolved.role()
        ));
    }
}