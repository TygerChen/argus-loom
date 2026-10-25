package com.argus.rag.auth.security;

import com.argus.rag.common.api.ApiResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import java.io.IOException;

/**
 * Spring Security 统一接入层：无状态 JWT 认证 + JSON 401/403 响应。
 * <p>
 * 业务层已有的 {@code UserContext} / {@code CurrentUserService} 校验保持不变，
 * 这里负责把未认证请求挡在过滤器链上，避免手写 filter 各自为政。
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /**
     * 声明式空用户源，仅用于让 Spring Boot 不再自动生成随机的 in-memory 默认密码
     * （那条 “Using generated security password” 日志）。本系统使用无状态 JWT，认证在
     * {@link JwtAuthenticationFilter} 完成，不依赖任何 form/用户名密码校验，故这里对任意
     * 用户名一律判「用户不存在」，不引入任何真实用户。
     */
    @Bean
    public UserDetailsService userDetailsService() {
        return username -> {
            throw new UsernameNotFoundException("用户不存在：" + username);
        };
    }

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            JwtAuthenticationFilter jwtAuthenticationFilter,
            ObjectMapper objectMapper
    ) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(Customizer.withDefaults())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(
                                "/api/auth/login",
                                "/api/auth/register",
                                "/api/auth/refresh",
                                "/api/auth/logout"
                        ).permitAll()
                        .requestMatchers(
                                "/actuator/health",
                                "/actuator/health/**",
                                "/actuator/info",
                                "/actuator/prometheus"
                        ).permitAll()
                        .requestMatchers(
                                "/doc.html",
                                "/swagger-ui/**",
                                "/swagger-ui.html",
                                "/v3/api-docs/**",
                                "/webjars/**",
                                "/favicon.ico",
                                "/error"
                        ).permitAll()
                        .requestMatchers("/ws/**").permitAll()
                        // 员工考勤/薪酬自助：任何登录角色都可查询本人数据，URL 避开 /api/finance/**。
                        .requestMatchers("/api/finance/hr/self/**").authenticated()
                        // 财务数据隔离（P0）：财务/领导统一使用 FINANCE 角色，普通员工与系统管理员一律 403。
                        .requestMatchers("/api/finance/**").hasAuthority("ROLE_FINANCE")
                        .anyRequest().authenticated())
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(restAuthenticationEntryPoint(objectMapper))
                        .accessDeniedHandler(restAccessDeniedHandler(objectMapper)))
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    public JwtAuthenticationFilter jwtAuthenticationFilter(
            JwtAccessTokenService jwtAccessTokenService,
            ObjectMapper objectMapper
    ) {
        return new JwtAuthenticationFilter(jwtAccessTokenService, objectMapper);
    }

    private AuthenticationEntryPoint restAuthenticationEntryPoint(ObjectMapper objectMapper) {
        return (request, response, authException) ->
                writeJson(response, objectMapper, HttpServletResponse.SC_UNAUTHORIZED, "未登录或登录已过期");
    }

    private AccessDeniedHandler restAccessDeniedHandler(ObjectMapper objectMapper) {
        return (request, response, accessDeniedException) ->
                writeJson(response, objectMapper, HttpServletResponse.SC_FORBIDDEN, "无权访问该资源");
    }

    private void writeJson(
            HttpServletResponse response,
            ObjectMapper objectMapper,
            int status,
            String message
    ) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getWriter(), new ApiResponse<>(false, null, message));
    }
}
