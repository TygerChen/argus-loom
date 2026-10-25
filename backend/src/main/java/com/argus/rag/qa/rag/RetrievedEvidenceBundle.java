package com.argus.rag.qa.rag;

import com.argus.rag.qa.model.EvidenceLevel;
import org.springframework.ai.document.Document;

import java.util.List;

/**
 * 检索证据束，封装混合检索的结果。
 * <p>
 * 包含检索到的文档列表、证据充分度等级、对应的回答指导语，以及类型化证据视图。
 * </p>
 *
 * @param documents       检索到的文档列表，每个文档包含证据文本和元数据
 * @param evidenceLevel   证据充分度等级，取值见 {@link EvidenceLevel}
 * @param evidenceGuidance 证据指导语，告知大模型当前证据状态下的回答策略
 * @param evidences       类型化证据列表（与 documents 一一对应），供诊断/引用校验等
 *                        消费方使用强类型字段，而不是解析字符串 metadata；可为 null（兼容旧构造）
 */
public record RetrievedEvidenceBundle(
        List<Document> documents,
        EvidenceLevel evidenceLevel,
        String evidenceGuidance,
        List<RetrievalEvidence> evidences
) {

    /** 兼容旧签名：不携带类型化证据。 */
    public RetrievedEvidenceBundle(
            List<Document> documents,
            EvidenceLevel evidenceLevel,
            String evidenceGuidance) {
        this(documents, evidenceLevel, evidenceGuidance, null);
    }

    /**
     * 创建空的证据束，表示未检索到任何证据。
     *
     * @return 空证据束，证据等级为 NONE
     */
    public static RetrievedEvidenceBundle empty() {
        return new RetrievedEvidenceBundle(
                List.of(),
                EvidenceLevel.NONE,
                "当前没有可用证据，必须直接拒答。",
                List.of()
        );
    }

    /**
     * 惰性派生类型化证据：未携带时从 documents 的 metadata 重建，保证新旧调用方一致。
     */
    public List<RetrievalEvidence> typedEvidences() {
        if (evidences != null) {
            return evidences;
        }
        if (documents == null || documents.isEmpty()) {
            return List.of();
        }
        return documents.stream()
                .map(RetrievalEvidence::fromDocument)
                .toList();
    }
}
