package com.argus.rag.qa.model.vo;

import com.argus.rag.qa.model.vo.AskQuestionResponse.Citation;
import com.argus.rag.qa.model.vo.AskQuestionResponse.EvidenceOverview;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AskQuestionResponse} 知识问答响应 VO 契约（纯 JVM/零 mock）。
 * answered/unanswered 两个工厂设置 answered 标志并只填充对应语义字段；
 * withRecordId/withEvidenceOverview 拷贝生成新实例；6 参简写与 7 参等价。
 */
@DisplayName("知识问答响应（AskQuestionResponse）")
class AskQuestionResponseTest {

    private static final Citation CIT = new Citation(1L, 2L, 0, "q.docx", 0.9, "摘要");

    @Test
    void answeredFactoryCarriesAnswerAndCitations() {
        AskQuestionResponse r = AskQuestionResponse.answered("答案", List.of(CIT));
        assertTrue(r.answered());
        assertEquals("答案", r.answer());
        assertNull(r.reasonCode());
        assertNull(r.reasonMessage());
        assertEquals(List.of(CIT), r.citations());
        assertNull(r.recordId());
        assertNull(r.evidenceOverview());
    }

    @Test
    void unansweredFactoryCarriesReasonAndNoAnswer() {
        AskQuestionResponse r = AskQuestionResponse.unanswered(
                "INSUFFICIENT_EVIDENCE", "证据不足", List.of(CIT));
        assertFalse(r.answered());
        assertNull(r.answer());
        assertEquals("INSUFFICIENT_EVIDENCE", r.reasonCode());
        assertEquals("证据不足", r.reasonMessage());
        assertEquals(List.of(CIT), r.citations());
    }

    @Test
    void withRecordIdCopiesResponseAndSetsId() {
        AskQuestionResponse base = AskQuestionResponse.answered("回答", List.of(CIT));
        AskQuestionResponse withId = base.withRecordId(99L);
        assertEquals(99L, withId.recordId());
        assertTrue(withId.answered());
        assertEquals("回答", withId.answer());
        assertEquals(List.of(CIT), withId.citations());
        assertNotSame(base, withId);
        assertNull(base.recordId());
    }

    @Test
    void withEvidenceOverviewSetsOverviewOnly() {
        EvidenceOverview overview = new EvidenceOverview(2, 5, "COMPLETE", List.of(), List.of());
        AskQuestionResponse r = AskQuestionResponse.answered("fi", List.of()).withEvidenceOverview(overview);
        assertEquals(overview, r.evidenceOverview());
        assertTrue(r.answered());
    }

    @Test
    void sevenArgConstructorKeepsAllFieldsAndShortcutEquivalent() {
        AskQuestionResponse seven = new AskQuestionResponse(false, null, "ERR", "msg", null, null, 7L);
        assertEquals("ERR", seven.reasonCode());
        assertNull(seven.evidenceOverview());
        assertEquals(7L, seven.recordId());
        // 6 参简写（evidenceOverview=null）等价于 7 参传 null
        AskQuestionResponse six = new AskQuestionResponse(false, null, "ERR", "msg", null, 7L);
        assertEquals(seven, six);
    }
}