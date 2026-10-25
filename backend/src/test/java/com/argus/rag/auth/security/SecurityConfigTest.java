package com.argus.rag.auth.security;

import com.argus.rag.common.api.ApiResponse;
import com.argus.rag.common.enums.SystemRole;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 验证无状态 JWT 过滤链：未认证返回 JSON 401，合法 token 正常放行。
 */
@WebMvcTest(controllers = SecurityConfigTest.SecurityProbeController.class)
@Import({SecurityConfig.class, SecurityConfigTest.SecurityProbeController.class})
@DisplayName("Security 过滤链测试")
class SecurityConfigTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApplication {
    }

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private JwtAccessTokenService jwtAccessTokenService;

    @RestController
    static class SecurityProbeController {

        @GetMapping("/api/security/probe")
        public ApiResponse<String> probe() {
            return ApiResponse.success("ok");
        }
    }

    @Test
    @DisplayName("无 token 访问受保护接口返回 JSON 401")
    void shouldReturn401JsonWithoutToken() throws Exception {
        mockMvc.perform(get("/api/security/probe"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("未登录或登录已过期"));
    }

    @Test
    @DisplayName("携带合法 token 访问受保护接口返回 200")
    void shouldAcceptRequestWithValidToken() throws Exception {
        Instant now = Instant.now();
        JwtAccessTokenService.AccessTokenClaims claims = new JwtAccessTokenService.AccessTokenClaims(
                1L,
                "U100001",
                "QA 冒烟用户",
                SystemRole.USER,
                false,
                now,
                now.plusSeconds(300)
        );
        when(jwtAccessTokenService.parse("good-token")).thenReturn(claims);

        mockMvc.perform(get("/api/security/probe")
                        .header("Authorization", "Bearer good-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").value("ok"));
    }
}
