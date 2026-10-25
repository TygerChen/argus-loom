package com.argus.rag.group.service;

import com.argus.rag.auth.CurrentUserService;
import com.argus.rag.common.enums.GroupInvitationStatus;
import com.argus.rag.common.exception.BusinessException;
import com.argus.rag.common.exception.ErrorCodes;
import com.argus.rag.group.mapper.GroupMembershipMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link GroupMembershipService} 群组成员语义契约：
 * - 可见性（成员/OWNER / 非法 groupId,userId→{@code GROUP_INVALID_TARGET}）；
 * - 待处理邀请去重（已是成员 / 已有待处理）；
 * - DTO 转换的 null 缺省语义（description 缺省空串、pendingRequestCount 缺省 0）。
 * 双参异常按 errorCode 断言，单参文案按 message 断言。纯 mock 2 依赖，无 DB。
 */
@DisplayName("群组成员关系（GroupMembershipService）")
class GroupMembershipServiceTest {

    private GroupMembershipMapper membershipMapper;
    private GroupMembershipService service;
    private final CurrentUserService.CurrentUser currentUser =
            new CurrentUserService.CurrentUser(7L, "U7", "小会计");

    @BeforeEach
    void setUp() {
        membershipMapper = mock(GroupMembershipMapper.class);
        CurrentUserService userService = mock(CurrentUserService.class);
        when(userService.requireBusinessUser()).thenReturn(currentUser);
        service = new GroupMembershipService(membershipMapper, userService);
    }

    private Map<String, Object> visibleRow(Long id, Long pending, boolean withNulls) {
        Map<String, Object> row = new HashMap<>();
        row.put("groupId", id);
        row.put("groupCode", "G" + id);
        row.put("groupName", "组" + id);
        if (withNulls) {
            row.put("description", null);
            row.put("pendingRequestCount", null);
        } else {
            row.put("description", "描述");
            row.put("pendingRequestCount", pending);
        }
        row.put("createdAt", "2026-09-01");
        return row;
    }

    @Test
    void requireGroupReadableRejectsNonMember() {
        when(membershipMapper.selectActiveMembershipRole(7L, 1L)).thenReturn(null);
        BusinessException ex = assertThrows(BusinessException.class, () -> service.requireGroupReadable(1L));
        assertEquals("当前用户不是目标群组成员", ex.getMessage());
    }

    @Test
    void requireGroupReadableReturnsUserWhenMember() {
        when(membershipMapper.selectActiveMembershipRole(7L, 1L)).thenReturn("VIEWER");
        assertEquals(currentUser, service.requireGroupReadable(1L));
    }

    @Test
    void requireGroupReadableRejectsIllegalGroupId() {
        BusinessException ex = assertThrows(BusinessException.class, () -> service.requireGroupReadable(0L));
        assertEquals(ErrorCodes.GROUP_INVALID_TARGET, ex.getCode());
    }

    @Test
    void requireGroupOwnerRejectsNonMember() {
        when(membershipMapper.selectActiveMembershipRole(7L, 1L)).thenReturn(null);
        BusinessException ex = assertThrows(BusinessException.class, () -> service.requireGroupOwner(1L));
        assertEquals("当前用户不是目标群组成员", ex.getMessage());
    }

    @Test
    void requireGroupOwnerRejectsNonOwnerMember() {
        when(membershipMapper.selectActiveMembershipRole(7L, 1L)).thenReturn("VIEWER");
        BusinessException ex = assertThrows(BusinessException.class, () -> service.requireGroupOwner(1L));
        assertEquals("当前用户不是目标群组 OWNER", ex.getMessage());
    }

    @Test
    void requireGroupOwnerAcceptsOwner() {
        when(membershipMapper.selectActiveMembershipRole(7L, 1L)).thenReturn("OWNER");
        assertEquals(currentUser, service.requireGroupOwner(1L));
    }

