package com.argus.rag.assistant.agent;

import com.argus.rag.common.enums.SystemRole;
import com.argus.rag.common.exception.BusinessException;
import com.argus.rag.common.security.AuthenticatedUser;
import com.argus.rag.common.security.UserContext;
import com.argus.rag.document.mapper.DocumentMapper;
import com.argus.rag.document.model.entity.DocumentEntity;
import com.argus.rag.document.security.DocumentRoleGate;
import com.argus.rag.engine.StructuredDataRetrievalService;
import com.argus.rag.excel.service.ExcelPermissionService;
import com.argus.rag.excel.mapper.ExcelDocumentMapper;
import com.argus.rag.group.service.GroupMembershipService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.model.ToolContext;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * #5：结构化数据工具的角色门槛校验。
 * 防止普通用户经 AI 工具（get_table_schema / search_table_data / query_structured_data）
 * 以 documentId 直取财务 Excel 表格——未达门槛一律“无数据”，达标才放行。
 */
@ExtendWith(MockitoExtension.class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
@DisplayName("结构化数据查询工具（AIAI 工具侧财务数据隔离）")
class StructuredDataQueryToolRoleGateTest {

    @Mock
    private StructuredDataRetrievalService retrievalService;
    @Mock
    private ExcelDocumentMapper excelDocumentMapper;
    @Mock
    private DocumentMapper ragDocumentMapper;
    @Mock
    private GroupMembershipService groupMembershipService;
    @Mock
    private ExcelPermissionService excelPermissionService;

    private DocumentRoleGate gate;

    @BeforeEach
    void setUp() {
        gate = new DocumentRoleGate(ragDocumentMapper);
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    private StructuredDataQueryTool tool() {
        return new StructuredDataQueryTool(
                retrievalService, ragDocumentMapper, groupMembershipService, gate, excelPermissionService);
    }

    private void login(SystemRole role) {
        UserContext.set(new AuthenticatedUser(1L, "U100001", "用户", role, false));
    }

    private DocumentEntity doc(Long id, Long groupId, String requiredRole) {
        DocumentEntity doc = new DocumentEntity();
        doc.setId(id);
        doc.setGroupId(groupId);
        doc.setRequiredRole(requiredRole);
        return doc;
    }

    private DocumentEntity doc(Long id, Long groupId, String requiredRole, String fileName) {
        DocumentEntity doc = doc(id, groupId, requiredRole);
        doc.setFileName(fileName);
        return doc;
    }

    @Test
    @DisplayName("普通用户访问 FINANCE 门槛文档 → 抛无数据（防泄露）")
    void userCannotAccessFinanceDoc() {
        login(SystemRole.USER);
        when(excelPermissionService.canView(5L)).thenReturn(false);

        assertThatThrownBy(() -> tool().getTableSchema(5L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("未找到该表格的结构化数据。");

        verify(retrievalService, never()).buildStructuredContext(any());
    }

    @Test
    @DisplayName("财务/领导角色访问 FINANCE 门槛文档 → 放行")
    void financeCanAccessFinanceDoc() {
        login(SystemRole.FINANCE);
        when(excelPermissionService.canView(5L)).thenReturn(true);
        when(retrievalService.buildStructuredContext(5L)).thenReturn("列A | 列B | 列C");

        tool().getTableSchema(5L);

        verify(retrievalService).buildStructuredContext(5L);
    }

    @Test
    @DisplayName("非群组成员被拒绝，即使门槛可放行")
    void nonMemberRejected() {
        login(SystemRole.ADMIN);
        when(excelPermissionService.canView(5L)).thenReturn(false);

        assertThatThrownBy(() -> tool().searchTableData("关键词", 5L))
                .isInstanceOf(BusinessException.class);

        verify(retrievalService, never()).fuzzySearch(any(), any());
    }

    @Test
    @DisplayName("文档不存在（含已删除）→ 视为无数据")
    void missingDocumentRejected() {
        login(SystemRole.ADMIN);
        when(excelPermissionService.canView(404L)).thenReturn(false);

        assertThatThrownBy(() -> tool().getTableSchema(404L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("未找到该表格的结构化数据。");

        verify(groupMembershipService, never()).requireGroupReadable(any());
        verify(retrievalService, never()).buildStructuredContext(any());
    }

    @Test
    @DisplayName("未指定 documentId：仅列出当前账号可读文档，不泄露受限文档")
    void noDocumentIdListsOnlyReadableDocs() {
        login(SystemRole.USER);
        // 群组 9 下存在公开文档 10 与 FINANCE 门槛文档 20
        when(ragDocumentMapper.selectList(any()))
                .thenReturn(List.of(doc(10L, 9L, "USER"), doc(20L, 9L, "FINANCE")));
        when(ragDocumentMapper.selectBatchIds(any()))
                .thenReturn(List.of(doc(10L, 9L, "USER", "部门预算.xlsx")));
        ToolContext context = new ToolContext(Map.of(
                AssistantKnowledgeBaseTool.GROUP_IDS_CONTEXT_KEY, List.of(9L)));

        String result = tool().queryStructuredData("销售额", "eq", "100", null, context);

        assertThat(result)
                .contains("10")
                .contains("部门预算.xlsx")
                .doesNotContain("20")
                .doesNotContain("FINANCE");
        // 全程未触碰检索服务，避免把“受限/不存在”的差异暴露给外部
        verify(retrievalService, never()).buildStructuredContext(any());
        verify(retrievalService, never()).retrieveRelevantData(any(), any(), anyInt());
    }

    @Test
    @DisplayName("Excel 工作簿鉴权走 ExcelPermissionService，不用 RAG DocumentRoleGate")
    void excelWorkbookAccessDelegatesToExcelPermissionService() {
        login(SystemRole.USER);
        // RAG 文档表没有任何 id=88 的记录——若工具仍走 RAG 门槛校验会误判"无数据"
        when(ragDocumentMapper.selectById(88L)).thenReturn(null);
        when(excelPermissionService.canView(88L)).thenReturn(true);
        when(retrievalService.buildStructuredContext(88L)).thenReturn("列A | 列B");

        String schema = tool().getTableSchema(88L);

        assertThat(schema).contains("列A");
        verify(excelPermissionService).canView(88L);
        // RAG 文档角色门槛绝不能参与 Excel 工作簿鉴权
        verify(groupMembershipService, never()).requireGroupReadable(any());
    }

    @Test
    @DisplayName("无 Excel 工作簿查看权 → 拒绝且不泄露工作簿存在性")
    void excelWorkbookDeniedForUserWithoutViewPermission() {
        login(SystemRole.USER);
        when(ragDocumentMapper.selectById(88L)).thenReturn(null);
        when(excelPermissionService.canView(88L)).thenReturn(false);

        assertThatThrownBy(() -> tool().getTableSchema(88L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("未找到该表格的结构化数据。");

        verify(retrievalService, never()).buildStructuredContext(any());
    }

    @Test
    @DisplayName("RAG 文档 ID 与 Excel 工作簿 ID 相同时，各走各的鉴权，不互串")
    void sameNumericIdDoesNotCrossAuthorize() {
        login(SystemRole.USER);
        // id=5 同时是 RAG FINANCE 门槛文档（不可读）和 Excel 工作簿（可读）——
        // 表格查询必须只看 Excel 权限；反之知识库检索只看 RAG 门槛。
        when(ragDocumentMapper.selectById(5L)).thenReturn(doc(5L, 9L, "FINANCE"));
        when(excelPermissionService.canView(5L)).thenReturn(true);
        when(retrievalService.buildStructuredContext(5L)).thenReturn("列A");

        String schema = tool().getTableSchema(5L);

        assertThat(schema).contains("列A");
    }
}
