package com.argus.rag.excel.controller;

import com.argus.rag.auth.CurrentUserService;
import com.argus.rag.auth.security.JwtAccessTokenService;
import com.argus.rag.auth.security.SecurityConfig;
import com.argus.rag.common.enums.SystemRole;
import com.argus.rag.excel.service.FinanceImportService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 财务数据隔离（P0）：验证 Spring Security 过滤链对 /api/finance/** 的角色拦截。
 * <p>复用 SecurityConfigTest 的「mock JWT 角色 → 走真实过滤链」模式：
 * 普通用户(USER)访问财务接口应 403；财务/领导(FINANCE)应放行 200；
 * 系统管理员(ADMIN)不属于财务/领导，应 403（当前模型：领导统一为 FINANCE 角色）。</p>
 */
@WebMvcTest(controllers = FinanceImportController.class)
@Import({SecurityConfig.class, FinanceImportController.class})
@DisplayName("财务 API 端点级隔离测试")
class FinanceSecurityIsolationTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApplication {
    }

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private JwtAccessTokenService jwtAccessTokenService;

    @MockitoBean
    private FinanceImportService financeImportService;

    @MockitoBean
    private CurrentUserService currentUserService;

    @MockitoBean
    private com.argus.rag.excel.service.FinancePeriodService financePeriodService;

    @MockitoBean
    private com.argus.rag.excel.service.FinanceAnalysisService financeAnalysisService;

    @BeforeEach
    void setUp() {
        when(financeImportService.financeOverview(null, null, null, null, null, false))
                .thenReturn(new LinkedHashMap<>(Map.of("ok", 1)));
    }

    @Test
    @DisplayName("普通用户访问 /api/finance/overview 被拒绝（403）")
    void userCannotReadFinanceOverview() throws Exception {
        when(jwtAccessTokenService.parse("user-token"))
                .thenReturn(claimsFor(SystemRole.USER));

        mockMvc.perform(get("/api/finance/overview")
                        .header("Authorization", "Bearer user-token"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("财务/领导角色(FINANCE)访问 /api/finance/overview 被放行（200）")
    void financeCanReadFinanceOverview() throws Exception {
        when(jwtAccessTokenService.parse("finance-token"))
                .thenReturn(claimsFor(SystemRole.FINANCE));

        mockMvc.perform(get("/api/finance/overview")
                        .header("Authorization", "Bearer finance-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    @DisplayName("系统管理员(ADMIN)不属于财务/领导，访问 /api/finance/overview 被拒绝（403）")
    void adminCannotReadFinanceOverview() throws Exception {
        when(jwtAccessTokenService.parse("admin-token"))
                .thenReturn(claimsFor(SystemRole.ADMIN));

        mockMvc.perform(get("/api/finance/overview")
                        .header("Authorization", "Bearer admin-token"))
                .andExpect(status().isForbidden());
    }

    private JwtAccessTokenService.AccessTokenClaims claimsFor(SystemRole role) {
        Instant now = Instant.now();
        return new JwtAccessTokenService.AccessTokenClaims(
                1L,
                "T100001",
                "财务隔离冒烟用户",
                role,
                false,
                now,
                now.plusSeconds(300)
        );
    }
}
