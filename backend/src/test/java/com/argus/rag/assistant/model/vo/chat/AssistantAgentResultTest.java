package com.argus.rag.assistant.model.vo.chat;

import com.argus.rag.qa.model.vo.AskQuestionResponse.Citation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AssistantAgentResult} 助手回复结果契约（纯 JVM/零 mock，含嵌套
 * {@link AskQuestionResponse.Citation}）。防御性拷贝构造器 null 归 {@link List#of()}、
 * 非空列表经 {@link java.util.List#copyOf} 拷贝为不可变视图；{@code withoutCitations}
 * 工厂产出回复 + 空引用列表。
 */
@DisplayName("助手回复结果（AssistantAgentResult）")
class AssistantAgentResultTest {

    @Test
    void nullCitationsBecomeEmptyImmutableList() {
        AssistantAgentResult r = new AssistantAgentResult("hello", null);
        assertEquals(List.of(), r.citations());
        assertSame(r.citations(), r.citations(), "归一化空列表应共享同一空单例");
    }

    @Test
    void defensiveCopyDetachesFromMutableInput() {
        List<Citation> mutable = new ArrayList<>();
        mutable.add(new Citation(1L, 10L, 1, "a.pdf", 0.9, "s"));
        AssistantAgentResult r = new AssistantAgentResult("reply", mutable);
        assertNotSame(mutable, r.citations(), "应拷贝而非直接持有原引用");
        mutable.add(new Citation(2L, 20L, 2, "b.pdf", 0.3, "t"));
        assertEquals(1, r.citations().size(), "构造后外部增改不得传导");
    }

    @Test
    void returnedCitationsAreImmutable() {
        AssistantAgentResult r = new AssistantAgentResult("reply", List.of());
        assertThrows(UnsupportedOperationException.class,
                () -> r.citations().add(new Citation(1L, 10L, 1, "a.pdf", 0.9, "s")));
    }

    @Test
    void withoutCitationsBuildsEmptyCitationNotice() {
        AssistantAgentResult r = AssistantAgentResult.withoutCitations("no sources");
        assertEquals("no sources", r.reply());
        assertTrue(r.citations().isEmpty());
    }
}