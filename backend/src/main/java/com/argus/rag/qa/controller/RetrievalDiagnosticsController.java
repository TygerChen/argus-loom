package com.argus.rag.qa.controller;

import com.argus.rag.auth.CurrentUserService;
import com.argus.rag.common.api.ApiResponse;
import com.argus.rag.qa.rag.RetrievalOptions;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 检索参数诊断端点（管理员只读）。
 * <p>
 * 方案任务 5 第 6 点：把检索参数写入配置并提供管理员只读诊断信息，
 * 不向普通用户暴露向量、内部提示词或其他文档标题。
 * 这里只输出参数值本身，不包含任何用户数据或文档内容。
 * </p>
 */
@Tag(name = "检索诊断（管理员）")
@RestController
@RequestMapping("/api/admin/retrieval")
public class RetrievalDiagnosticsController {

    private final CurrentUserService currentUserService;
    private final RetrievalOptions retrievalOptions;

    public RetrievalDiagnosticsController(CurrentUserService currentUserService,
                                          RetrievalOptions retrievalOptions) {
        this.currentUserService = currentUserService;
        this.retrievalOptions = retrievalOptions;
    }

    /** 服务层守卫：即使 URL 规则被绕过，控制器内部也拒绝非管理员。 */
    private void requireAdmin() {
        currentUserService.requireSystemAdmin();
    }

    @GetMapping("/options")
    @Operation(summary = "查看当前生效的检索参数（管理员只读诊断）")
    public ApiResponse<Map<String, Object>> options() {
        requireAdmin();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("channelTopK", retrievalOptions.channelTopK());
        data.put("recallK", retrievalOptions.recallK());
        data.put("maxGlobalDocuments", retrievalOptions.maxGlobalDocuments());
        data.put("maxChunksPerDocument", retrievalOptions.maxChunksPerDocument());
        return ApiResponse.success(data);
    }
}
