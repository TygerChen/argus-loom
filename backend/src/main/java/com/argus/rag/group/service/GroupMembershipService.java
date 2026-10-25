package com.argus.rag.group.service;

import com.argus.rag.auth.CurrentUserService;
import com.argus.rag.common.enums.GroupInvitationStatus;
import com.argus.rag.common.enums.GroupRole;
import com.argus.rag.common.exception.BusinessException;
import com.argus.rag.common.exception.ErrorCodes;
import com.argus.rag.group.mapper.GroupMembershipMapper;
import com.argus.rag.group.model.entity.Group;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.ArrayList;
import java.util.Map;

/**
 * 群组成员关系服务，提供群组可见性查询、权限校验等功能。
 *
 * <p><b>这是知识库权限的"户口查询处"</b>：所有"这个用户是不是群成员？
 * 是不是 OWNER？他能看到哪些群？"的判断都从这里出（03 篇的房卡总台）。
 * listVisibleGroupsForUser 决定检索路由的候选集（04 篇第 ⑤ 步权限过滤
 * 的第一道）；requireOwner / requireMember 被 GroupManagementService
 * 的每个管理动作调用。错误信息常量（NON_MEMBER_MESSAGE 等）统一收口，
 * 提示语一致且不泄露群是否存在。</p>
 */
@Slf4j
@Service
public class GroupMembershipService {

    private static final String NON_MEMBER_MESSAGE = "当前用户不是目标群组成员";
    private static final String NON_OWNER_MESSAGE = "当前用户不是目标群组 OWNER";
    private static final String EXISTING_MEMBER_MESSAGE = "被邀请人已是群组成员";
    private static final String EXISTING_PENDING_INVITATION_MESSAGE = "已存在待处理邀请";
    private final GroupMembershipMapper groupMembershipMapper;
    private final CurrentUserService currentUserService;

    public GroupMembershipService(
            GroupMembershipMapper groupMembershipMapper,
            CurrentUserService currentUserService
    ) {
        this.groupMembershipMapper = groupMembershipMapper;
        this.currentUserService = currentUserService;
    }

    /** 查询当前用户可见的群组列表（拥有的、加入的、待处理的邀请） */
    public GroupQueryResult listVisibleGroups() {
        CurrentUserService.CurrentUser currentUser = currentUserService.requireBusinessUser();
        return new GroupQueryResult(
                toVisibleGroups(groupMembershipMapper.selectOwnedGroupsByUserId(currentUser.userId())),
                toVisibleGroups(groupMembershipMapper.selectJoinedGroupsByUserId(currentUser.userId())),
                toPendingInvitations(groupMembershipMapper.selectPendingInvitationsByInviteeUserId(currentUser.userId()))
        );
    }

    /** 查询指定用户可见（拥有或已加入）的活跃知识库组列表，供自动路由使用。 */
    public List<VisibleGroup> listVisibleGroupsForUser(Long userId) {
        Long validUserId = requireUserId(userId);
        List<VisibleGroup> groups = new ArrayList<>();
        groups.addAll(toVisibleGroups(groupMembershipMapper.selectOwnedGroupsByUserId(validUserId)));
        groups.addAll(toVisibleGroups(groupMembershipMapper.selectJoinedGroupsByUserId(validUserId)));
        return List.copyOf(groups);
    }

    /** 要求当前用户为目标群组成员，否则抛出异常，返回当前用户 */
    public CurrentUserService.CurrentUser requireCurrentUserMember(Long groupId) {
        return requireGroupReadable(groupId);
    }

    /** 要求当前用户可读目标群组（即为其成员），否则抛出异常，返回当前用户 */
    public CurrentUserService.CurrentUser requireGroupReadable(Long groupId) {
        CurrentUserService.CurrentUser currentUser = currentUserService.requireBusinessUser();
        String role = groupMembershipMapper.selectActiveMembershipRole(currentUser.userId(), requireGroupId(groupId));
        if (role == null) {
            throw new BusinessException(NON_MEMBER_MESSAGE);
        }
        return currentUser;
    }

    /** 要求当前用户为目标群组 OWNER，否则抛出异常，返回当前用户 */
    public CurrentUserService.CurrentUser requireGroupOwner(Long groupId) {
        CurrentUserService.CurrentUser currentUser = currentUserService.requireBusinessUser();
        String role = groupMembershipMapper.selectActiveMembershipRole(currentUser.userId(), requireGroupId(groupId));
        if (role == null) {
            throw new BusinessException(NON_MEMBER_MESSAGE);
        }
        if (!GroupRole.OWNER.name().equals(role)) {
            throw new BusinessException(NON_OWNER_MESSAGE);
        }
        return currentUser;
    }

