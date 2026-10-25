package com.argus.rag.excel.service;

import com.argus.rag.auth.CurrentUserService;
import com.argus.rag.excel.model.dto.BatchCountResult;
import com.argus.rag.excel.model.dto.BatchOperationResult;
import com.argus.rag.excel.model.dto.BatchRejectEntry;
import com.argus.rag.excel.mapper.ExcelDocumentPermissionMapper;
import com.argus.rag.excel.mapper.OperationLogMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link RecycleBinService} Map→DTO 重构的守卫：
 * 序列化为 JSON 后 key 必须与既有 Map 结构逐字一致（{@code total/successCount/rejectedCount/successIds/rejected/id/reason}），
 * 批量处理的成功/拒绝明细语义不变。改字段名 / 顺序 / 类型即红。
 */
@DisplayName("回收站 DTO 契约与批量语义（RecycleBinService）")
class RecycleBinServiceTest {

    private ExcelDocumentService documentService;
    private RecycleBinService service;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        documentService = mock(ExcelDocumentService.class);
        ExcelSheetService sheetService = mock(ExcelSheetService.class);
        ExcelVersionService versionService = mock(ExcelVersionService.class);
        ExcelDocumentPermissionMapper permissionMapper = mock(ExcelDocumentPermissionMapper.class);
        OperationLogMapper operationLogMapper = mock(OperationLogMapper.class);
        service = new RecycleBinService(documentService, sheetService, versionService,
                permissionMapper, operationLogMapper);
    }

    @Test
    void batchOperationResultJsonKeysAreStable() throws Exception {
        BatchOperationResult r = new BatchOperationResult(
                3, 2, 1, List.of(1L, 2L),
                List.of(new BatchRejectEntry(3L, "彻底删除失败")));
        JsonNode node = mapper.readTree(mapper.writeValueAsString(r));
        assertEquals(3, node.get("total").asInt());
        assertEquals(2, node.get("successCount").asInt());
        assertEquals(1, node.get("rejectedCount").asInt());
        assertEquals(1L, node.get("successIds").get(0).asLong());
        assertEquals(3L, node.get("rejected").get(0).get("id").asLong());
        assertEquals("彻底删除失败", node.get("rejected").get(0).get("reason").asText());
    }

    @Test
    void batchCountResultJsonKeysAreStable() throws Exception {
        BatchCountResult c = new BatchCountResult(3, 3, 0);
        JsonNode node = mapper.readTree(mapper.writeValueAsString(c));
        assertEquals(3, node.get("total").asInt());
        assertEquals(3, node.get("successCount").asInt());
        assertEquals(0, node.get("rejectedCount").asInt());
        // 计数结果不携带明细字段
        assertFalse(node.has("successIds"));
        assertFalse(node.has("rejected"));
    }

    @Test
    void batchRestorePartitionsSuccessAndReject() {
        doThrow(new IllegalStateException("文档已不存在")).when(documentService)
                .restoreFromTrash(2L);

        BatchOperationResult r = service.batchRestore(List.of(1L, 2L, 3L));

        assertEquals(3, r.total());
        assertEquals(2, r.successCount());
        assertEquals(1, r.rejectedCount());
        assertEquals(List.of(1L, 3L), r.successIds());
        assertEquals(2L, r.rejected().get(0).id());
        assertEquals("文档已不存在", r.rejected().get(0).reason());
    }

    @Test
    void clearRecycleEmptySelectionReturnsZeroCount() {
        when(documentService.listIds(any())).thenReturn(List.of());
        BatchCountResult c = service.clearRecycle(mock(CurrentUserService.CurrentUser.class));
        assertEquals(0, c.total());
        assertEquals(0, c.successCount());
        assertEquals(0, c.rejectedCount());
    }

    @Test
    void batchRestoreRejectNullIdAndDistinct() {
        doThrow(new IllegalStateException("x")).when(documentService).restoreFromTrash(5L);
        BatchOperationResult r = service.batchRestore(Arrays.asList(1L, null, 1L, 5L));
        // null 被过滤、1 去重后仍成功；5 被拒
        assertEquals(List.of(1L), r.successIds());
        assertTrue(r.rejected().stream().anyMatch(e -> e.id().equals(5L)));
    }
}