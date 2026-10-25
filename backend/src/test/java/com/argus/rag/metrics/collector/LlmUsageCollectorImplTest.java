package com.argus.rag.metrics.collector;

import com.argus.rag.metrics.mapper.LlmUsageRecordMapper;
import com.argus.rag.metrics.model.dto.LlmUsageRecordDTO;
import com.argus.rag.metrics.model.entity.LlmUsageRecordEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * {@link LlmUsageCollectorImpl} 用量记录契约（mock {@link LlmUsageRecordMapper}，纯 JVM/零 DB/零 LLM）。
 * record 把 DTO 全字段映射到实体（含固定币种 CNY 与 createdAt），落库；insert 异常被吞不影响主流程。
 */
@DisplayName("LLM 用量收集（LlmUsageCollectorImpl）")
class LlmUsageCollectorImplTest {

    private final LlmUsageRecordMapper mapper = mock(LlmUsageRecordMapper.class);
    private final LlmUsageCollectorImpl collector = new LlmUsageCollectorImpl(mapper);

    private LlmUsageRecordEntity captured() {
        ArgumentCaptor<LlmUsageRecordEntity> captor = ArgumentCaptor.forClass(LlmUsageRecordEntity.class);
        verify(mapper).insert(captor.capture());
        return captor.getValue();
    }

    @Test
    void mapsAllDtoFieldsToEntity() {
        collector.record(LlmUsageRecordDTO.builder()
                .userId(5L).groupId(6L).module("QA").endpoint("qa/ask").sessionId("s-1")
                .promptTokens(10).completionTokens(20).totalTokens(30)
                .isEstimated(true).costAmount(new BigDecimal("0.5"))
                .latencyMs(120L).success(true).errorMessage("").modelName("qwen-plus")
                .build());
        LlmUsageRecordEntity e = captured();
        assertEquals(5L, e.getUserId());
        assertEquals(6L, e.getGroupId());
        assertEquals("QA", e.getModule());
        assertEquals("qa/ask", e.getEndpoint());
        assertEquals("s-1", e.getSessionId());
        assertEquals(Integer.valueOf(10), e.getPromptTokens());
        assertEquals(Integer.valueOf(20), e.getCompletionTokens());
        assertEquals(Integer.valueOf(30), e.getTotalTokens());
        assertEquals(Boolean.TRUE, e.getIsEstimated());
        assertEquals(new BigDecimal("0.5"), e.getCostAmount());
        assertEquals(Long.valueOf(120L), e.getLatencyMs());
        assertEquals(Boolean.TRUE, e.getSuccess());
        assertEquals("qwen-plus", e.getModelName());
    }

    @Test
    void costCurrencyFixedToCnyAndCreatedAtSet() {
        collector.record(LlmUsageRecordDTO.builder().module("QA").build());
        LlmUsageRecordEntity e = captured();
        assertEquals("CNY", e.getCostCurrency());
        assertNotNull(e.getCreatedAt());
    }

    @Test
    void minimalDtoRespectsDefaults() {
        collector.record(LlmUsageRecordDTO.builder().build());
        LlmUsageRecordEntity e = captured();
        assertEquals(Integer.valueOf(0), e.getPromptTokens());
        assertEquals(Integer.valueOf(0), e.getTotalTokens());
        assertEquals(Boolean.FALSE, e.getIsEstimated());
        assertEquals(Boolean.TRUE, e.getSuccess());
        assertNull(e.getUserId());
        assertNull(e.getCostAmount());
    }

    @Test
    void insertFailureDoesNotPropagate() {
        doThrow(new RuntimeException("db down")).when(mapper).insert(any(LlmUsageRecordEntity.class));
        // 统计记录失败应静默：不抛异常，业务主流程不受影响
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(
                () -> collector.record(LlmUsageRecordDTO.builder().module("QA").build()));
    }
}