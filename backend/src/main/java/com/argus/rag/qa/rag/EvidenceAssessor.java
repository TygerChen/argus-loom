package com.argus.rag.qa.rag;

import com.argus.rag.qa.model.EvidenceLevel;
import org.springframework.ai.document.Document;

import java.util.List;

/**
 * 检索证据充分度评估器（无状态纯函数集合）。
 * <p>
 * 从 {@code HybridChunkRetrievalService} 抽出的「证据评估 / 指导语生成」段：
 * 仅对传入的 {@code documents} 与得到的 {@link EvidenceLevel} 做纯计算，不触碰检索编排、
 * 候选排序、DAO 或任何内部状态。抽离后独立可测，且不改变任何对外行为。
 * </p>
 */
public final class EvidenceAssessor {

    private EvidenceAssessor() {
    }

    /**
     * 根据检索证据文档束评估证据充分度等级。
     *
     * @param documents 检索到的证据文档列表（可能为空）
     * @return 证据等级，见 {@link EvidenceLevel}
     */
    public static EvidenceLevel evaluate(List<Document> documents) {
        if (documents.isEmpty()) {
            return EvidenceLevel.NONE;
        }
        // BOTH = 向量通道与关键词通道都命中了同一条 → 两路交叉印证，置信度更高。
        boolean hasBothSource = documents.stream()
                .map(document -> document.getMetadata().get("retrievalSource"))
                .anyMatch("BOTH"::equals);
        boolean hasVectorEvidence = documents.stream()
                .map(document -> document.getMetadata().get("retrievalSource"))
                .anyMatch(source -> "VECTOR".equals(source) || "BOTH".equals(source));
        double topScore = documents.stream()
                .map(document -> document.getMetadata().get("score"))
                .filter(Double.class::isInstance)
                .map(Double.class::cast)
                .max(Double::compareTo)
                .orElse(0D);
        double avgScore = documents.stream()
                .map(document -> document.getMetadata().get("score"))
                .filter(Double.class::isInstance)
                .mapToDouble(Double.class::cast)
                .average().orElse(0D);
        // SUFFICIENT: 多源交叉验证 或 高 top 分且多文档
        if (documents.size() >= 2 && (hasBothSource || (hasVectorEvidence && topScore >= 0.85D))) {
            return EvidenceLevel.SUFFICIENT;
        }
        // INSUFFICIENT: top 分极低（即便有候选命中）
        if (topScore < 0.30D || (documents.size() <= 1 && avgScore < 0.40D)) {
            return EvidenceLevel.INSUFFICIENT;
        }
        // PARTIAL 与 WEAK 的区别（新手最易混）：
        // PARTIAL = 有多源命中或文档条数 ≥2（覆盖了几个方面，但尚未强到 SUFFICIENT）→ 可"部分容有依据地答"；
        // WEAK    = 只剩单源、且分数不高的弱命中 → 证据最弱，只能"说明依据有限地谨慎答"。
        if (hasBothSource || documents.size() >= 2) {
            return EvidenceLevel.PARTIAL;
        }
        return EvidenceLevel.WEAK;
    }

    /**
     * 根据证据等级生成对应的回答指导语。
     *
     * @param evidenceLevel 证据等级
     * @return 对应的回答策略指导语
     */
    public static String guidanceFor(EvidenceLevel evidenceLevel) {
        return switch (evidenceLevel) {
            case NONE -> "当前没有可用证据，必须直接拒答。";
            case INSUFFICIENT -> "检索到的证据相关性太低，不应强行回答。请告知用户当前知识库中暂无足够相关信息。";
            case WEAK -> "当前证据相关性有限，只能谨慎回答，必须明确说明依据有限，不能给出确定性结论。";
            case PARTIAL -> "当前证据只覆盖部分问题，只能回答证据明确支持的部分，未覆盖部分必须明确说明不足。";
            case SUFFICIENT -> "当前证据较充分，可以正常回答，但仍然不得超出证据进行臆测。";
        };
    }
}