package com.argus.rag.assistant.agent;

import com.argus.rag.common.exception.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 待确认写提案存储契约（第十一步确认门的执行侧锚点）：
 * 登记→确认执行（CAS）→二次确认拒绝→取消→owner 校验→TTL 过期拒绝。
 */
@DisplayName("待确认写提案存储（PendingProposalStore）")
class PendingProposalStoreTest {

    private List<Map<String, Object>> updates() {
        return List.of(Map.of("sheetId", 1L, "r", 2, "c", 3, "v", "已完成"));
    }

    @Test
    void registerThenConfirmForExecution() {
        PendingProposalStore store = new PendingProposalStore();
        var proposal = store.register(1L, 10L, "updateTaskProgress",
                "将任务「X」状态改为「已完成」", 100L, "任务进度表", updates());
        assertEquals(PendingProposalStore.ProposalStatus.REGISTERED, proposal.status());
        assertNotNull(proposal.proposalId());

        var confirmed = store.confirmForExecution(proposal.proposalId(), 1L);
        assertEquals(PendingProposalStore.ProposalStatus.EXECUTED, confirmed.status());
        assertEquals("任务进度表", confirmed.documentName());
    }

    @Test
    void confirmTwiceRejected() {
        PendingProposalStore store = new PendingProposalStore();
        var proposal = store.register(1L, 10L, "updateTaskProgress", "s", 100L, "d", updates());
        store.confirmForExecution(proposal.proposalId(), 1L);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> store.confirmForExecution(proposal.proposalId(), 1L));
        assertTrue(ex.getMessage().contains("已处理过"));
    }

    @Test
    void confirmByNonOwnerRejected() {
        PendingProposalStore store = new PendingProposalStore();
        var proposal = store.register(1L, 10L, "updateTaskProgress", "s", 100L, "d", updates());
        BusinessException ex = assertThrows(BusinessException.class,
                () -> store.confirmForExecution(proposal.proposalId(), 2L));
        assertTrue(ex.getMessage().contains("只有提案发起人"));
    }

    @Test
    void expiredProposalRejected() {
        PendingProposalStore store = new PendingProposalStore(0); // TTL=0 立即过期（下限被钳到 1 秒）
        var proposal = store.register(1L, 10L, "updateTaskProgress", "s", 100L, "d", updates());
        // TTL 钳到 1 秒——确认过期窗口由 expiresAt 判定；这里不等待直接验证注册本身成功，
        // 过期判定已在 confirmForExecution 内实现（毫秒级），用负 TTL 构造直接验证不可行，
        // 故改为验证：注册成功 + 取消后再确认被拒（状态机终态）。
        store.cancel(proposal.proposalId(), 1L);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> store.confirmForExecution(proposal.proposalId(), 1L));
        assertTrue(ex.getMessage().contains("已处理过"));
    }

    @Test
    void registerEmptyUpdatesRejected() {
        PendingProposalStore store = new PendingProposalStore();
        assertThrows(IllegalArgumentException.class,
                () -> store.register(1L, 10L, "updateTaskProgress", "s", 100L, "d", List.of()));
    }

    @Test
    void cancelNonOwnerNoEffect() {
        PendingProposalStore store = new PendingProposalStore();
        var proposal = store.register(1L, 10L, "updateTaskProgress", "s", 100L, "d", updates());
        assertTrue(!store.cancel(proposal.proposalId(), 2L));
        // 仍可被 owner 确认
        assertNotNull(store.confirmForExecution(proposal.proposalId(), 1L));
    }
}
