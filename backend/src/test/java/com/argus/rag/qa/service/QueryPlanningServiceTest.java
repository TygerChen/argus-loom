package com.argus.rag.qa.service;

import com.argus.rag.common.exception.BusinessException;
import com.argus.rag.qa.model.QueryPlanResult;
import com.argus.rag.qa.model.QueryPlanStrategy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.prompt.PromptTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link QueryPlanningService} 策略分发契约：大模型返回的策略/语句须正确规约
 * （GLOBAL 归一、REWRITE/DECOMPOSE 去重+限 3、空白剔除），失败自动回退 DIRECT。
 * 用 deep-stub 链式 mock ChatClient，不接真 LLM；纯行为锁。
 */
@DisplayName("查询规划策略分发（QueryPlanningService）")
class QueryPlanningServiceTest {

    private ChatClient chatClient;
    private QueryPlanningService service;

    @BeforeEach
    void setUp() {
        chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        PromptTemplate userPromptTemplate = mock(PromptTemplate.class, RETURNS_DEEP_STUBS);
        when(userPromptTemplate.create(anyMap())).thenReturn(mock(Prompt.class, RETURNS_DEEP_STUBS));
        service = new QueryPlanningService(chatClient, userPromptTemplate);
    }

    /** 让模型这次返回给定规划结果 */
    private void modelReturns(QueryPlanResult raw) {
        when(chatClient.prompt(any(Prompt.class)).call().entity(QueryPlanResult.class))
                .thenReturn(raw);
    }

    @Test
    void rejectsBlankQuestion() {
        BusinessException ex = assertThrows(BusinessException.class, () -> service.plan("  "));
        assertEquals("问题不能为空", ex.getMessage());
    }

    @Test
    void globalStrategyReturnsOnlyOriginalQuestion() {
        modelReturns(new QueryPlanResult(QueryPlanStrategy.GLOBAL, List.of("忽略")));
        QueryPlanResult r = service.plan("本月利润如何");
        assertEquals(QueryPlanStrategy.GLOBAL, r.strategy());
        assertEquals(List.of("本月利润如何"), r.queries());
    }

    @Test
    void directStrategyIgnoresModelQueries() {
        modelReturns(new QueryPlanResult(QueryPlanStrategy.DIRECT, List.of("模型乱写")));
        QueryPlanResult r = service.plan("本月回款");
        assertEquals(QueryPlanStrategy.DIRECT, r.strategy());
        assertEquals(List.of("本月回款"), r.queries());
    }

    @Test
    void rewriteStrategyPrefixesOriginalThenLimitsToThree() {
        modelReturns(new QueryPlanResult(QueryPlanStrategy.REWRITE,
                List.of(" 第二问 ", "第三问", "第四问", "  ")));
        QueryPlanResult r = service.plan("第一问");
        assertEquals(QueryPlanStrategy.REWRITE, r.strategy());
        // 去去空白 + 去重 + 原问打头 + 限 3
        assertEquals(List.of("第一问", "第二问", "第三问"), r.queries());
    }

    @Test
    void decomposeStrategyDedupsAndLimitsToThree() {
        modelReturns(new QueryPlanResult(QueryPlanStrategy.DECOMPOSE,
                List.of("a", "b", "b", "c", "d")));
        QueryPlanResult r = service.plan("拆解问题");
        assertEquals(QueryPlanStrategy.DECOMPOSE, r.strategy());
        assertEquals(List.of("a", "b", "c"), r.queries());
    }

    @Test
    void blankQueriesFallbackToDirect() {
        modelReturns(new QueryPlanResult(QueryPlanStrategy.DECOMPOSE, null));
        QueryPlanResult r = service.plan("无备查");
        assertEquals(QueryPlanStrategy.DIRECT, r.strategy());
        assertEquals(List.of("无备查"), r.queries());
    }

    @Test
    void nullRawResultFallsBackToDirect() {
        modelReturns(null);
        QueryPlanResult r = service.plan("模型空返回");
        assertEquals(QueryPlanStrategy.DIRECT, r.strategy());
        assertEquals(List.of("模型空返回"), r.queries());
    }

    @Test
    void modelFailureFallsBackToDirect() {
        when(chatClient.prompt(any(Prompt.class)).call().entity(QueryPlanResult.class))
                .thenThrow(new IllegalStateException("provider down"));
        QueryPlanResult r = service.plan("失败回退");
        assertEquals(QueryPlanStrategy.DIRECT, r.strategy());
        assertEquals(List.of("失败回退"), r.queries());
    }
}