package com.argus.rag.assistant.agent;

import com.argus.rag.common.exception.BusinessException;
import com.argus.rag.assistant.loop.TaskLoopState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 工具执行门禁契约（第六步六道门的代码落点）：
 * 在册（未注册工具拒）、预算硬闸（用尽即拒）、写操作幂等键强制、null 上下文放行（历史路径兼容）。
 */
@DisplayName("工具执行门禁（ToolExecutionGuard）")
class ToolExecutionGuardTest {

    private ToolContext ctxWith(TaskLoopState state, String idempotencyKey) {
        Map<String, Object> map = new HashMap<>();
        map.put(ToolExecutionGuard.LOOP_STATE_CONTEXT_KEY, state);
        if (idempotencyKey != null) {
            map.put("idempotencyKey", idempotencyKey);
        }
        return new ToolContext(map);
    }

    @Test
    void registeredToolWithBudgetPasses() {
        TaskLoopState state = TaskLoopState.defaults();
        state.nextRound();
        assertDoesNotThrow(() -> ToolExecutionGuard.check(ctxWith(state, null), "knowledgeBaseSearch"));
    }

    @Test
    void unregisteredToolIsRejected() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> ToolExecutionGuard.check(null, "executeSql"), "不在注册清单的工具必须被拒");
        assertTrue(ex.getMessage().contains("注册清单"));
    }

    @Test
    void exhaustedBudgetHardStopsExecution() {
        TaskLoopState state = TaskLoopState.defaults();
        for (int i = 0; i < 3; i++) {
            state.nextRound();
        }
        BusinessException ex = assertThrows(BusinessException.class,
                () -> ToolExecutionGuard.check(ctxWith(state, null), "knowledgeBaseSearch"),
                "预算用尽的工具调用必须被硬闸拒绝");
        assertTrue(ex.getMessage().contains("预算已用尽"));
    }

    @Test
    void writeOperationProposalModeAllowedWithoutKey() {
        // 第十一步：写操作在对话循环内只允许"提案模式"——无幂等键放行（工具只登记提案不执行写）
        TaskLoopState state = TaskLoopState.defaults();
        state.nextRound();
        assertDoesNotThrow(() -> ToolExecutionGuard.check(ctxWith(state, null), "updateTaskProgress"),
                "写操作在对话循环内以提案模式放行（无幂等键）");
    }

    @Test
    void writeOperationRejectedWhenIdempotencyKeyPresentInLoop() {
        // 幂等键属于确认 API 的运行器执行路径——绝不允许出现在对话循环里（防模型把执行 smuggle 进对话）
        TaskLoopState state = TaskLoopState.defaults();
        state.nextRound();
        BusinessException ex = assertThrows(BusinessException.class,
                () -> ToolExecutionGuard.check(ctxWith(state, "P-abc123"), "updateTaskProgress"),
                "对话循环内携带幂等键调用写工具必须被拒");
        assertTrue(ex.getMessage().contains("不允许在对话循环内执行"));
    }
}
