package com.argus.rag.qa.service;

import com.argus.rag.common.util.MetadataReaders;
import com.argus.rag.qa.model.vo.AskQuestionResponse;
import com.argus.rag.qa.model.EvidenceLevel;
import com.argus.rag.qa.rag.ReadyChunkDocumentRetriever;
import com.argus.rag.qa.rag.RetrievedEvidenceBundle;
import com.argus.rag.qa.rag.HybridChunkRetrievalService;
import com.argus.rag.qa.support.CitationAssembler;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * QA 模块对外检索门面，供 assistant 等模块调用。
 *
 * <p><b>门面（Facade）模式的实际应用</b>：检索这件事背后有"按文档直查"
 * （ReadyChunkDocumentRetriever）和"混合检索"（HybridChunkRetrievalService，
 * 04 篇的双路召回）两套引擎，还有引用组装（CitationAssembler）。
 * 本类把它们收成一个简单入口：给问题，还你带引用的证据包。
 * 调用方（assistant 对话等）不需要知道内部用了哪个引擎——
 * 以后换检索实现，只改这里，所有调用方无感。</p>
 *
 * <p><b>两个 top-k 的分工</b>：perGroupTopK = 每个知识库最多出几条
 * （防止单个大库淹没别的库）；overallTopK = 全局总共最多几条
 * （控制大模型上下文长度）。多库检索的配额思想，13 篇旅程图第 ⑤ 步。</p>
 */
@Service
public class QaRetrievalService {

    private final ReadyChunkDocumentRetriever documentRetriever;
    private final HybridChunkRetrievalService hybridChunkRetrievalService;
    private final CitationAssembler citationAssembler;
    private final int perGroupTopK;
    private final int overallTopK;

    public QaRetrievalService(ReadyChunkDocumentRetriever documentRetriever,
                              HybridChunkRetrievalService hybridChunkRetrievalService,
                              CitationAssembler citationAssembler,
                              @Value("${kb-routing.per-group-top-k:4}") int perGroupTopK,
                              @Value("${kb-routing.overall-top-k:12}") int overallTopK) {
        this.documentRetriever = documentRetriever;
        this.hybridChunkRetrievalService = hybridChunkRetrievalService;
        this.citationAssembler = citationAssembler;
        this.perGroupTopK = perGroupTopK > 0 ? perGroupTopK : 4;
        this.overallTopK = overallTopK > 0 ? overallTopK : 12;
    }

    /** 执行知识库检索，返回证据束和引用列表 */
    public QaRetrievalResult retrieveEvidence(Long groupId, String query) {
        RetrievedEvidenceBundle bundle = documentRetriever.retrieveEvidence(groupId, query);
        List<AskQuestionResponse.Citation> citations = citationAssembler.assembleDocuments(bundle.documents());
        return new QaRetrievalResult(bundle, citations);
    }

    /**
     * 多知识库检索：逐组执行混合检索，按组间公平配额权衡后合并，返回统一证据束和引用列表。
     */
    public QaRetrievalResult retrieveEvidence(List<Long> groupIds, String query) {
        List<Long> validGroupIds = distinctPositiveGroupIds(groupIds);
        if (validGroupIds.isEmpty()) {
            return new QaRetrievalResult(RetrievedEvidenceBundle.empty(), List.of());
        }
        List<Document> documents = new ArrayList<>();
        EvidenceLevel aggregatedLevel = EvidenceLevel.NONE;
        String guidance = null;
        for (Long groupId : validGroupIds) {
            RetrievedEvidenceBundle bundle = hybridChunkRetrievalService.retrieve(groupId, query, perGroupTopK);
            if (bundle.documents() == null || bundle.documents().isEmpty()) {
                continue;
            }
            documents.addAll(bundle.documents());
            aggregatedLevel = worse(aggregatedLevel, bundle.evidenceLevel());
            if (bundle.evidenceGuidance() != null && !bundle.evidenceGuidance().isBlank()) {
                guidance = bundle.evidenceGuidance();
            }
        }
        if (documents.isEmpty()) {
            return new QaRetrievalResult(RetrievedEvidenceBundle.empty(), List.of());
        }
        List<Document> balancedDocuments = balanceAcrossGroups(documents, overallTopK);
        List<AskQuestionResponse.Citation> citations = citationAssembler.assembleDocuments(balancedDocuments);
        EvidenceLevel level = aggregatedLevel == EvidenceLevel.NONE ? EvidenceLevel.WEAK : aggregatedLevel;
        return new QaRetrievalResult(
                new RetrievedEvidenceBundle(balancedDocuments, level, guidance),
                citations
        );
    }

    /** 去重、剔除非法 ID 后的知识库组列表。 */
    private List<Long> distinctPositiveGroupIds(List<Long> groupIds) {
        Set<Long> distinct = new LinkedHashSet<>();
        if (groupIds != null) {
            for (Long id : groupIds) {
                if (id != null && id > 0) {
                    distinct.add(id);
                }
            }
        }
        return List.copyOf(distinct);
    }

    /** 合并后的候选按知识库公平轮换，避免单个知识库占用全部上下文预算。 */
    private List<Document> balanceAcrossGroups(List<Document> documents, int cap) {
        if (documents.isEmpty()) {
            return List.of();
        }
        Map<Long, ArrayDeque<Document>> perGroup = new LinkedHashMap<>();
        for (Document document : documents) {
            Long groupId = readLong(document.getMetadata(), "groupId");
            if (groupId != null) {
                perGroup.computeIfAbsent(groupId, ignored -> new ArrayDeque<>()).addLast(document);
            }
        }
        List<Document> balanced = new ArrayList<>();
        while (balanced.size() < cap && !perGroup.isEmpty()) {
            boolean advanced = false;
            var iterator = perGroup.entrySet().iterator();
            while (iterator.hasNext()) {
                var entry = iterator.next();
                ArrayDeque<Document> queue = entry.getValue();
                if (queue.isEmpty()) {
                    iterator.remove();
                    continue;
                }
                balanced.add(queue.pollFirst());
                advanced = true;
                if (balanced.size() >= cap) {
                    break;
                }
            }
            if (!advanced) {
                break;
            }
        }
        return balanced;
    }

    private Long readLong(Map<String, Object> metadata, String key) {
        return MetadataReaders.longValue(metadata, key);
    }

    private EvidenceLevel worse(EvidenceLevel current, EvidenceLevel candidate) {
        if (candidate == null || candidate == EvidenceLevel.NONE) {
            return current;
        }
        if (current == null || current == EvidenceLevel.NONE) {
            return candidate;
        }
        if (current == EvidenceLevel.INSUFFICIENT || candidate == EvidenceLevel.INSUFFICIENT) {
            return EvidenceLevel.INSUFFICIENT;
        }
        if (current == EvidenceLevel.WEAK || candidate == EvidenceLevel.WEAK) {
            return EvidenceLevel.WEAK;
        }
        if (current == EvidenceLevel.PARTIAL || candidate == EvidenceLevel.PARTIAL) {
            return EvidenceLevel.PARTIAL;
        }
        return EvidenceLevel.SUFFICIENT;
    }

    public record QaRetrievalResult(
            RetrievedEvidenceBundle bundle,
            List<AskQuestionResponse.Citation> citations
    ) {}
}
