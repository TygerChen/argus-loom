package com.argus.rag.user.service;

import com.argus.rag.auth.security.RefreshTokenService;
import com.argus.rag.common.enums.SystemRole;
import com.argus.rag.common.enums.UserStatus;
import com.argus.rag.common.exception.BusinessException;
import com.argus.rag.common.exception.ErrorCodes;
import com.argus.rag.user.mapper.UserMapper;
import com.argus.rag.user.model.dto.UpdateUserStatusRequest;
import com.argus.rag.user.model.dto.ResetUserPasswordRequest;
import com.argus.rag.user.model.entity.User;
import com.argus.rag.user.model.vo.AdminUserItemResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.argus.rag.auth.service.PasswordHasher;

import java.util.List;

/**
 * 管理员用户管理服务。
 */
@Slf4j
@Service
public class AdminUserService {

    private final UserMapper userMapper;
    private final RefreshTokenService refreshTokenService;
    private final UserQueryService userQueryService;
    private final PasswordHasher passwordHasher;
    private final com.argus.rag.auth.service.PasswordPolicyValidator passwordPolicyValidator;

    public AdminUserService(
            UserMapper userMapper,
            RefreshTokenService refreshTokenService,
            UserQueryService userQueryService,
            PasswordHasher passwordHasher,
            com.argus.rag.auth.service.PasswordPolicyValidator passwordPolicyValidator
    ) {
        this.userMapper = userMapper;
        this.refreshTokenService = refreshTokenService;
        this.userQueryService = userQueryService;
        this.passwordHasher = passwordHasher;
        this.passwordPolicyValidator = passwordPolicyValidator;
    }

    /** 获取所有用户列表 */
    public List<AdminUserItemResponse> listUsers() {
        return userQueryService.listUsers();
    }

    /** 获取单个用户 */
    public AdminUserItemResponse getUser(Long userId) {
        return userQueryService.getUser(requireUserId(userId));
    }

    /** 修改用户状态，禁用时同时吊销其所有 refresh token */
    @Transactional
    public void updateUserStatus(Long userId, UpdateUserStatusRequest request) {
        User user = userMapper.selectById(requireUserId(userId));
        if (user == null) {
            throw new BusinessException(ErrorCodes.USER_NOT_FOUND, "用户不存在");
        }
        user.setStatus(request.status());
        userMapper.updateById(user);
        if (request.status() == UserStatus.DISABLED) {
            refreshTokenService.revokeActiveTokens(userId);
        }
        log.info("管理员修改用户状态: userId={}, status={}", userId, request.status());
    }

    /** 修改用户系统角色；角色变更后吊销 refresh token，强制用户重新登录获得新权限。 */
    @Transactional
    public void updateUserRole(Long userId, SystemRole newRole) {
        if (newRole == null) {
            throw new BusinessException("系统角色不能为空");
        }
        User user = userMapper.selectById(requireUserId(userId));
        if (user == null) {
            throw new BusinessException(ErrorCodes.USER_NOT_FOUND, "用户不存在");
        }
        if (user.getSystemRole() == newRole) {
            throw new BusinessException(ErrorCodes.USER_ROLE_ALREADY_SET, "用户已是该角色，无需重复设置");
        }
        user.setSystemRole(newRole);
        userMapper.updateById(user);
        refreshTokenService.revokeActiveTokens(userId);
        log.info("管理员调整用户角色: userId={}, newRole={}", userId, newRole);
    }

    /** 管理员重置密码，并强制用户重新登录后修改。 */
    @Transactional
    public void resetUserPassword(Long userId, ResetUserPasswordRequest request) {
        if (request == null || request.newPassword() == null || request.newPassword().isBlank()) {
            throw new BusinessException("新密码不能为空");
        }
        // 与注册/自助改密同一策略：管理员重置不能绕过复杂度校验（曾绕过，可重置出弱密码）
        passwordPolicyValidator.validate(request.newPassword());
        User user = userMapper.selectById(requireUserId(userId));
        if (user == null) {
            throw new BusinessException(ErrorCodes.USER_NOT_FOUND, "用户不存在");
        }
        user.setPasswordHash(passwordHasher.hash(request.newPassword()));
        user.setMustChangePassword(true);
        userMapper.updateById(user);
        refreshTokenService.revokeActiveTokens(userId);
        log.info("管理员重置密码: userId={}", userId);
    }

    private static Long requireUserId(Long userId) {
        if (userId == null || userId <= 0) {
            throw new BusinessException("用户ID非法");
        }
        return userId;
    }
}
