package com.argus.rag.document.service;

import com.argus.rag.auth.CurrentUserService;
import com.argus.rag.common.enums.SystemRole;
import com.argus.rag.common.exception.BusinessException;
import com.argus.rag.document.mapper.DocumentMapper;
import com.argus.rag.document.model.dto.DocumentQuery;
import com.argus.rag.document.model.entity.DocumentEntity;
import com.argus.rag.document.model.vo.DocumentListItemVO;
import com.argus.rag.document.security.DocumentRoleGate;
import com.argus.rag.group.service.GroupMembershipService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * #1：文档角色门槛「读侧强制 + 设门槛入口」测试。
 * 用真实 DocumentRoleGate（单一判定点，USER<FINANCE<ADMIN）验证：
 * 列表按当前用户角色过滤、setRequiredRole 合法性校验与写入行为。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("文档角色门槛（DocumentQueryService 读侧强制 + 设门槛）")
class DocumentRoleGateReadSideTest {

    @Mock
    private DocumentMapper documentMapper;
    @Mock
    private GroupMembershipService groupMembershipService;
    @Mock
    private CurrentUserService currentUserService;

    /** 真实门槛：优先级纯函数，不触碰 mapper，测试判定可信。 */
    private DocumentRoleGate gate() {
        return new DocumentRoleGate(documentMapper);
    }

    private DocumentQueryService service() {
        return new DocumentQueryService(documentMapper, groupMembershipService, currentUserService, gate());
    }

    private DocumentListItemVO item(Long id, String requiredRole) {
        DocumentListItemVO vo = new DocumentListItemVO();
        vo.setDocumentId(id);
        vo.setRequiredRole(requiredRole);
        return vo;
    }

    private CurrentUserService.CurrentUser user(SystemRole role) {
        return new CurrentUserService.CurrentUser(1L, "U100001", "用户", role, false);
    }

    @Test
    @DisplayName("列表读侧强制：FINANCE 用户只看到 USER 与 FINANCE 文档，看不到 ADMIN 门槛文档")
    void listFiltersByRoleForFinance() {
        when(currentUserService.requireBusinessUser()).thenReturn(user(SystemRole.FINANCE));
        when(documentMapper.selectReadableDocuments(any())).thenReturn(List.of(
                item(10L, "USER"),
                item(20L, "FINANCE"),
                item(30L, "ADMIN")));

        List<DocumentListItemVO> result = service().listDocuments(new DocumentQuery());

        assertThat(result).extracting("documentId").containsExactly(10L, 20L);
    }

    @Test
    @DisplayName("列表读侧强制：USER 用户只见公开文档，FINANCE 门槛文档被剔除")
    void listFiltersForUserRole() {
        when(currentUserService.requireBusinessUser()).thenReturn(user(SystemRole.USER));
        when(documentMapper.selectReadableDocuments(any())).thenReturn(List.of(
                item(10L, "USER"),
                item(20L, "FINANCE")));

        List<DocumentListItemVO> result = service().listDocuments(new DocumentQuery());

        assertThat(result).extracting("documentId").containsExactly(10L);
        // 未达门槛的文档被剔除，不会让前端据此探知受限文档存在。
    }

    @Test
    @DisplayName("设门槛：合法值写入 required_role 并落库")
    void setRequiredRoleWritesValue() {
        DocumentEntity doc = new DocumentEntity();
        doc.setId(88L);
        doc.setGroupId(3L);
        doc.setRequiredRole("USER");
        when(documentMapper.selectByIdAndGroupId(88L, 3L)).thenReturn(doc);

        service().setRequiredRole(88L, 3L, "FINANCE");

        assertThat(doc.getRequiredRole()).isEqualTo("FINANCE");
        verify(documentMapper).updateById(any(DocumentEntity.class));
    }

    @Test
    @DisplayName("设门槛：requiredRole 非法值抛业务异常，不落库")
    void setRequiredRoleRejectsInvalidValue() {
        assertThatThrownBy(() -> service().setRequiredRole(1L, 3L, "BANKER"))
                .isInstanceOf(BusinessException.class);

        verify(documentMapper, never()).updateById(any(DocumentEntity.class));
    }

    @Test
    @DisplayName("设门槛：文档不存在时抛“文档不存在”")
    void setRequiredRoleRejectsMissingDocument() {
        when(documentMapper.selectByIdAndGroupId(1L, 3L)).thenReturn(null);

        assertThatThrownBy(() -> service().setRequiredRole(1L, 3L, "USER"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("文档不存在或已删除");
    }
}