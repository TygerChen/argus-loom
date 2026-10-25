package com.argus.rag.assistant.controller;

import com.argus.rag.assistant.model.dto.session.CreateAssistantSessionRequest;
import com.argus.rag.assistant.model.dto.session.UpdateAssistantSessionRequest;
import com.argus.rag.assistant.model.vo.session.AssistantSessionDetailVO;
import com.argus.rag.assistant.model.vo.session.AssistantSessionListItemVO;
import com.argus.rag.assistant.service.AssistantSessionService;
import com.argus.rag.auth.CurrentUserService;
import com.argus.rag.common.api.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 助手会话管理控制器。
 * <p>提供会话的 CRUD 操作接口，包括创建、查询列表、查看详情、重命名和删除会话。</p>
 * <ul>
 *   <li>POST /api/assistant/sessions - 创建新会话</li>
 *   <li>GET /api/assistant/sessions - 获取会话列表</li>
 *   <li>GET /api/assistant/sessions/{sessionId} - 获取会话详情</li>
 *   <li>PATCH /api/assistant/sessions/{sessionId} - 更新会话（重命名）</li>
 *   <li>DELETE /api/assistant/sessions/{sessionId} - 删除会话</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/assistant/sessions")
@Tag(name = "AI 会话管理", description = "助手会话的创建、查询、重命名、归档、删除")
public class AssistantSessionController {

    private final AssistantSessionService assistantSessionService;
    private final CurrentUserService currentUserService;

    public AssistantSessionController(AssistantSessionService assistantSessionService,
                                      CurrentUserService currentUserService) {
        this.assistantSessionService = assistantSessionService;
        this.currentUserService = currentUserService;
    }

    /**
     * 创建新的助手会话。
     * <p>为当前用户创建一个空的会话，会话标题默认为"新会话"。</p>
     *
     * @param requestBody 创建请求体（选填，当前未使用，保留扩展）
     * @return 创建成功的会话详情
     */
    @PostMapping
    public ApiResponse<AssistantSessionDetailVO> createSession(
            @RequestBody(required = false) CreateAssistantSessionRequest requestBody
    ) {
        return ApiResponse.success(assistantSessionService.createSession());
    }

    /**
     * 获取当前用户的会话列表。
     * <p>返回所有会话的简要信息，按最后消息时间降序排列。</p>
     *
     * @return 会话列表 VO
     */
    @GetMapping
    public List<AssistantSessionListItemVO> listSessions(
            @RequestParam(value = "includeArchived", defaultValue = "false") boolean includeArchived
    ) {
        return assistantSessionService.listSessions(includeArchived);
    }

    /**
     * 获取指定会话的详细信息。
     *
     * @param sessionId 会话ID，路径参数
     * @return 会话详情 VO
     */
    @GetMapping("/{sessionId}")
    public AssistantSessionDetailVO getSessionDetail(
            @PathVariable Long sessionId
    ) {
        return assistantSessionService.getSessionDetail(sessionId);
    }

    /**
     * 更新会话信息，支持重命名会话。
     * <p>请求体中携带新的标题，标题不能为空且长度不能超过 255 个字符。</p>
     *
     * @param sessionId   会话ID，路径参数
     * @param requestBody 更新请求体，包含新的标题
     * @return 更新后的会话详情
     */
    @PatchMapping("/{sessionId}")
    public ApiResponse<AssistantSessionDetailVO> updateSession(
            @PathVariable Long sessionId,
            @Valid @RequestBody UpdateAssistantSessionRequest requestBody
    ) {
        return ApiResponse.success(assistantSessionService.renameSession(sessionId, requestBody));
    }

    /**
     * 删除指定会话。
     * <p>同时删除会话下的所有消息记录和上下文记录。</p>
     *
     * @param sessionId 会话ID，路径参数
     * @return 成功响应，data 为 null
     */
    @DeleteMapping("/{sessionId}")
    public ApiResponse<Void> deleteSession(
            @PathVariable Long sessionId
    ) {
        assistantSessionService.deleteSession(sessionId);
        return ApiResponse.success(null);
    }

    /**
     * 归档指定会话。
     * <p>将当前用户的指定会话标记为已归档，归档会话默认不在列表中展示。</p>
     *
     * @param id 会话ID，路径参数
     * @return 成功响应
     */
    @PostMapping("/{id}/archive")
    public ApiResponse<Void> archive(@PathVariable Long id) {
        Long userId = currentUserService.requireBusinessUser().userId();
        assistantSessionService.archiveSession(userId, id);
        return ApiResponse.success(null);
    }

    /**
     * 取消归档指定会话。
     * <p>将已归档的会话恢复为正常状态，重新展示在会话列表中。</p>
     *
     * @param id 会话ID，路径参数
     * @return 成功响应
     */
    @PostMapping("/{id}/restore")
    public ApiResponse<Void> restore(@PathVariable Long id) {
        Long userId = currentUserService.requireBusinessUser().userId();
        assistantSessionService.restoreSession(userId, id);
        return ApiResponse.success(null);
    }
}
