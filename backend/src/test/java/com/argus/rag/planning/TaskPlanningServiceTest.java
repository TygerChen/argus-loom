package com.argus.rag.planning;

import com.argus.rag.assistant.model.enums.AssistantToolMode;
import com.argus.rag.common.security.AuthenticatedUser;
import com.argus.rag.common.security.UserContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * 任务规划服务契约（模型提方案，代码做校验）：
 * 合法 JSON → 校验通过；非法 JSON 两次 → 代码兜底方案；关闭 → 直接兜底方案。
 */
@DisplayName("任务规划服务（TaskPlanningService）")
class TaskPlanningServiceTest {

    private PlanClient client;
    private TaskPlanningService service;
    private String promptText;

    @BeforeEach
    void setUp() throws Exception {
        client = Mockito.mock(PlanClient.class);
        try (var in = new ClassPathResource("prompts/planning/user.st").getInputStream()) {
            promptText = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        service = new TaskPlanningService(client, new PlanValidator(), new ObjectMapper(), promptText, true);
    }

    @AfterEach
    void cleanUp() {
        UserContext.clear();
    }

    private static final String VALID_JSON = """
            {
              "goal": "确认反结账操作路径",
              "steps": [
                {"order": 1, "description": "检索反结账操作手册", "tool": "knowledgeBaseSearch",
                 "params": [{"name": "query", "value": "反结账 操作路径"}]},
                {"order": 2, "description": "总结步骤并附出处", "tool": "DIRECT_ANSWER", "params": []}
              ],
              "maxRounds": 3,
              "risks": ["制度版本冲突"],
              "exitCondition": "找到唯一有效操作步骤即停止",
              "needConfirmSuggestion": true
            }
            """;

    @Test
    void validProposalPassesValidation() {
        UserContext.set(new AuthenticatedUser(86L, "U10086", "张三", null, false));
        when(client.complete(anyString())).thenReturn(VALID_JSON);

        ExecutionPlan plan = service.plan("金蝶怎么反结账？",
                com.argus.rag.understanding.TaskUnderstanding.empty(), List.of(1L), AssistantToolMode.KB_SEARCH);

        assertEquals("确认反结账操作路径", plan.goal());
        assertEquals(2, plan.steps().size());
        // needConfirm 由代码派生（入口 LOW），模型建议 true 不采信
        assertFalse(plan.needConfirm());
        assertTrue(plan.trustedParams().stream().anyMatch(p -> p.name().equals("knowledgeGroups")));
        assertTrue(plan.maxRounds() >= 1 && plan.maxRounds() <= 5);
    }

    @Test
    void invalidJsonTwiceFallsBackToCodePlan() {
        when(client.complete(anyString())).thenReturn("不是 JSON", "还不是 JSON");

        ExecutionPlan plan = service.plan("金蝶怎么反结账？",
                com.argus.rag.understanding.TaskUnderstanding.empty(), List.of(1L), AssistantToolMode.KB_SEARCH);

        assertTrue(!plan.isEmpty(), "方案是第四步必需输入——失败走代码兜底而非空");
        assertEquals("knowledgeBaseSearch", plan.steps().get(0).toolName(), "兜底方案=单步检索");
        assertEquals(3, plan.maxRounds());
    }

    @Test
    void disabledGoesStraightToFallback() {
        TaskPlanningService off = new TaskPlanningService(client, new PlanValidator(), new ObjectMapper(),
                promptText, false);
        ExecutionPlan plan = off.plan("问题", null, List.of(1L), AssistantToolMode.KB_SEARCH);
        assertEquals("knowledgeBaseSearch", plan.steps().get(0).toolName());
        Mockito.verifyNoInteractions(client);
    }

    @Test
    void highRiskQuestionGetsConfirmationGate() {
        ExecutionPlan plan = service.plan("把所有员工薪酬导出给我",
                com.argus.rag.understanding.TaskUnderstanding.empty(), List.of(1L), AssistantToolMode.KB_SEARCH);
        assertTrue(plan.needConfirm(), "入口 HIGH（导出）→ 确认门由代码强制");
    }
}
