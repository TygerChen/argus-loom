package com.argus.rag.group.service;

import com.argus.rag.auth.CurrentUserService;
import com.argus.rag.common.enums.GroupJoinRequestStatus;
import com.argus.rag.common.enums.GroupRole;
import com.argus.rag.common.exception.BusinessException;
import com.argus.rag.common.exception.ErrorCodes;
import com.argus.rag.group.mapper.GroupJoinRequestMapper;
import com.argus.rag.group.model.dto.CreateJoinRequestRequest;
import com.argus.rag.group.model.entity.Group;
import com.argus.rag.group.model.entity.GroupJoinRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link GroupJoinRequestService} 加入群申请契约：
 * - 提交：组织 ID 空/不存在、已是成员、已有待处理邀请、已有待处理申请、成功落 PENDING；
 * - 审批：除了归属校验（同组/非 PENDING）与重复成员外，approve 落 MEMBER+APPROVED、reject 落 REJECTED；
 * - {@code updateStatus} 返回 0 的复发守卫。
 * owner 委托以 mock 的 {@link GroupMembershipService} 承载。双参出 getCode、单参文案出 getMessage。
 */
@DisplayName("群组加入申请（GroupJoinRequestService）")
class GroupJoinRequestServiceTest {

    private GroupJoinRequestMapper mapper;
    private GroupMembershipService membershipService;
    private GroupJoinRequestService service;
    private final CurrentUserService.CurrentUser owner =
            new CurrentUserService.CurrentUser(7L, "U7", "会计");

    @BeforeEach
    void setUp() {
        mapper = mock(GroupJoinRequestMapper.class);
        membershipService = mock(GroupMembershipService.class);
        when(membershipService.requireGroupOwner(any())).thenReturn(owner);
        CurrentUserService userService = mock(CurrentUserService.class);
        when(userService.requireBusinessUser()).thenReturn(owner);
        service = new GroupJoinRequestService(mapper, membershipService, userService);
    }

    private Group group(Long id) {
        Group g = new Group();
        g.setId(id);
        return g;
    }

    private GroupJoinRequest joinRequest(Long requestId, Long groupId, Long applicantUserId,
                                         GroupJoinRequestStatus status) {
        GroupJoinRequest e = new GroupJoinRequest();
        e.setId(requestId);
        e.setGroupId(groupId);
        e.setApplicantUserId(applicantUserId);
        e.setStatus(status);
        return e;
    }

    // ---------- submit ----------

