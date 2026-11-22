package com.argus.rag.assistant.controller;

import com.argus.rag.assistant.model.vo.chat.PendingProposalVO;
import com.argus.rag.assistant.service.AssistantProposalService;
import com.argus.rag.auth.CurrentUserService;
import com.argus.rag.common.api.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 写提案确认控制器（第十一步）——确认门的用户入口。
 *
 * <ul>
 *   <li>GET    /api/assistant/proposals/{proposalId} - 查看提案（卡片刷新）</li>
 *   <li>POST   /api/assistant/proposals/{proposalId}/confirm - 确认执行（幂等键=proposalId）</li>
 *   <li>DELETE /api/assistant/proposals/{proposalId} - 取消提案</li>
 * </ul>
 *
 * <p><b>安全口径</b>：确认/取消都校验"提案发起人=当前登录人"；执行前走完整
 * {@code requireEdit} 权限链；所有路径经入口审计过滤器记账（ASSISTANT 通道）。</p>
 */
@RestController
@RequestMapping("/api/assistant/proposals")
@Tag(name = "AI 写提案确认", description = "任务进度等写操作的确认执行与取消")
public class AssistantProposalController {

    private final AssistantProposalService proposalService;
    private final CurrentUserService currentUserService;

    public AssistantProposalController(AssistantProposalService proposalService,
                                       CurrentUserService currentUserService) {
        this.proposalService = proposalService;
        this.currentUserService = currentUserService;
    }

    @GetMapping("/{proposalId}")
    public ApiResponse<PendingProposalVO> get(@PathVariable String proposalId) {
        CurrentUserService.CurrentUser user = currentUserService.requireBusinessUser();
        var proposal = proposalService.find(proposalId, user);
        return ApiResponse.success(proposal == null ? null : PendingProposalVO.from(proposal));
    }

    @PostMapping("/{proposalId}/confirm")
    public ApiResponse<AssistantProposalService.ConfirmOutcome> confirm(@PathVariable String proposalId) {
        CurrentUserService.CurrentUser user = currentUserService.requireBusinessUser();
        return ApiResponse.success(proposalService.confirm(proposalId, user));
    }

    @DeleteMapping("/{proposalId}")
    public ApiResponse<Boolean> cancel(@PathVariable String proposalId) {
        CurrentUserService.CurrentUser user = currentUserService.requireBusinessUser();
        return ApiResponse.success(proposalService.cancel(proposalId, user));
    }
}
