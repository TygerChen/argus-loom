package com.argus.rag.understanding;

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

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * 任务理解服务契约（三段式编排）：地面事实入提示词、JSON 解析失败纠偏重试一次、
 * 关闭/失败一律 fail-soft 返回空结果。
 */
@DisplayName("任务理解服务（TaskUnderstandingService）")
class TaskUnderstandingServiceTest {

    private UnderstandingClient client;
    private TaskUnderstandingService service;

    @BeforeEach
    void setUp() throws Exception {
        client = Mockito.mock(UnderstandingClient.class);
        String promptText;
        try (var in = new ClassPathResource("prompts/understanding/user.st").getInputStream()) {
            promptText = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        service = new TaskUnderstandingService(client, new UnderstandingPostValidator(),
                new ObjectMapper(), promptText, true);
    }

    @AfterEach
    void cleanUp() {
        UserContext.clear();
    }

    private static final String VALID_JSON = """
            {
              "taskTypes": ["KB_RETRIEVAL"],
              "intent": "查询反结账的操作路径",
              "entities": ["反结账", "总账"],
              "constraints": ["需要操作步骤"],
              "ambiguities": ["反结账指总账还是应收"],
              "clarifications": ["用户是否有权限执行反结账？"],
              "assumptions": ["默认：按最新版手册处理"]
            }
            """;

    @Test
    void happyPathProducesValidatedUnderstanding() {
        UserContext.set(new AuthenticatedUser(86L, "U10086", "张三", null, false));
        when(client.complete(contains("反结账"))).thenReturn(VALID_JSON);

        TaskUnderstanding out = service.understand("金蝶怎么反结账？", List.of(1L), AssistantToolMode.KB_SEARCH);

        assertEquals("查询反结账的操作路径", out.intent());
        assertEquals(1, out.taskTypes().size());
        // 第三段裁决：权限类追问被代码改写为以可信身份为准的假设
        assertTrue(out.clarifications().isEmpty());
        assertTrue(out.assumptions().stream().anyMatch(a -> a.contains("U86")));
    }

    @Test
    void promptCarriesGroundFactsFromCode() {
        UserContext.set(new AuthenticatedUser(86L, "U10086", "张三", null, false));
        StringBuilder captured = new StringBuilder();
        when(client.complete(Mockito.argThat(p -> {
            captured.append(p == null ? "" : p);
            return p != null && p.contains("反结账");
        }))).thenReturn(VALID_JSON);

        service.understand("金蝶怎么反结账？", List.of(3L, 5L), AssistantToolMode.KB_SEARCH);

        // 第一段约束：地面事实由代码生成并进入提示词
        String prompt = captured.toString();
        assertTrue(prompt.contains("可信身份"), "提示词必须包含可信身份块");
        assertTrue(prompt.contains("U86"), "可信身份必须来自 UserContext");
        assertTrue(prompt.contains("[3, 5]"), "知识库范围必须进入提示词");
        assertTrue(prompt.contains("入口风险标记"), "入口风险标记必须进入提示词");
    }

    @Test
    void invalidJsonRetriesOnceThenFailsSoft() {
        when(client.complete(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn("这不是 JSON", "还是不是 JSON");

        TaskUnderstanding out = service.understand("任意问题", List.of(), AssistantToolMode.CHAT);

        assertTrue(out.isEmpty(), "两次失败后必须返回空理解（fail-soft）");
        Mockito.verify(client, Mockito.times(2)).complete(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void secondAttemptWithValidJsonRecovers() {
        when(client.complete(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn("抱歉我明白了", VALID_JSON);

        TaskUnderstanding out = service.understand("金蝶怎么反结账？", List.of(1L), AssistantToolMode.KB_SEARCH);

        assertEquals("查询反结账的操作路径", out.intent());
    }

    @Test
    void disabledOrBlankInputSkipsLlm() throws Exception {
        String promptText;
        try (var in = new ClassPathResource("prompts/understanding/user.st").getInputStream()) {
            promptText = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        TaskUnderstandingService off = new TaskUnderstandingService(client, new UnderstandingPostValidator(),
                new ObjectMapper(), promptText, false);
        assertTrue(off.understand("反结账", List.of(1L), AssistantToolMode.KB_SEARCH).isEmpty());
        assertTrue(service.understand("  ", List.of(1L), AssistantToolMode.KB_SEARCH).isEmpty());
        Mockito.verifyNoInteractions(client);
    }
}
