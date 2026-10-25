package com.argus.rag.document.service;

import com.argus.rag.auth.CurrentUserService;
import com.argus.rag.common.enums.SystemRole;
import com.argus.rag.common.exception.BusinessException;
import com.argus.rag.common.exception.ErrorCodes;
import com.argus.rag.document.mapper.DocumentMapper;
import com.argus.rag.document.model.dto.DocumentQuery;
import com.argus.rag.document.model.entity.DocumentEntity;
import com.argus.rag.document.security.DocumentRoleGate;
import com.argus.rag.group.service.GroupMembershipService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link DocumentQueryService} 纯逻辑契约（roleGate 仅放行不经判定实现）：
 * - {#normalizeQuery}：非法 uploaderUserId / 时间区间 / groupRelation / status 归一化、fileName trim；
 * - {#setRequiredRole}：非法 groupId/文档ID/空/非法角色名，成功归一 USER/FINANCE/ADMIN 并 updateById。
 */
@DisplayName("文档查询与角色门槛（DocumentQueryService）")
class DocumentQueryServiceTest {

    private DocumentMapper documentMapper;
    private GroupMembershipService groupMembershipService;
    private CurrentUserService currentUserService;
    private DocumentRoleGate roleGate;
    private DocumentQueryService service;
    private final CurrentUserService.CurrentUser user =
            new CurrentUserService.CurrentUser(7L, "U7", "会计", SystemRole.USER, false);

    @BeforeEach
    void setUp() {
        documentMapper = mock(DocumentMapper.class);
        groupMembershipService = mock(GroupMembershipService.class);
        currentUserService = mock(CurrentUserService.class);
        when(currentUserService.requireBusinessUser()).thenReturn(user);
        roleGate = mock(DocumentRoleGate.class);
        when(roleGate.canRead(any(), any())).thenReturn(true); // 放行，不触判定实现
        when(documentMapper.selectReadableDocuments(any())).thenReturn(List.of());
        service = new DocumentQueryService(documentMapper, groupMembershipService,
                currentUserService, roleGate);
    }

    private DocumentQuery listQuery() {
        DocumentQuery q = new DocumentQuery();
        q.setCurrentUserId(7L);
        return q;
    }

    private DocumentQuery capturedQuery() {
        ArgumentCaptor<DocumentQuery> cap = ArgumentCaptor.forClass(DocumentQuery.class);
        verify(documentMapper).selectReadableDocuments(cap.capture());
        return cap.getValue();
    }

    // ---------- normalizeQuery（经 listDocuments 触发） ----------

    @Test
    void listDocumentsAcceptsNullQuery() {
        // null → 归一为安全空查询并返回空列表（不抛）
        assertEquals(0, service.listDocuments(null).size());
    }

    @Test
    void normalizeRejectsIllegalUploaderUserId() {
        DocumentQuery q = listQuery();
        q.setUploaderUserId(0L);
        BusinessException ex = assertThrows(BusinessException.class, () -> service.listDocuments(q));
        assertEquals("uploaderUserId 非法", ex.getMessage());
    }

    @Test
    void normalizeRejectsTimeRangeReversed() {
        DocumentQuery q = listQuery();
        q.setUploadedFrom(LocalDateTime.of(2026, 9, 10, 0, 0));
        q.setUploadedTo(LocalDateTime.of(2026, 9, 9, 0, 0));
        BusinessException ex = assertThrows(BusinessException.class, () -> service.listDocuments(q));
        assertEquals("uploadedFrom 不能晚于 uploadedTo", ex.getMessage());
    }

    @Test
    void normalizeRejectsIllegalGroupRelation() {
        DocumentQuery q = listQuery();
        q.setGroupRelation("random");
        BusinessException ex = assertThrows(BusinessException.class, () -> service.listDocuments(q));
        assertEquals("groupRelation 非法", ex.getMessage());
    }

    @Test
    void normalizeMapsOwnedAliasesToOwned() {
        DocumentQuery q = listQuery();
        q.setGroupRelation(" owner ");
        service.listDocuments(q);
        assertEquals("OWNED", capturedQuery().getGroupRelation());
    }

    @Test
    void normalizeMapsJoinedAliasesToJoined() {
        DocumentQuery q = listQuery();
        q.setGroupRelation("MEMBER");
        service.listDocuments(q);
        assertEquals("JOINED", capturedQuery().getGroupRelation());
    }

    @Test
    void normalizeRejectsIllegalStatus() {
        DocumentQuery q = listQuery();
        q.setStatus("deleted");
        BusinessException ex = assertThrows(BusinessException.class, () -> service.listDocuments(q));
        assertEquals("status 非法", ex.getMessage());
    }

    @Test
    void normalizeMapsStatusAndTrimsFileName() {
        DocumentQuery q = listQuery();
        q.setGroupRelation("owned");
        q.setStatus("  READY ");
        q.setFileName("  报告.pdf  ");
        service.listDocuments(q);

        DocumentQuery norm = capturedQuery();
        assertEquals("OWNED", norm.getGroupRelation());
        assertEquals("READY", norm.getStatus());
        assertEquals("报告.pdf", norm.getFileName());
    }

    @Test
    void normalizeRequiresReadableGroupWhenProvided() {
        DocumentQuery q = listQuery();
        q.setGroupId(0L);
        BusinessException ex = assertThrows(BusinessException.class, () -> service.listDocuments(q));
        assertEquals("groupId 非法", ex.getMessage());

        // 合法 groupId 正常放行
        DocumentQuery q2 = listQuery();
        q2.setGroupId(5L);
        when(roleGate.canRead(any(), any())).thenReturn(true);
        service.listDocuments(q2);
        verify(groupMembershipService).requireGroupReadable(5L);
    }

    // ---------- setRequiredRole ----------

    @Test
    void setRequiredRoleRejectsIllegalGroupId() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.setRequiredRole(1L, 0L, "USER"));
        assertEquals("groupId 非法", ex.getMessage());
    }

    @Test
    void setRequiredRoleRejectsIllegalDocumentId() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.setRequiredRole(0L, 1L, "USER"));
        assertEquals("文档ID非法", ex.getMessage());
    }

    @Test
    void setRequiredRoleRejectsBlank() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.setRequiredRole(1L, 1L, "  "));
        assertEquals("requiredRole 不能为空", ex.getMessage());
    }

    @Test
    void setRequiredRoleRejectsInvalidRoleName() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.setRequiredRole(1L, 1L, "viewer"));
        assertEquals("requiredRole 非法，仅支持 USER/FINANCE/ADMIN", ex.getMessage());
    }

    @Test
    void setRequiredRoleRejectsMissingDocument() {
        when(documentMapper.selectByIdAndGroupId(1L, 1L)).thenReturn(null);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.setRequiredRole(1L, 1L, "USER"));
        assertEquals(ErrorCodes.DOCUMENT_NOT_FOUND, ex.getCode());
    }

    @Test
    void setRequiredRoleNormalizesAndUpdates() {
        DocumentEntity doc = new DocumentEntity();
        doc.setRequiredRole("USER");
        when(documentMapper.selectByIdAndGroupId(1L, 1L)).thenReturn(doc);
        when(documentMapper.updateById(any(DocumentEntity.class))).thenReturn(1);

        service.setRequiredRole(1L, 1L, "  FINANCE  ");

        ArgumentCaptor<DocumentEntity> cap = ArgumentCaptor.forClass(DocumentEntity.class);
        verify(documentMapper).updateById(cap.capture());
        assertEquals("FINANCE", cap.getValue().getRequiredRole());
    }
}