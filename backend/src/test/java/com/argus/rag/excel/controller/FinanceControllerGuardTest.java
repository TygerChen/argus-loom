package com.argus.rag.excel.controller;

import com.argus.rag.auth.CurrentUserService;
import com.argus.rag.common.enums.SystemRole;
import com.argus.rag.common.exception.ForbiddenException;
import com.argus.rag.common.exception.GlobalExceptionHandler;
import com.argus.rag.excel.service.FinanceImportService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityFilterAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 服务层财务守卫（P0 双保险）：即使 Spring Security 的 URL 过滤规则被绕过
 * （本测试显式排除 security 过滤器），FinanceImportController 内部的
 * {@code requireFinanceAdmin()} 仍会拒绝非管理员，阻止越权访问财务数据。
 */
@WebMvcTest(
        controllers = FinanceImportController.class,
        excludeAutoConfiguration = {
                SecurityAutoConfiguration.class,
                SecurityFilterAutoConfiguration.class
        }
)
@Import({FinanceImportController.class, GlobalExceptionHandler.class})
@DisplayName("财务 Controller 服务层守卫测试")
class FinanceControllerGuardTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApplication {
    }

    @Autowired
    private MockMvc mockMvc;

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
                .thenReturn(Map.of("ok", 1));
    }

    @Test
    @DisplayName("非财务/非领导角色调用财务接口时服务层守卫抛 403")
    void controllerRejectsNonAdminEvenWithoutSecurityFilter() throws Exception {
        when(currentUserService.requireFinance())
                .thenThrow(new ForbiddenException("当前用户不是财务/领导角色"));

        mockMvc.perform(get("/api/finance/overview"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    @DisplayName("财务/领导角色调用财务接口时服务层守卫放行 200")
    void controllerAllowsAdmin() throws Exception {
        when(currentUserService.requireFinance())
                .thenReturn(new CurrentUserService.CurrentUser(
                        1L, "U100001", "财务", SystemRole.FINANCE, false));

        mockMvc.perform(get("/api/finance/overview"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }
}