    @Test
    void createPendingInvitationRejectsIllegalGroupId() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.createPendingInvitation(0L, 9L));
        assertEquals(ErrorCodes.GROUP_INVALID_TARGET, ex.getCode());
    }

    @Test
    void createPendingInvitationRejectsIllegalInvitee() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.createPendingInvitation(1L, 0L));
        assertEquals(ErrorCodes.GROUP_INVALID_TARGET, ex.getCode());
    }

    @Test
    void createPendingInvitationRejectsExistingMember() {
        when(membershipMapper.selectActiveMembershipRole(7L, 1L)).thenReturn("OWNER");
        when(membershipMapper.countMembershipByGroupIdAndUserId(1L, 9L)).thenReturn(1L);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.createPendingInvitation(1L, 9L));
        assertEquals("被邀请人已是群组成员", ex.getMessage());
    }

    @Test
    void createPendingInvitationRejectsPendingDuplicate() {
        when(membershipMapper.selectActiveMembershipRole(7L, 1L)).thenReturn("OWNER");
        when(membershipMapper.countMembershipByGroupIdAndUserId(1L, 9L)).thenReturn(0L);
        when(membershipMapper.countPendingInvitation(1L, 9L)).thenReturn(1L);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.createPendingInvitation(1L, 9L));
        assertEquals("已存在待处理邀请", ex.getMessage());
    }

    @Test
    void createPendingInvitationSucceedsAndPersistsPendingStatus() {
        when(membershipMapper.selectActiveMembershipRole(7L, 1L)).thenReturn("OWNER");
        when(membershipMapper.countMembershipByGroupIdAndUserId(1L, 9L)).thenReturn(0L);
        when(membershipMapper.countPendingInvitation(1L, 9L)).thenReturn(0L);

        service.createPendingInvitation(1L, 9L);

        verify(membershipMapper).insertPendingInvitation(1L, 7L, 9L, GroupInvitationStatus.PENDING.name());
    }

    @Test
    void listVisibleGroupsForUserRejectsIllegalUserId() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.listVisibleGroupsForUser(0L));
        assertEquals(ErrorCodes.GROUP_INVALID_TARGET, ex.getCode());
    }

    @Test
    void listVisibleGroupsForUserCombinesOwnedAndJoined() {
        when(membershipMapper.selectOwnedGroupsByUserId(9L))
                .thenReturn(List.of(visibleRow(1L, 1L, false)));
        when(membershipMapper.selectJoinedGroupsByUserId(9L))
                .thenReturn(List.of(visibleRow(2L, 2L, false)));

        List<GroupMembershipService.VisibleGroup> groups = service.listVisibleGroupsForUser(9L);

        assertEquals(2, groups.size());
        assertEquals(1L, groups.get(0).groupId());
        assertEquals(2L, groups.get(1).groupId());
        assertTrue(groups.get(0).description().equals("描述"));
        // 返回不可变拷贝
        assertThrows(UnsupportedOperationException.class, () -> groups.add(null));
    }

    @Test
    void visibleGroupConversionDefaultsNullDescriptionAndPendingCount() {
        when(membershipMapper.selectOwnedGroupsByUserId(9L))
                .thenReturn(List.of(visibleRow(5L, 0L, true)));

        GroupMembershipService.VisibleGroup g = service.listVisibleGroupsForUser(9L).get(0);

        assertEquals(5L, g.groupId());
        assertEquals("组5", g.groupName());
        assertEquals("", g.description());          // null → 空串缺省
        assertEquals(0L, g.pendingRequestCount());  // null → 0 缺省
    }

    @Test
    void pendingInvitationConversionMapsInviterAndStatus() {
        Map<String, Object> row = new HashMap<>();
        row.put("invitationId", 3L);
        row.put("groupId", 2L);
        row.put("groupCode", "G2");
        row.put("groupName", "组2");
        row.put("description", null);
        row.put("createdAt", "2026-01-01");
        row.put("inviterUserId", 5L);
        row.put("inviterDisplayName", "发起人");
        row.put("status", "PENDING");
        when(membershipMapper.selectPendingInvitationsByInviteeUserId(7L)).thenReturn(List.of(row));

        GroupMembershipService.PendingInvitationItem item =
                service.listVisibleGroups().pendingInvitations().get(0);

        assertEquals(3L, item.invitationId());
        assertEquals(2L, item.groupId());
        assertEquals(5L, item.inviterUserId());
        assertEquals("", item.description());
        assertEquals("PENDING", item.status());
    }
}