    /** 创建待处理的邀请（由其他服务内部调用，调用方需确保已通过权限校验） */
    @Transactional
    public void createPendingInvitation(Long groupId, Long inviteeUserId) {
        Long requiredGroupId = requireGroupId(groupId);
        Long requiredInviteeUserId = requireUserId(inviteeUserId);
        CurrentUserService.CurrentUser currentUser = requireGroupOwner(requiredGroupId);
        rejectDuplicateInvitationTarget(requiredGroupId, requiredInviteeUserId);
        groupMembershipMapper.insertPendingInvitation(
                requiredGroupId,
                currentUser.userId(),
                requiredInviteeUserId,
                GroupInvitationStatus.PENDING.name()
        );
        log.info("创建待处理邀请: groupId={}, inviterUserId={}, inviteeUserId={}", requiredGroupId, currentUser.userId(), requiredInviteeUserId);
    }

    private void rejectDuplicateInvitationTarget(Long groupId, Long inviteeUserId) {
        if (hasRows(groupMembershipMapper.countMembershipByGroupIdAndUserId(groupId, inviteeUserId))) {
            throw new BusinessException(EXISTING_MEMBER_MESSAGE);
        }
        if (hasRows(groupMembershipMapper.countPendingInvitation(groupId, inviteeUserId))) {
            throw new BusinessException(EXISTING_PENDING_INVITATION_MESSAGE);
        }
    }

    private static Long requireGroupId(Long groupId) {
        if (groupId == null || groupId <= 0) {
            throw new BusinessException(ErrorCodes.GROUP_INVALID_TARGET, "groupId 非法");
        }
        return groupId;
    }

    private static Long requireUserId(Long userId) {
        if (userId == null || userId <= 0) {
            throw new BusinessException(ErrorCodes.GROUP_INVALID_TARGET, "被邀请用户非法");
        }
        return userId;
    }

    private static boolean hasRows(Long count) {
        return count != null && count > 0;
    }

    private List<VisibleGroup> toVisibleGroups(List<Map<String, Object>> rows) {
        return rows.stream().map(GroupMembershipService::toVisibleGroup).toList();
    }

    private List<PendingInvitationItem> toPendingInvitations(List<Map<String, Object>> rows) {
        return rows.stream().map(GroupMembershipService::toPendingInvitationItem).toList();
    }


    private static VisibleGroup toVisibleGroup(Map<String, Object> row) {
        Number groupId = (Number) row.get("groupId");
        Number pendingRequestCount = (Number) row.get("pendingRequestCount");
        return new VisibleGroup(
                groupId.longValue(), 
                String.valueOf(row.get("groupCode")), 
                String.valueOf(row.get("groupName")),
                row.get("description") == null ? "" : String.valueOf(row.get("description")),
                pendingRequestCount == null ? 0L : pendingRequestCount.longValue(),
                String.valueOf(row.get("createdAt"))
        );
    }

    private static PendingInvitationItem toPendingInvitationItem(Map<String, Object> row) {
        Number invitationId = (Number) row.get("invitationId");
        Number groupId = (Number) row.get("groupId");
        Number inviterUserId = (Number) row.get("inviterUserId");

        return new PendingInvitationItem(
                invitationId.longValue(),
                groupId.longValue(),
                String.valueOf(row.get("groupCode")),
                String.valueOf(row.get("groupName")),
                row.get("description") == null ? "" : String.valueOf(row.get("description")),
                String.valueOf(row.get("createdAt")),
                inviterUserId.longValue(),
                String.valueOf(row.get("inviterDisplayName")),
                String.valueOf(row.get("status"))
        );
    }

    private Long toLong(Object value) {
        return ((Number) value).longValue();
    }

    /** 群组查询结果，包含拥有的群组、加入的群组和待处理邀请 */
    public record GroupQueryResult(
            /** 拥有的群组列表 */
            List<VisibleGroup> ownedGroups,
            /** 加入的群组列表（不含已拥有的） */
            List<VisibleGroup> joinedGroups,
            /** 待处理的邀请列表 */
            List<PendingInvitationItem> pendingInvitations
    ) {
    }

    public record VisibleGroup(
            /** 群组 ID */
            Long groupId,
            /** 群组编码 */
            String groupCode,
            /** 群组名称 */
            String groupName,
            /** 群组描述 */
            String description,
            /** 待处理的申请数量 */
            Long pendingRequestCount,
            /** 创建时间 */
            String createdAt
    ) {
    }

    /** 待处理邀请信息 */
    public record PendingInvitationItem(
            Long invitationId,
            Long groupId,
            String groupCode,
            String groupName,
            String description,
            String createdAt,
            Long inviterUserId,
            String inviterDisplayName,
            String status
    ) {
    }
}