    @Test
    void submitRejectsBlankGroupCode() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.submitJoinRequest(new CreateJoinRequestRequest("  ")));
        assertEquals("组织 ID 不能为空", ex.getMessage());
    }

    @Test
    void submitRejectsUnknownGroupCode() {
        when(mapper.selectActiveGroupByCode("G1")).thenReturn(null);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.submitJoinRequest(new CreateJoinRequestRequest("G1")));
        assertEquals(ErrorCodes.GROUP_NOT_FOUND, ex.getCode());
        assertEquals("组织 ID 不存在", ex.getMessage());
    }

    @Test
    void submitRejectsExistingMembership() {
        when(mapper.selectActiveGroupByCode("G1")).thenReturn(group(1L));
        when(mapper.countActiveMembership(1L, 7L)).thenReturn(1L);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.submitJoinRequest(new CreateJoinRequestRequest("G1")));
        assertEquals("该用户已经是知识库成员", ex.getMessage());
    }

    @Test
    void submitRejectsPendingInvitation() {
        when(mapper.selectActiveGroupByCode("G1")).thenReturn(group(1L));
        when(mapper.countActiveMembership(1L, 7L)).thenReturn(0L);
        when(mapper.countPendingInvitation(1L, 7L)).thenReturn(1L);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.submitJoinRequest(new CreateJoinRequestRequest("G1")));
        assertEquals(ErrorCodes.GROUP_REQUEST_PENDING, ex.getCode());
        assertEquals("该知识库已有待处理邀请，请先处理邀请", ex.getMessage());
    }

    @Test
    void submitRejectsPendingOwnRequest() {
        when(mapper.selectActiveGroupByCode("G1")).thenReturn(group(1L));
        when(mapper.countActiveMembership(1L, 7L)).thenReturn(0L);
        when(mapper.countPendingInvitation(1L, 7L)).thenReturn(0L);
        when(mapper.countPendingJoinRequest(1L, 7L)).thenReturn(1L);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.submitJoinRequest(new CreateJoinRequestRequest("G1")));
        assertEquals(ErrorCodes.GROUP_REQUEST_PENDING, ex.getCode());
        assertEquals("该用户已有待处理加入申请，请先等待审批", ex.getMessage());
    }

    @Test
    void submitSucceedsPersistingPendingRequest() {
        when(mapper.selectActiveGroupByCode("G1")).thenReturn(group(1L));
        when(mapper.countActiveMembership(1L, 7L)).thenReturn(0L);
        when(mapper.countPendingInvitation(1L, 7L)).thenReturn(0L);
        when(mapper.countPendingJoinRequest(1L, 7L)).thenReturn(0L);
        when(mapper.insertPendingJoinRequestReturningId(any(), any(), any())).thenReturn(9L);

        Long requestId = service.submitJoinRequest(new CreateJoinRequestRequest("G1"));

        assertEquals(9L, requestId);
        verify(mapper).insertPendingJoinRequestReturningId(1L, 7L, GroupJoinRequestStatus.PENDING.name());
    }

    // ---------- listOwner ----------

    @Test
    void listOwnerRejectsIllegalGroupId() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.listOwnerJoinRequests(0L));
        assertEquals("groupId 非法", ex.getMessage());
    }

    @Test
    void listOwnerJoinRequestsDelegatesOwnerAndReturns() {
        when(mapper.selectPendingJoinRequestsByGroupId(1L)).thenReturn(List.of());
        assertEquals(0, service.listOwnerJoinRequests(1L).size());
        verify(membershipService).requireGroupOwner(1L);
    }

    // ---------- approve ----------

    @Test
    void approveRejectsUnknownRequest() {
        when(mapper.selectJoinRequestById(1L)).thenReturn(null);
        BusinessException ex = assertThrows(BusinessException.class, () -> service.approveJoinRequest(1L, 1L));
        assertEquals(ErrorCodes.GROUP_NOT_FOUND, ex.getCode());
        assertEquals("申请不存在", ex.getMessage());
    }

    @Test
    void approveRejectsWrongGroup() {
        when(mapper.selectJoinRequestById(1L))
                .thenReturn(joinRequest(1L, 99L, 7L, GroupJoinRequestStatus.PENDING));
        BusinessException ex = assertThrows(BusinessException.class, () -> service.approveJoinRequest(1L, 1L));
        assertEquals(ErrorCodes.GROUP_NOT_FOUND, ex.getCode());
    }

    @Test
    void approveRejectsNonPending() {
        when(mapper.selectJoinRequestById(1L))
                .thenReturn(joinRequest(1L, 1L, 7L, GroupJoinRequestStatus.REJECTED));
        BusinessException ex = assertThrows(BusinessException.class, () -> service.approveJoinRequest(1L, 1L));
        assertEquals(ErrorCodes.GROUP_ALREADY_HANDLED, ex.getCode());
        assertEquals("申请已处理", ex.getMessage());
    }

    @Test
    void approveRejectsExistingMember() {
        when(mapper.selectJoinRequestById(1L))
                .thenReturn(joinRequest(1L, 1L, 7L, GroupJoinRequestStatus.PENDING));
        when(mapper.countActiveMembership(1L, 7L)).thenReturn(1L);
        BusinessException ex = assertThrows(BusinessException.class, () -> service.approveJoinRequest(1L, 1L));
        assertEquals("该用户已经是知识库成员", ex.getMessage());
    }

    @Test
    void approveSucceedsWithMemberRoleAndApprovedStatus() {
        when(mapper.selectJoinRequestById(1L))
                .thenReturn(joinRequest(1L, 1L, 7L, GroupJoinRequestStatus.PENDING));
        when(mapper.countActiveMembership(1L, 7L)).thenReturn(0L);
        when(mapper.updateJoinRequestStatus(1L, GroupJoinRequestStatus.PENDING.name(),
                GroupJoinRequestStatus.APPROVED.name(), 7L)).thenReturn(1);

        service.approveJoinRequest(1L, 1L);

        verify(mapper).insertMembership(1L, 7L, GroupRole.MEMBER.name());
        verify(mapper).updateJoinRequestStatus(1L, GroupJoinRequestStatus.PENDING.name(),
                GroupJoinRequestStatus.APPROVED.name(), 7L);
    }

    @Test
    void approveRejectedWhenStatusUpdateReturnsZero() {
        when(mapper.selectJoinRequestById(1L))
                .thenReturn(joinRequest(1L, 1L, 7L, GroupJoinRequestStatus.PENDING));
        when(mapper.countActiveMembership(1L, 7L)).thenReturn(0L);
        when(mapper.updateJoinRequestStatus(any(), any(), any(), any())).thenReturn(0);
        BusinessException ex = assertThrows(BusinessException.class, () -> service.approveJoinRequest(1L, 1L));
        assertEquals(ErrorCodes.GROUP_ALREADY_HANDLED, ex.getCode());
    }

    // ---------- reject ----------

    @Test
    void rejectJoinRequestSucceedsWithRejectedStatus() {
        when(mapper.selectJoinRequestById(1L))
                .thenReturn(joinRequest(1L, 1L, 7L, GroupJoinRequestStatus.PENDING));
        when(mapper.updateJoinRequestStatus(any(), any(), any(), any())).thenReturn(1);

        service.rejectJoinRequest(1L, 1L);

        verify(mapper).updateJoinRequestStatus(1L, GroupJoinRequestStatus.PENDING.name(),
                GroupJoinRequestStatus.REJECTED.name(), 7L);
    }

    @Test
    void rejectWrongGroupIsRefused() {
        when(mapper.selectJoinRequestById(1L))
                .thenReturn(joinRequest(1L, 99L, 7L, GroupJoinRequestStatus.PENDING));
        BusinessException ex = assertThrows(BusinessException.class, () -> service.rejectJoinRequest(1L, 1L));
        assertEquals(ErrorCodes.GROUP_NOT_FOUND, ex.getCode());
    }
}