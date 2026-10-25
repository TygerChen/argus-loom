package com.argus.rag.qa.rag;

import com.argus.rag.qa.model.EvidenceLevel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link EvidenceAssessor#evaluate(List)} / {@link EvidenceAssessor#guidanceFor(EvidenceLevel)}
 * 检索证据充分度评估行为锁（纯 JVM、public static 直调、零构造服务、零 IO/DB/LLM）。
 * <p>锁契约：空束→NONE；≥2 且（跨源交叉 或 高 top 分向量证据）→SUFFICIENT；
 * top 极低 或 单文档均分过低→INSUFFICIENT；有双源∨多文档→PARTIAL；否则 WEAK；
 * score 仅认 {@code Double} 类型键。指导语逐枚锁定。</p>
 */
@DisplayName("证据充分度评估（EvidenceAssessor.evaluate/guidanceFor）")
class EvidenceAssessorTest {

    private static Document doc(String source, Double score) {
        return new Document("证据内容", Map.of("retrievalSource", source, "score", score));
    }

    // ---------- evaluate ----------

    @Test
    void emptyListYieldsNone() {
        assertEquals(EvidenceLevel.NONE, EvidenceAssessor.evaluate(List.of()));
    }

    @Test
    void singleBothSourceIsPartial() {
        // 单文档 hasBothSource=true，但 SUFFICIENT 需 >=2 → 落入 PARTIAL
        assertEquals(EvidenceLevel.PARTIAL, EvidenceAssessor.evaluate(List.of(doc("BOTH", 0.6))));
    }

    @Test
    void twoBothSourceDocsYieldSufficient() {
        assertEquals(EvidenceLevel.SUFFICIENT,
                EvidenceAssessor.evaluate(List.of(doc("BOTH", 0.5), doc("BOTH", 0.7))));
    }

    @Test
    void twoDocsHighTopVectorScoreYieldSufficient() {
        // ≥2 且 hasVectorEvidence && topScore>=0.85
        assertEquals(EvidenceLevel.SUFFICIENT,
                EvidenceAssessor.evaluate(List.of(doc("VECTOR", 0.9), doc("KEYWORD", 0.4))));
    }

    @Test
    void vectorScoreJustBelowThresholdIsPartial() {
        // topScore=0.84 < 0.85 → 不 SUFFICIENT；≥2 → PARTIAL
        assertEquals(EvidenceLevel.PARTIAL,
                EvidenceAssessor.evaluate(List.of(doc("VECTOR", 0.84), doc("KEYWORD", 0.4))));
    }

    @Test
    void extremeLowTopScoreIsInsufficient() {
        // topScore=0.2 < 0.30 → 即便多文档也 INSUFFICIENT
        assertEquals(EvidenceLevel.INSUFFICIENT,
                EvidenceAssessor.evaluate(List.of(doc("VECTOR", 0.2), doc("KEYWORD", 0.15))));
    }

    @Test
    void singleDocLowAverageIsInsufficient() {
        // size<=1 且 avg=0.35 < 0.40 → INSUFFICIENT
        assertEquals(EvidenceLevel.INSUFFICIENT, EvidenceAssessor.evaluate(List.of(doc("VECTOR", 0.35))));
    }

    @Test
    void singleDocMidScoreIsWeak() {
        // 非极低、单文档无多源 → WEAK
        assertEquals(EvidenceLevel.WEAK, EvidenceAssessor.evaluate(List.of(doc("VECTOR", 0.5))));
    }

    @Test
    void metadataMissingScoreFallsBackZeroToInsufficient() {
        // 无 score 元数据(仅认 Double)→ top=0 → INSUFFICIENT
        assertEquals(EvidenceLevel.INSUFFICIENT, EvidenceAssessor.evaluate(List.of(
                new Document("无元数据文档A"), new Document("无元数据文档B"))));
    }

    @Test
    void nonDoubleScoreIgnoredFallingBackToZero() {
        // score 以 Integer 放入 metadata → 断言只认 Double：top=0 → INSUFFICIENT
        Document byInt = new Document("正文", Map.of("retrievalSource", "VECTOR", "score", Integer.valueOf(90)));
        assertEquals(EvidenceLevel.INSUFFICIENT, EvidenceAssessor.evaluate(List.of(byInt)));
    }

    // ---------- guidanceFor：五枚 enum 逐一锁 ----------

    @Test
    void guidanceNone() {
        assertEquals("当前没有可用证据，必须直接拒答。", EvidenceAssessor.guidanceFor(EvidenceLevel.NONE));
    }

    @Test
    void guidanceInsufficient() {
        assertEquals("检索到的证据相关性太低，不应强行回答。请告知用户当前知识库中暂无足够相关信息。",
                EvidenceAssessor.guidanceFor(EvidenceLevel.INSUFFICIENT));
    }

    @Test
    void guidanceWeak() {
        assertEquals("当前证据相关性有限，只能谨慎回答，必须明确说明依据有限，不能给出确定性结论。",
                EvidenceAssessor.guidanceFor(EvidenceLevel.WEAK));
    }

    @Test
    void guidancePartial() {
        assertEquals("当前证据只覆盖部分问题，只能回答证据明确支持的部分，未覆盖部分必须明确说明不足。",
                EvidenceAssessor.guidanceFor(EvidenceLevel.PARTIAL));
    }

    @Test
    void guidanceSufficient() {
        assertEquals("当前证据较充分，可以正常回答，但仍然不得超出证据进行臆测。",
                EvidenceAssessor.guidanceFor(EvidenceLevel.SUFFICIENT));
    }
}