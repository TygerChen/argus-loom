package com.argus.rag.auth.security;

import com.argus.rag.common.api.ApiResponse;
import com.argus.rag.common.exception.BusinessException;
import com.argus.rag.common.enums.SystemRole;
import com.argus.rag.common.security.AuthenticatedUser;
import com.argus.rag.common.security.UserContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

// 教学导读（02 篇）：这是每个请求的"第一站"——扫手环的闸机。
// OncePerRequestFilter 保证一个请求只过一次（转发/包含不会重复触发）。
// 四个放行路径是"买手环的地方"——登录/注册/刷新/登出本身不能要求已登录，
// 否则鸡生蛋死循环。验签通过后把用户身份写进 SecurityContextHolder，
// 之后整条调用链（控制器/服务/@PreAuthorize）都能拿到"你是谁"；
// 验签失败直接 401 JSON 响应，连控制器的门都摸不到。
/**
 * JWT 认证过滤器 —— 每个 API 请求过安检闸机的地方。
 *
 * <p><b>干什么</b>：从 Authorization 头取 Bearer token，验签通过后把用户身份
 * 写进<b>两本登记簿</b>，之后整条调用链才能拿到"你是谁"：
 * <ul>
 *   <li><b>SecurityContextHolder</b>（Spring Security 官方登记簿）：
 *       存角色权威（ROLE_ADMIN/FINANCE/USER），@PreAuthorize、Security 配置
 *       做鉴权决策时查这本；</li>
 *   <li><b>UserContext</b>（本项目自建 ThreadLocal 登记簿）：
 *       业务代码随手可取（DocumentRoleGate、CurrentUserService 都从这拿人），
 *       不用一路把用户参数往下传。</li>
 * </ul>
 * 为什么两本都要写？Spring Security 的鉴权体系只认它自己的上下文，
 * 而业务代码直读 Spring Security 的 UserDetails 很啰嗦——
 * UserContext 是给业务代码的"便民窗口"。</p>
 *
 * <p><b>三个新手最容易忽略的细节</b>：</p>
 * <ul>
 *   <li><b>finally 里必须 clearContext/clear</b>：Tomcat 用线程池复用请求线程，
 *       不清理的话，下一个复用这条线程的请求可能"继承"上一个用户的身份——
 *       ThreadLocal 泄漏是权限事故的高发地。这是面试必考点
 *       （"ThreadLocal 为什么会有内存泄漏/越权风险"）；</li>
 *   <li><b>token 为空 ≠ 立刻拒绝</b>：没有 Authorization 头直接放行过闸机
 *       （交给后面的 Security 规则决定是否 401），但带了头却验不过 → 直接 401 JSON；
 *       两者的区别是：前者可能是匿名可访问的接口，后者是"提供了假票"；</li>
 *   <li><b>shouldNotFilter 放行 /api/auth/*</b>：登录/注册/刷新/登出本身
 *       不能要求已登录，否则鸡生蛋死循环。</li>
 * </ul>
 */
@Slf4j
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";
    /** 无需认证的路径 */
    private static final String LOGIN_PATH = "/api/auth/login";
    private static final String REGISTER_PATH = "/api/auth/register";
    private static final String REFRESH_PATH = "/api/auth/refresh";
    private static final String LOGOUT_PATH = "/api/auth/logout";

    private final JwtAccessTokenService jwtAccessTokenService;
    private final ObjectMapper objectMapper;

    public JwtAuthenticationFilter(
            JwtAccessTokenService jwtAccessTokenService,
            ObjectMapper objectMapper
    ) {
        this.jwtAccessTokenService = jwtAccessTokenService;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (authorization == null) {
            log.debug("请求无 Authorization header: path={}", request.getRequestURI());
            filterChain.doFilter(request, response);
            return;
        }
        String accessToken;
        if (authorization.startsWith(BEARER_PREFIX)) {
            accessToken = authorization.substring(BEARER_PREFIX.length()).trim();
        } else {
            accessToken = authorization.trim();
        }
        if (accessToken.isEmpty()) {
            writeUnauthorized(response, "access token 非法或已过期");
            return;
        }
        try {
            JwtAccessTokenService.AccessTokenClaims claims = jwtAccessTokenService.parse(accessToken);
            log.debug("JWT 认证成功: userId={}, path={}", claims.userId(), request.getRequestURI());
            UserContext.set(new AuthenticatedUser(
                    claims.userId(),
                    claims.userCode(),
                    claims.displayName(),
                    claims.systemRole(),
                    claims.mustChangePassword()
            ));
            String role = switch (claims.systemRole()) {
                case ADMIN -> "ROLE_ADMIN";
                case FINANCE -> "ROLE_FINANCE";
                default -> "ROLE_USER";
            };
            UsernamePasswordAuthenticationToken authentication =
                    new UsernamePasswordAuthenticationToken(
                            claims.userId(),
                            null,
                            List.of(new SimpleGrantedAuthority(role))
                    );
            SecurityContextHolder.getContext().setAuthentication(authentication);
            filterChain.doFilter(request, response);
        } catch (BusinessException exception) {
            log.debug("JWT 认证失败: {} path={}", exception.getMessage(), request.getRequestURI());
            writeUnauthorized(response, exception.getMessage());
        } finally {
            SecurityContextHolder.clearContext();
            UserContext.clear();
        }
    }

    /** 认证白名单路径跳过过滤 */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String requestUri = request.getRequestURI();
        return LOGIN_PATH.equals(requestUri)
                || REGISTER_PATH.equals(requestUri)
                || REFRESH_PATH.equals(requestUri)
                || LOGOUT_PATH.equals(requestUri);
    }

    private void writeUnauthorized(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getWriter(), new ApiResponse<>(false, null, message));
    }

}
