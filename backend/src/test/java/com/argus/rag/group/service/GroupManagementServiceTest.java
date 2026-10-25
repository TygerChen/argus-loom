package com.argus.rag.group.service;

import com.argus.rag.auth.CurrentUserService;
import com.argus.rag.common.enums.GroupInvitationStatus;
import com.argus.rag.common.enums.GroupRole;
import com.argus.rag.common.enums.GroupStatus;
import com.argus.rag.common.exception.BusinessException;
import com.argus.rag.common.exception.ErrorCodes;
import com.argus.rag.group.mapper.GroupJoinRequestMapper;
import com.argus.rag.group.mapper.GroupMembershipMapper;
import com.argus.rag.group.model.dto.CreateGroupRequest;
import com.argus.rag.group.model.dto.CreateInvitationRequest;
import com.argus.rag.group.model.entity.GroupInvitation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link GroupManagementService} 群组管理业务契约：
 * - 创建：组名空/超 128、描述空/超 512 的校验与 trim，成功落 OW NER 成员；
 * - 邀请：非法 id、用户不存在、已是成员、重复待处理/待审批、成功；
 * - 邀请状态机：接受（仅被邀请人、仅 PENDING、reject 已存在成员）与取消；
 * - 成员操作：移除（非成员/不能移除 OWNER）、退出（非成员/OWNER 不可退）。
 * 复用 mock 的 {@link GroupMembershipService} 承载 owner 委托，双参码按 getCode、单参文案按 getMessage。
 */
@DisplayName("群组管理业务（GroupManagementService）")
class GroupManagementServiceTest {

    private GroupMembershipMapper membershipMapper;
    private GroupJoinRequestMapper joinRequestMapper;
    private GroupMembershipService membershipService;
    private GroupManagementService service;
    private final CurrentUserService.CurrentUser owner =
            new CurrentUserService.CurrentUser(7L, "U7", "会计");

    @BeforeEach
    void setUp() {
        membershipMapper = mock(GroupMembershipMapper.class);
        joinRequestMapper = mock(GroupJoinRequestMapper.class);
        membershipService = mock(GroupMembershipService.class);
        CurrentUserService userService = mock(CurrentUserService.class);
        when(userService.requireBusinessUser()).thenReturn(owner);
        service = new GroupManagementService(membershipMapper, joinRequestMapper, membershipService, userService);
    }

    private GroupInvitation invitation(Long id, Long inviteeUserId, GroupInvitationStatus status) {
        GroupInvitation e = new GroupInvitation();
        e.setId(id);
        e.setGroupId(1L);
        e.setInviterUserId(5L);
        e.setInviteeUserId(inviteeUserId);
        e.setStatus(status);
        return e;
    }

    // ---------- 创建群组 ----------

