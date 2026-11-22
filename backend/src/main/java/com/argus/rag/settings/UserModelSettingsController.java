package com.argus.rag.settings;

import com.argus.rag.common.api.ApiResponse;
import com.argus.rag.common.security.UserContext;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 模型配置端点（BYOK，配置方式参考 Smart Draw 的"连接"分区并服务端化）。
 *
 * <p>作用域映射：ADMIN 操作平台默认行（user_id NULL），USER/FINANCE 操作本人行。
 * 密钥永不回传明文（GET 只给掩码；PUT apiKey 留空 = 沿用已存密钥）。</p>
 */
@RestController
@RequestMapping("/api/settings/model")
public class UserModelSettingsController {

    private final UserChatModelService service;

    public UserModelSettingsController(UserChatModelService service) {
        this.service = service;
    }

    /** 查看当前作用域的模型配置（密钥为掩码）。 */
    @GetMapping
    public ApiResponse<UserChatModelService.ModelConfigView> view() {
        return ApiResponse.success(service.viewForCurrentUser());
    }

    /** 保存当前作用域的模型配置。apiKey 留空 = 沿用已存密钥。 */
    @PutMapping
    public ApiResponse<Void> save(@RequestBody UserChatModelService.SaveModelConfigRequest request) {
        service.saveForCurrentUser(request);
        return ApiResponse.success(null);
    }

    /** 清除当前作用域的模型配置（恢复平台默认 → 全局 .env 回落链）。 */
    @DeleteMapping
    public ApiResponse<Void> clear() {
        service.clearForCurrentUser();
        return ApiResponse.success(null);
    }

    /** 连通性测试：真实调用一次 chat。apiKey 留空 = 使用已存密钥。 */
    @PostMapping("/test")
    public ApiResponse<UserChatModelService.TestConnectionResult> test(
            @RequestBody UserChatModelService.SaveModelConfigRequest request) {
        return ApiResponse.success(service.test(request));
    }
}
