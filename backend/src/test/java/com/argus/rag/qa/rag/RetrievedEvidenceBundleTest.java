package com.argus.rag.qa.rag;

import com.argus.rag.qa.model.EvidenceLevel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link RetrievedEvidenceBundle} 检索证据束 record 契约（纯 JVM/零 mock）。
 * empty() 产生未检索到证据的骨架束：文档列表为空、证据等级 NONE、指导语固定拒答文案。
 */
@DisplayName("检索证据束（RetrievedEvidenceBundle）")
class RetrievedEvidenceBundleTest {

    @Test
    void emptyYieldsNoDocuments() {
        assertTrue(RetrievedEvidenceBundle.empty().documents().isEmpty());
    }

    @Test
    void emptyYieldsNoneEvidenceLevel() {
        assertEquals(EvidenceLevel.NONE, RetrievedEvidenceBundle.empty().evidenceLevel());
        assertSame(EvidenceLevel.NONE, RetrievedEvidenceBundle.empty().evidenceLevel());
    }

    @Test
    void emptyCarriesFixedRejectionGuidance() {
        assertEquals("当前没有可用证据，必须直接拒答。",
                RetrievedEvidenceBundle.empty().evidenceGuidance());
    }

    @Test
    void constructedBundleKeepsExactFields() {
        RetrievedEvidenceBundle bundle = new RetrievedEvidenceBundle(
                java.util.List.of(), EvidenceLevel.SUFFICIENT, "有证据，可完整回答。");
        assertTrue(bundle.documents().isEmpty());
        assertEquals(EvidenceLevel.SUFFICIENT, bundle.evidenceLevel());
        assertEquals("有证据，可完整回答。", bundle.evidenceGuidance());
    }
}