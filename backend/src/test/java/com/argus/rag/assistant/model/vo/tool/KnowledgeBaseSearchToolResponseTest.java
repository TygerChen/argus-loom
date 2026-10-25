package com.argus.rag.assistant.model.vo.tool;

import com.argus.rag.assistant.model.vo.tool.KnowledgeBaseSearchToolResponse.Evidence;
import com.argus.rag.qa.model.vo.AskQuestionResponse.Citation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link KnowledgeBaseSearchToolResponse} 检索工具响应对照契约（纯 JVM/零 mock）。
 * 防御性拷贝构造器：null 证据/引用自动归一为 {@link List#of()}；非空列表经
 * {@link java.util.List#copyOf} 深拷贝为不可变视图（底层可改列表变更不传导、后续写抛
 * UnsupportedOperationException）。
 */
@DisplayName("检索工具响应（KnowledgeBaseSearchToolResponse）")
class KnowledgeBaseSearchToolResponseTest {

    @Test
    void nullEvidencesAndCitationsBecomeEmptyImmutableLists() {
        KnowledgeBaseSearchToolResponse r = new KnowledgeBaseSearchToolResponse(
                true, "OK", "ok", "HIGH", "use it", null, null);
        assertEquals(List.of(), r.evidences());
        assertEquals(List.of(), r.citations());
        assertSame(r.evidences(), r.evidences(), "空列表归一为共享单例不应重复分配");
    }

    @Test
    void defensiveCopyDetachesFromMutableInput() {
        List<Evidence> mutable = new ArrayList<>();
        mutable.add(new Evidence(1L, 10L, 0, "a.pdf", 0.9, "alpha"));
        List<Citation> mutableCites = new ArrayList<>();
        mutableCites.add(new Citation(1L, 10L, 1, "a.pdf", 0.9, "snippet"));

        KnowledgeBaseSearchToolResponse r = new KnowledgeBaseSearchToolResponse(
                true, "OK", "ok", "HIGH", "use it", mutable, mutableCites);

        assertNotSame(mutable, r.evidences(), "应拷贝而非直接用原引用");
        assertNotSame(mutableCites, r.citations());

        mutable.add(new Evidence(2L, 20L, 1, "b.pdf", 0.3, "beta"));
        mutableCites.add(new Citation(2L, 20L, 2, "b.pdf", 0.3, "snippet2"));
        assertEquals(1, r.evidences().size(), "构造后外部改动不应传导");
        assertEquals(1, r.citations().size());
    }

    @Test
    void returnedListsAreImmutable() {
        Evidence e = new Evidence(1L, 10L, 0, "a.pdf", 0.9, "s");
        KnowledgeBaseSearchToolResponse r = new KnowledgeBaseSearchToolResponse(
                true, "OK", "ok", "HIGH", "use it", List.of(e), List.of());

        assertThrows(UnsupportedOperationException.class, () -> r.evidences().add(e));
        assertThrows(UnsupportedOperationException.class,
                () -> r.citations().add(new Citation(1L, 10L, 1, "a.pdf", 0.9, "snippet")));
    }

    @Test
    void withGroupIdMapsToNewCitationKeepingFields() {
        Citation base = new Citation(1L, 10L, 1, "a.pdf", 0.9, "snippet");
        Citation grouped = base.withGroupId(42L);
        assertEquals(42L, grouped.groupId());
        assertEquals(base.documentId(), grouped.documentId());
        assertEquals(base.chunkId(), grouped.chunkId());
        assertEquals(base.chunkIndex(), grouped.chunkIndex());
        assertEquals(base.fileName(), grouped.fileName());
        assertEquals(base.snippet(), grouped.snippet());
        assertEquals(null, base.groupId(), "源引用 groupId 应为 null，withGroupId 不就地修改");
    }
}