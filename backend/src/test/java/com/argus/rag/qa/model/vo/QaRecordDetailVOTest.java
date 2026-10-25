package com.argus.rag.qa.model.vo;

import com.argus.rag.qa.model.vo.QaRecordDetailVO.Citation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * {@link QaRecordDetailVO} QA 历史详情引用快照契约（纯 JVM/零 mock）。
 * withCitations 产出 22 字段逐一透传、仅替换引用列表的新实例：新实例引用为新传入列表，
 * 源实例引用保持原列表且其余字段（id/userId/…/evidenceOverview 等）原值不变。
 */
@DisplayName("QA 历史详情（QaRecordDetailVO.withCitations）")
class QaRecordDetailVOTest {

    private static final LocalDateTime AT = LocalDateTime.of(2026, 9, 19, 10, 0);

    private static Citation cit(Long id) {
        return new Citation(id, 9L, 100L, 0, 0, 1, "a.pdf", 0.9,
                "HYBRID", 0.1, 0.2, 0.3, "snippet-" + id);
    }

    private static QaRecordDetailVO base() {
        return new QaRecordDetailVO(
                42L, 7L, 3L, "什么是销售毛利率？", "毛利率 = …",
                true, null, null, "SUFFICIENT",
                1, 500, 200, 700, false, 120L, null, "gpt-4o", "qa/ask",
                true, null, AT,
                null, List.of(cit(100L), cit(200L)));
    }

    @Test
    void withCitationsReplacesListAndKeepsSourceFields() {
        QaRecordDetailVO src = base();
        List<Citation> fresh = List.of(cit(300L));
        QaRecordDetailVO replaced = src.withCitations(fresh);

        assertNotSame(src, replaced);
        assertSame(fresh, replaced.citations(), "withCitations 应直接采用新列表实例");

        // 源实例字段与引用不变
        assertSame(src.citations(), src.citations());
        assertEquals(2, src.citations().size(), "源引用列表不应被改动");
        assertNull(replaced.evidenceOverview(), "其余字段透传（evidenceOverview 保持 null）");
        assertEquals(src.id(), replaced.id());
        assertEquals(src.question(), replaced.question());
        assertEquals(src.answer(), replaced.answer());
        assertEquals(src.totalTokens(), replaced.totalTokens());
        assertEquals(src.modelName(), replaced.modelName());
        assertEquals(src.success(), replaced.success());
        assertEquals(src.createdAt(), replaced.createdAt());
    }

    @Test
    void withCitationsCanSetNull() {
        QaRecordDetailVO src = base();
        QaRecordDetailVO cleared = src.withCitations(null);
        assertNull(cleared.citations(), "允许传 null 覆盖引用快照");
        assertEquals(2, src.citations().size(), "源引用不受影响");
    }
}
