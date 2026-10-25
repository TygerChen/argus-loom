package com.argus.rag.config;

import com.argus.rag.common.api.ApiResponse;
import com.argus.rag.auth.CurrentUserService;
import com.argus.rag.common.enums.SystemRole;
import com.argus.rag.common.exception.ForbiddenException;
import org.springframework.web.bind.annotation.*;
import org.springframework.beans.factory.annotation.Value;

import java.util.Map;

/**
 * 生成式能力（图片/视频）的 HTTP 接口层。
 * <p>暴露 POST {@code /api/generate/image} 与 {@code /api/generate/video}，把请求体参数透传给底层 {@link AgnesAIService}，并用统一的 {@code ApiResponse} 包装返回。</p>
 * <p>成本红线：图片/视频生成按次计费且与知识库核心场景无关，默认关闭（{@code generation.enabled=false}）；
 * 开启后也仅系统管理员可用。</p>
 */
@RestController
@RequestMapping("/api")
public class GenerationController {
    private final AgnesAIService agnesAIService;
    private final CurrentUserService currentUserService;

    @Value("${generation.enabled:false}")
    private boolean enabled;

    public GenerationController(AgnesAIService agnesAIService, CurrentUserService currentUserService) {
        this.agnesAIService = agnesAIService;
        this.currentUserService = currentUserService;
    }

    /** 未开启或非管理员一律拒绝，避免公共 API Key 被普通用户消耗。 */
    private void requireEnabledAdmin() {
        if (!enabled) {
            throw new ForbiddenException("生成式功能未开启");
        }
        if (currentUserService.getRequiredCurrentUser().systemRole() != SystemRole.ADMIN) {
            throw new ForbiddenException("仅系统管理员可使用生成式功能");
        }
    }

    @PostMapping("/generate/image")
    public ApiResponse<Map<String, Object>> generateImage(@RequestBody Map<String, Object> body) {
        requireEnabledAdmin();
        String model = (String) body.getOrDefault("model", "agnes-image-2.5-flash");
        String prompt = (String) body.get("prompt");
        String size = (String) body.getOrDefault("size", "1024x1024");
        int n = body.containsKey("n") ? ((Number) body.get("n")).intValue() : 1;
        return agnesAIService.generateImage(model, prompt, size, n);
    }

    @PostMapping("/generate/video")
    public ApiResponse<Map<String, Object>> generateVideo(@RequestBody Map<String, Object> body) {
        requireEnabledAdmin();
        String model = (String) body.getOrDefault("model", "agnes-video-2.5-flash");
        String prompt = (String) body.get("prompt");
        return agnesAIService.generateVideo(model, prompt);
    }
}
