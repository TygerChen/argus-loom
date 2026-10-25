package com.argus.rag.qa.controller;

import com.argus.rag.auth.CurrentUserService;
import com.argus.rag.config.RetrievalOptionsConfig;
import com.argus.rag.common.exception.GlobalExceptionHandler;
import com.argus.rag.common.exception.ForbiddenException;
import com.argus.rag.qa.rag.RetrievalOptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityFilterAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 检索参数诊断端点契约（方案任务 5 第 6 点）。
 * <p>两条红线：普通用户 403（不暴露内部检索参数）；管理员可见当前生效的参数值。
 * 显式排除 security 过滤器，验证控制器内服务层守卫本身就 fail-closed。</p>
 */
@WebMvcTest(
        controllers = RetrievalDiagnosticsController.class,
        excludeAutoConfiguration = {
                SecurityAutoConfiguration.class,
                SecurityFilterAutoConfiguration.class
        }
)
@Import({RetrievalDiagnosticsController.class, GlobalExceptionHandler.class, RetrievalOptionsConfig.class})
@DisplayName("检索参数诊断端点（管理员只读）")
class RetrievalDiagnosticsControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CurrentUserService currentUserService;

    @Test
    @DisplayName("非管理员访问诊断端点返回 403")
    void rejectsNonAdmin() throws Exception {
        when(currentUserService.requireSystemAdmin())
                .thenThrow(new ForbiddenException("当前用户不是系统管理员"));
        mockMvc.perform(get("/api/admin/retrieval/options"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("管理员可读取当前生效的检索参数")
    void exposesCurrentOptionsToAdmin() throws Exception {
        mockMvc.perform(get("/api/admin/retrieval/options"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.channelTopK").value(50))
                .andExpect(jsonPath("$.data.recallK").value(50))
                .andExpect(jsonPath("$.data.maxGlobalDocuments").value(50))
                .andExpect(jsonPath("$.data.maxChunksPerDocument").value(0));
    }
}
