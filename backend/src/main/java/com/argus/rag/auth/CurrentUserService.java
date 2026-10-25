package com.argus.rag.auth;

import com.argus.rag.common.security.AuthenticatedUser;
import com.argus.rag.common.security.UserContext;
import com.argus.rag.common.enums.SystemRole;
import com.argus.rag.common.enums.UserStatus;
import com.argus.rag.common.exception.BusinessException;
import com.argus.rag.common.exception.ErrorCodes;
import com.argus.rag.common.exception.ForbiddenException;
import com.argus.rag.common.exception.UnauthorizedException;
import com.argus.rag.user.service.UserQueryService;
import org.springframework.stereotype.Service;

/**
 * 当前用户服务，通过 {@link UserContext} 获取当前请求用户。
 * <p>
 * 依赖 JwtAuthenticationFilter 在请求进入时设置 {@link UserContext}。
 */
@Service
public class CurrentUserService {

    private final UserQueryService userQueryService;

    public CurrentUserService(UserQueryService userQueryService) {
        this.userQueryService = userQueryService;
    }

    /** 获取当前登录用户，未登录抛出 401 */
    public CurrentUser getRequiredCurrentUser() {
        AuthenticatedUser authenticatedUser = UserContext.get();
        if (authenticatedUser != null) {
            return loadUserById(authenticatedUser.userId());
        }
        throw new UnauthorizedException("当前请求未登录");
    }

    /** 要求当前用户为系统管理员，否则抛出 403 */
    public CurrentUser requireSystemAdmin() {
        CurrentUser currentUser = getRequiredCurrentUser();
        if (currentUser.systemRole() != SystemRole.ADMIN) {
            throw new ForbiddenException("当前用户不是系统管理员");
        }
        return currentUser;
    }

    /** 当前用户是否财务/领导角色（两者统一为 FINANCE 角色） */
    public boolean isFinance() {
        return getRequiredCurrentUser().systemRole() == SystemRole.FINANCE;
    }

    /** 指定用户是否财务/领导角色（两者统一为 FINANCE 角色）；{@code null} 安全。 */
    public static boolean isFinanceUser(CurrentUser user) {
        return user != null && user.systemRole() == SystemRole.FINANCE;
    }

    /** 要求当前用户为财务/领导角色，否则抛出 403 */
    public CurrentUser requireFinance() {
        CurrentUser currentUser = getRequiredCurrentUser();
        if (currentUser.systemRole() != SystemRole.FINANCE) {
            throw new ForbiddenException("财务数据仅限财务/领导角色访问");
        }
        return currentUser;
    }

    /** 要求当前用户为业务用户（非管理员），否则抛出 403；财务角色也属于业务用户 */
    public CurrentUser requireBusinessUser() {
        CurrentUser currentUser = getRequiredCurrentUser();
        if (currentUser.systemRole() == SystemRole.ADMIN) {
            throw new ForbiddenException("系统管理员不能访问普通业务区");
        }
        return currentUser;
    }

    /** 从数据库加载用户并校验状态 */
    private CurrentUser loadUserById(Long userId) {
        UserQueryService.UserRecord user = userQueryService.findById(userId);
        if (user == null) {
            throw new BusinessException(ErrorCodes.AUTH_USER_NOT_FOUND, "当前用户不存在");
        }
        if (user.status() == UserStatus.DISABLED) {
            throw new ForbiddenException("账号已被禁用");
        }
        return new CurrentUser(
                user.userId(),
                user.userCode(),
                user.displayName(),
                user.systemRole(),
                user.mustChangePassword()
        );
    }

    /** 当前登录用户信息 */
    public record CurrentUser(
            Long userId,
            String userCode,
            String displayName,
            SystemRole systemRole,
            boolean mustChangePassword
    ) {
        public CurrentUser(Long userId, String userCode, String displayName) {
            this(userId, userCode, displayName, SystemRole.USER, false);
        }
    }
}
