package com.argus.rag.assistant.model.vo.chat;

import com.argus.rag.assistant.agent.PendingProposalStore;

import java.util.List;
import java.util.Map;

/**
 * 待确认写提案视图（第十一步）——随聊天响应下发，前端据此渲染确认卡片。
 *
 * @param proposalId   提案号（确认/取消 API 的路径参数，兼幂等键）
 * @param toolName     来源写工具
 * @param summary      改动摘要（"将任务「X」状态改为「已完成」"）
 * @param documentId   目标文档 ID
 * @param documentName 目标文档名
 * @param sessionId    发起会话
 * @param status       状态：REGISTERED（待确认）/ EXECUTED / CANCELLED
 * @param expiresAt    过期时间戳（毫秒）——前端据此显示"已过期请重新发起"
 */
public record PendingProposalVO(
        String proposalId,
        String toolName,
        String summary,
        Long documentId,
        String documentName,
        Long sessionId,
        String status,
        long expiresAt
) {

    public static PendingProposalVO from(PendingProposalStore.PendingProposal proposal) {
        return new PendingProposalVO(
                proposal.proposalId(),
                proposal.toolName(),
                proposal.summary(),
                proposal.documentId(),
                proposal.documentName(),
                proposal.sessionId(),
                proposal.status().name(),
                proposal.expiresAt()
        );
    }
}
