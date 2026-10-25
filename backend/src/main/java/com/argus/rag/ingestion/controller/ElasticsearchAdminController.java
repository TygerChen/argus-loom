package com.argus.rag.ingestion.controller;

import com.argus.rag.auth.CurrentUserService;
import com.argus.rag.common.api.ApiResponse;
import com.argus.rag.ingestion.service.ElasticsearchRebuildService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * ES 索引运维：仅管理员可达。
 *
 * <p>ES 是派生数据（事实源在 PostgreSQL），索引损坏/mapping 变更/换集群时
 * 由管理员触发全量重建，无需重跑 ETL。</p>
 */
@Tag(name = "ES 索引运维")
@RestController
@RequestMapping("/api/admin/ops")
public class ElasticsearchAdminController {

    private final CurrentUserService currentUserService;
    private final ElasticsearchRebuildService rebuildService;

    public ElasticsearchAdminController(CurrentUserService currentUserService,
                                        ElasticsearchRebuildService rebuildService) {
        this.currentUserService = currentUserService;
        this.rebuildService = rebuildService;
    }

    @PostMapping("/elasticsearch/rebuild")
    @Operation(summary = "全量重建 ES 索引（以 PostgreSQL chunk 数据为准）")
    public ApiResponse<ElasticsearchRebuildService.RebuildResult> rebuild() {
        currentUserService.requireSystemAdmin();
        return ApiResponse.success(rebuildService.rebuildAll());
    }
}
