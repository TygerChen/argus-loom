package com.argus.rag.assistant.agent;

import com.argus.rag.qa.model.vo.AskQuestionResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AssistantKnowledgeBaseToolResultHolder} 知识库检索结果持有器契约（纯 JVM/零 mock）。
 * 初始空且未检索；记录 null 引列表→空 + 标记已完成；记录列表→保留且已完成；
 * 返回列表不可变（List.copyOf），防 Agent 图内篡改；同一调用周期内完成状态供去重。
 */
@DisplayName("知识库检索结果持有（AssistantKnowledgeBaseToolResultHolder）")
class AssistantKnowledgeBaseToolResultHolderTest {

    private final AssistantKnowledgeBaseToolResultHolder holder = new AssistantKnowledgeBaseToolResultHolder();

    private AskQuestionResponse.Citation citation(Long id) {
        return new AskQuestionResponse.Citation(id, 2L, 3, "f.md", 0.5, "snippet");
    }

    @Test
    void initiallyEmptyAndNotCompleted() {
        assertEquals(List.of(), holder.currentCitations());
        assertFalse(holder.hasCompletedSearch());
    }

    @Test
    void recordNullCitationsYieldsEmptyAndCompleted() {
        holder.recordCitations(null);
        assertEquals(List.of(), holder.currentCitations());
        assertTrue(holder.hasCompletedSearch());
    }

    @Test
    void recordCitationsRetainsListAndMarksCompleted() {
        List<AskQuestionResponse.Citation> in = List.of(citation(1L), citation(2L));
        holder.recordCitations(in);
        assertEquals(in, holder.currentCitations());
        assertEquals(2, holder.currentCitations().size());
        assertTrue(holder.hasCompletedSearch());
    }

    @Test
    void returnedListIsImmutableCopy() {
        holder.recordCitations(List.of(citation(1L)));
        assertThrows(UnsupportedOperationException.class,
                () -> holder.currentCitations().add(citation(9L)));
    }

    @Test
    void freshHolderHasCompletedFalseAfterConstruct() {
        assertFalse(holder.hasCompletedSearch());
    }
}