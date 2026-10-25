package com.argus.rag.qa.rag;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 检索候选选择器：封装"是否需要跨文档覆盖"判断与最终候选集选取逻辑。
 *
 * <p>从 {@link HybridChunkRetrievalService} 中提取的纯逻辑方法，不持有任何 Spring 依赖。</p>
 */
final class RetrievalCandidateSelector {

    private RetrievalCandidateSelector() {
    }

    /**
     * 判断问题是否更需要跨文档覆盖，而不是只追求最高相关切片。
     */
    static boolean shouldPreferDocumentCoverage(String question) {
        String normalizedQuestion = question.toLowerCase(Locale.ROOT);
        return normalizedQuestion.contains("每个文档")
                || normalizedQuestion.contains("每份文档")
                || normalizedQuestion.contains("各个文档")
                || normalizedQuestion.contains("逐个文档")
                || normalizedQuestion.contains("按文档")
                || normalizedQuestion.contains("文档分别")
                || normalizedQuestion.contains("所有文档")
                || normalizedQuestion.contains("全部文档")
                || normalizedQuestion.contains("all documents")
                || normalizedQuestion.contains("each document")
                || normalizedQuestion.contains("every document")
                || normalizedQuestion.contains("per document")
                || normalizedQuestion.contains("document by document");
    }

    /**
     * 选择最终进入上下文的候选切片。
     * <p>
     * 普通问答按相关性取前 topK；当用户要求"每个文档/所有文档"时，先给不同文档各保留一个最高分切片，
     * 避免单个高相关文档的多个切片挤掉其他文档。
     * </p>
     * <p>{@code maxChunksPerDocument <= 0} 时不启用单文档配额（历史行为）。</p>
     */
    static List<RetrievalCandidate> selectRankedCandidates(
            List<RetrievalCandidate> sortedCandidates,
            int topK,
            boolean preferDocumentCoverage) {
        return selectRankedCandidates(sortedCandidates, topK, preferDocumentCoverage, 0);
    }

    /**
     * 带每文档配额的候选选择。
     * <p>三段式挑选：</p>
     * <ol>
     * <li>覆盖模式下，先给每个文档保留一个最高分切片（多样性优先）；</li>
     * <li>再按全局相关性补足剩余席位，同一文档超出配额的切片跳过；</li>
     * <li>每文档配额在覆盖模式下同样生效，防止单文档借覆盖规则霸占席位。</li>
     * </ol>
     */
    static List<RetrievalCandidate> selectRankedCandidates(
            List<RetrievalCandidate> sortedCandidates,
            int topK,
            boolean preferDocumentCoverage,
            int maxChunksPerDocument) {
        int perDocumentCap = Math.max(0, maxChunksPerDocument);
        if (!preferDocumentCoverage) {
            if (perDocumentCap == 0) {
                return sortedCandidates.stream().limit(topK).toList();
            }
            List<RetrievalCandidate> selected = new ArrayList<>();
            Map<Long, Integer> perDocumentCounts = new LinkedHashMap<>();
            for (RetrievalCandidate candidate : sortedCandidates) {
                if (selected.size() >= topK) {
                    break;
                }
                int used = perDocumentCounts.merge(candidate.documentId(), 1, Integer::sum);
                if (used > perDocumentCap) {
                    perDocumentCounts.put(candidate.documentId(), used - 1);
                    continue;
                }
                selected.add(candidate);
            }
            return selected;
        }
        List<RetrievalCandidate> selectedCandidates = new ArrayList<>();
        Set<Long> selectedChunkIds = new LinkedHashSet<>();
        Set<Long> coveredDocumentIds = new LinkedHashSet<>();
        Map<Long, Integer> perDocumentCounts = new LinkedHashMap<>();
        // 第一遍保证「覆盖不同文档」：每个文档只先取它评分最高的那一条，让多个文档都能进上下文。
        for (RetrievalCandidate candidate : sortedCandidates) {
            if (selectedCandidates.size() >= topK) {
                break;
            }
            if (coveredDocumentIds.add(candidate.documentId())) {
                selectedCandidates.add(candidate);
                selectedChunkIds.add(candidate.chunkId());
                perDocumentCounts.merge(candidate.documentId(), 1, Integer::sum);
            }
        }
        // 第二遍再按相关度从高到低补足到 topK（去重已入选的 chunk），
        // 用剩余的高相关切片填满名额，保证覆盖之外也不浪费精度。
        // 补足阶段同样受每文档配额约束，避免覆盖规则被同文档的后续切片绕过。
        for (RetrievalCandidate candidate : sortedCandidates) {
            if (selectedCandidates.size() >= topK) {
                break;
            }
            if (!selectedChunkIds.add(candidate.chunkId())) {
                continue;
            }
            if (perDocumentCap > 0) {
                int used = perDocumentCounts.getOrDefault(candidate.documentId(), 0);
                if (used >= perDocumentCap) {
                    continue;
                }
                perDocumentCounts.merge(candidate.documentId(), 1, Integer::sum);
            }
            selectedCandidates.add(candidate);
        }
        return selectedCandidates;
    }
}