    @Test
    void createGroupRejectsBlankName() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.createGroup(new CreateGroupRequest("  ", "desc")));
        assertEquals("组名称不能为空", ex.getMessage());
    }

    @Test
    void createGroupRejectsOverlongName() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.createGroup(new CreateGroupRequest("组".repeat(129), "desc")));
        assertEquals("组名称不能超过 128", ex.getMessage());
    }

    @Test
    void createGroupRejectsOverlongDescription() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.createGroup(new CreateGroupRequest("组名", "d".repeat(513))));
        assertEquals("组描述不能超过 512", ex.getMessage());
    }

    @Test
    void createGroupSucceedsAndPersistsOwnerMembership() {
        when(membershipMapper.insertGroupReturningId(anyString(), anyString(), any(), any(), any()))
                .thenReturn(42L);

        Long groupId = service.createGroup(new CreateGroupRequest(" 研发组 ", "desc"));

        assertEquals(42L, groupId);
        verify(membershipMapper).insertGroupReturningId(any(), eq("研发组"),
                any(), any(), eq(GroupStatus.ACTIVE.name()));
        verify(membershipMapper).insertMembership(42L, 7L, GroupRole.OWNER.name());
    }

    // ---------- createInvitation ----------

    @Test
    void createInvitationRejectsIllegalGroupId() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.createInvitation(0L, new CreateInvitationRequest(9L)));
        assertEquals("groupId 非法", ex.getMessage());
    }

    @Test
    void createInvitationRejectsMissingUser() {
        when(membershipService.requireGroupOwner(1L)).thenReturn(owner);
        when(membershipMapper.countUserById(9L)).thenReturn(0L);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.createInvitation(1L, new CreateInvitationRequest(9L)));
        assertEquals(ErrorCodes.GROUP_NOT_FOUND, ex.getCode());
        assertEquals("被邀请用户不存在", ex.getMessage());
    }

    @Test
    void createInvitationRejectsExistingMember() {
        when(membershipService.requireGroupOwner(1L)).thenReturn(owner);
        when(membershipMapper.countUserById(9L)).thenReturn(1L);
        when(membershipMapper.countMembershipByGroupIdAndUserId(1L, 9L)).thenReturn(1L);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.createInvitation(1L, new CreateInvitationRequest(9L)));
        assertEquals(ErrorCodes.GROUP_ACTION_FORBIDDEN, ex.getCode());
        assertEquals("被邀请人已是群组成员", ex.getMessage());
    }

    @Test
    void createInvitationRejectsPendingJoinRequest() {
        when(membershipService.requireGroupOwner(1L)).thenReturn(owner);
        when(membershipMapper.countUserById(9L)).thenReturn(1L);
        when(membershipMapper.countMembershipByGroupIdAndUserId(1L, 9L)).thenReturn(0L);
        when(membershipMapper.countPendingInvitation(1L, 9L)).thenReturn(0L);
        when(joinRequestMapper.countPendingJoinRequest(1L, 9L)).thenReturn(1L);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.createInvitation(1L, new CreateInvitationRequest(9L)));
        assertEquals(ErrorCodes.GROUP_REQUEST_PENDING, ex.getCode());
    }

    @Test
    void createInvitationRejectsPendingInvitation() {
        when(membershipService.requireGroupOwner(1L)).thenReturn(owner);
        when(membershipMapper.countUserById(9L)).thenReturn(1L);
        when(membershipMapper.countMembershipByGroupIdAndUserId(1L, 9L)).thenReturn(0L);
        when(membershipMapper.countPendingInvitation(1L, 9L)).thenReturn(1L);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.createInvitation(1L, new CreateInvitationRequest(9L)));
        assertEquals(ErrorCodes.GROUP_REQUEST_PENDING, ex.getCode());
    }

    @Test
    void createInvitationSucceedsPersistingPending() {
        when(membershipService.requireGroupOwner(1L)).thenReturn(owner);
        when(membershipMapper.countUserById(9L)).thenReturn(1L);
        when(membershipMapper.countMembershipByGroupIdAndUserId(1L, 9L)).thenReturn(0L);
        when(membershipMapper.countPendingInvitation(1L, 9L)).thenReturn(0L);
        when(joinRequestMapper.countPendingJoinRequest(1L, 9L)).thenReturn(0L);
        when(membershipMapper.insertPendingInvitationReturningId(any(), any(), any(), any())).thenReturn(55L);

        Long invitationId = service.createInvitation(1L, new CreateInvitationRequest(9L));

        assertEquals(55L, invitationId);
        verify(membershipMapper).insertPendingInvitationReturningId(1L, 7L, 9L, GroupInvitationStatus.PENDING.name());
    }

    // ---------- accept / reject / cancel ----------

    @Test
    void acceptInvitationRejectsUnknownInvitation() {
        when(membershipMapper.selectInvitationById(1L)).thenReturn(null);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.acceptInvitation(1L));
        assertEquals(ErrorCodes.GROUP_NOT_FOUND, ex.getCode());
        assertEquals("邀请不存在", ex.getMessage());
    }

    @Test
    void acceptInvitationRejectsNonInvitee() {
        when(membershipMapper.selectInvitationById(1L))
                .thenReturn(invitation(1L, 99L, GroupInvitationStatus.PENDING));
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.acceptInvitation(1L));
        assertEquals(ErrorCodes.GROUP_ACTION_FORBIDDEN, ex.getCode());
    }

    @Test
    void acceptInvitationRejectsAlreadyHandled() {
        when(membershipMapper.selectInvitationById(1L))
                .thenReturn(invitation(1L, 7L, GroupInvitationStatus.CANCELED));
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.acceptInvitation(1L));
        assertEquals(ErrorCodes.GROUP_ALREADY_HANDLED, ex.getCode());
        assertEquals("邀请已处理", ex.getMessage());
    }

    @Test
    void acceptInvitationRejectsExistingMembership() {
        when(membershipMapper.selectInvitationById(1L))
                .thenReturn(invitation(1L, 7L, GroupInvitationStatus.PENDING));
        when(membershipMapper.countMembershipByGroupIdAndUserId(1L, 7L)).thenReturn(1L);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.acceptInvitation(1L));
        assertEquals(ErrorCodes.GROUP_ACTION_FORBIDDEN, ex.getCode());
    }

    @Test
    void acceptInvitationSucceedsWithMemberRoleAndAcceptedStatus() {
        when(membershipMapper.selectInvitationById(1L))
                .thenReturn(invitation(1L, 7L, GroupInvitationStatus.PENDING));
        when(membershipMapper.countMembershipByGroupIdAndUserId(1L, 7L)).thenReturn(0L);
        when(membershipMapper.updateInvitationStatus(1L,
                GroupInvitationStatus.PENDING.name(), GroupInvitationStatus.ACCEPTED.name())).thenReturn(1);

        service.acceptInvitation(1L);

        verify(membershipMapper).insertMembership(1L, 7L, GroupRole.MEMBER.name());
        verify(membershipMapper).updateInvitationStatus(1L,
                GroupInvitationStatus.PENDING.name(), GroupInvitationStatus.ACCEPTED.name());
    }

    @Test
    void rejectInvitationSucceedsWithRejectedStatus() {
        when(membershipMapper.selectInvitationById(1L))
                .thenReturn(invitation(1L, 7L, GroupInvitationStatus.PENDING));
        when(membershipMapper.updateInvitationStatus(1L,
                GroupInvitationStatus.PENDING.name(), GroupInvitationStatus.REJECTED.name())).thenReturn(1);
        service.rejectInvitation(1L);
        verify(membershipMapper).updateInvitationStatus(1L,
                GroupInvitationStatus.PENDING.name(), GroupInvitationStatus.REJECTED.name());
    }

    @Test
    void cancelInvitationRejectsAlreadyHandled() {
        when(membershipMapper.selectInvitationById(1L))
                .thenReturn(invitation(1L, 7L, GroupInvitationStatus.ACCEPTED));
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.cancelInvitation(1L));
        assertEquals(ErrorCodes.GROUP_ALREADY_HANDLED, ex.getCode());
    }

    @Test
    void cancelInvitationSucceedsWithCanceledStatus() {
        when(membershipMapper.selectInvitationById(1L))
                .thenReturn(invitation(1L, 9L, GroupInvitationStatus.PENDING));
        when(membershipMapper.updateInvitationStatus(1L,
                GroupInvitationStatus.PENDING.name(), GroupInvitationStatus.CANCELED.name())).thenReturn(1);
        service.cancelInvitation(1L);
        verify(membershipMapper).updateInvitationStatus(1L,
                GroupInvitationStatus.PENDING.name(), GroupInvitationStatus.CANCELED.name());
    }

    // ---------- removeMember / leaveGroup ----------

    @Test
    void removeMemberRejectsUnknownMember() {
        when(membershipService.requireGroupOwner(1L)).thenReturn(owner);
        when(membershipMapper.selectActiveMembershipRole(9L, 1L)).thenReturn(null);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.removeMember(1L, 9L));
        assertEquals(ErrorCodes.GROUP_NOT_FOUND, ex.getCode());
        assertEquals("成员不存在", ex.getMessage());
    }

    @Test
    void removeMemberRejectsOwner() {
        when(membershipService.requireGroupOwner(1L)).thenReturn(owner);
        when(membershipMapper.selectActiveMembershipRole(9L, 1L)).thenReturn(GroupRole.OWNER.name());
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.removeMember(1L, 9L));
        assertEquals(ErrorCodes.GROUP_ACTION_FORBIDDEN, ex.getCode());
        assertEquals("不能移除 OWNER", ex.getMessage());
    }

    @Test
    void removeMemberSucceedsForRegularMember() {
        when(membershipService.requireGroupOwner(1L)).thenReturn(owner);
        when(membershipMapper.selectActiveMembershipRole(9L, 1L)).thenReturn(GroupRole.MEMBER.name());
        service.removeMember(1L, 9L);
        verify(membershipMapper).deleteMembership(1L, 9L);
    }

    @Test
    void leaveGroupRejectsNonMember() {
        when(membershipMapper.selectActiveMembershipRole(7L, 1L)).thenReturn(null);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.leaveGroup(1L));
        assertEquals(ErrorCodes.GROUP_ACTION_FORBIDDEN, ex.getCode());
    }

    @Test
    void leaveGroupRejectsOwner() {
        when(membershipMapper.selectActiveMembershipRole(7L, 1L)).thenReturn(GroupRole.OWNER.name());
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.leaveGroup(1L));
        assertEquals(ErrorCodes.GROUP_ACTION_FORBIDDEN, ex.getCode());
        assertEquals("OWNER 不能退出自己的组", ex.getMessage());
    }

    @Test
    void leaveGroupSucceedsForRegularMember() {
        when(membershipMapper.selectActiveMembershipRole(7L, 1L)).thenReturn(GroupRole.MEMBER.name());
        service.leaveGroup(1L);
        verify(membershipMapper).deleteMembership(1L, 7L);
    }
}