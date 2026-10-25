package com.argus.rag.config;

import com.argus.rag.auth.CurrentUserService;
import com.argus.rag.common.enums.SystemRole;
import com.argus.rag.common.exception.ForbiddenException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 生成式接口成本红线：默认关闭；开启后仅系统管理员可用。
 */
@DisplayName("图片/视频生成接口门禁（GenerationController）")
class GenerationControllerGuardTest {

    private AgnesAIService agnesAIService;
    private CurrentUserService currentUserService;
    private GenerationController controller;

    @BeforeEach
    void setUp() {
        agnesAIService = mock(AgnesAIService.class);
        currentUserService = mock(CurrentUserService.class);
        controller = new GenerationController(agnesAIService, currentUserService);
        ReflectionTestUtils.setField(controller, "enabled", false);
    }

    private void loginAs(SystemRole role) {
        when(currentUserService.getRequiredCurrentUser()).thenReturn(
                new CurrentUserService.CurrentUser(1L, "U1", "用户", role, false));
    }

    @Test
    void disabledByDefaultEvenForAdmin() {
        loginAs(SystemRole.ADMIN);
        assertThrows(ForbiddenException.class,
                () -> controller.generateImage(Map.of("prompt", "cat")));
        assertThrows(ForbiddenException.class,
                () -> controller.generateVideo(Map.of("prompt", "cat")));
    }

    @Test
    void enabledButNonAdminRejected() {
        ReflectionTestUtils.setField(controller, "enabled", true);
        loginAs(SystemRole.USER);
        assertThrows(ForbiddenException.class,
                () -> controller.generateImage(Map.of("prompt", "cat")));
    }

    @Test
    void enabledAdminCanGenerate() {
        ReflectionTestUtils.setField(controller, "enabled", true);
        loginAs(SystemRole.ADMIN);
        Mockito.when(agnesAIService.generateImage(Mockito.anyString(), Mockito.any(),
                        Mockito.anyString(), Mockito.anyInt()))
                .thenReturn(new com.argus.rag.common.api.ApiResponse<>(true, Map.of(), null));

        var response = controller.generateImage(Map.of("prompt", "cat"));
        assertEquals(true, response.success());
    }
}
