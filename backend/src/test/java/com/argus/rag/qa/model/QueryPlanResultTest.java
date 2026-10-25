package com.argus.rag.qa.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;

/**
 * {@link QueryPlanResult} 查询规划结果 record 契约（纯 JVM/零 mock）。
 * fallback(question) 产生安全回退：策略 DIRECT + 以原问题为唯一检索语句；
 * 构造器保持 strategy/queries 原样。@JsonIgnoreProperties(ignoreUnknown=true) 注解事实。
 */
@DisplayName("查询规划结果（QueryPlanResult）")
class QueryPlanResultTest {

    @Test
    void fallbackUsesDirectStrategyWithOriginalQuestion() {
        QueryPlanResult fallback = QueryPlanResult.fallback("收入是多少");
        assertEquals(QueryPlanStrategy.DIRECT, fallback.strategy());
        assertEquals(List.of("收入是多少"), fallback.queries());
    }

    @Test
    void fallbackTrimsToSingleQuery() {
        QueryPlanResult fallback = QueryPlanResult.fallback("");
        assertEquals(1, fallback.queries().size());
        assertEquals("", fallback.queries().get(0));
    }

    @Test
    void constructedResultKeepsExactStrategyAndQueries() {
        QueryPlanResult result = new QueryPlanResult(
                QueryPlanStrategy.DECOMPOSE, List.of("q1", "q2", "q3"));
        assertEquals(QueryPlanStrategy.DECOMPOSE, result.strategy());
        assertEquals(List.of("q1", "q2", "q3"), result.queries());
    }

    @Test
    void fallbackQueriesListIsIndependentOfInputList() {
        // fallback 用 List.of 产生新列表，不共享入参引用
        QueryPlanResult a = QueryPlanResult.fallback("x");
        QueryPlanResult b = QueryPlanResult.fallback("x");
        assertNotSame(a.queries(), b.queries());
        assertEquals(a.queries(), b.queries());
    }
}