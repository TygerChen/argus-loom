package com.argus.rag.qa.rag;

import com.argus.rag.common.enums.SystemRole;
import com.argus.rag.common.exception.BusinessException;
import com.argus.rag.common.security.AuthenticatedUser;
import com.argus.rag.common.security.UserContext;
import com.argus.rag.document.security.DocumentRoleGate;
import com.argus.rag.ingestion.mapper.DocumentChunkMapper;
import com.argus.rag.ingestion.model.entity.DocumentChunkEntity;
import com.argus.rag.document.mapper.DocumentMapper;
import com.argus.rag.document.model.entity.DocumentEntity;
import com.argus.rag.qa.model.QueryPlanStrategy;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.argus.rag.qa.model.EvidenceLevel;
import com.argus.rag.qa.model.QueryPlanResult;
import com.argus.rag.qa.service.QueryPlanningService;
import com.argus.rag.qa.support.RrfFusion;
import com.argus.rag.engine.elasticsearch.ElasticsearchChunkIndexService;
import com.argus.rag.engine.pgvector.PgVectorRetrievalAdapter;
import com.argus.rag.engine.youcom.YoucomSearchService;
import com.argus.rag.engine.reranking.RerankingService;
import lombok.extern.slf4j.Slf4j;

import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.*;

/**
 * 混合文档切片检索服务。
 * <p>
 * 核心检索引擎，融合向量语义检索、关键词检索和 You.com 网络搜索三个通道，
 * 向量+关键词通过 RRF（Reciprocal Rank Fusion）算法融合排序，
 * You.com 结果追加到证据列表作为第三条通道，
 * 并支持邻居窗口扩展和证据充分度评估。
 * </p>
 * <h3>检索流程</h3>
 * <ol>
 * <li>查询规划：由 {@link QueryPlanningService} 分析问题并生成检索语句</li>
 * <li>三通道并行检索：向量检索 + 关键词检索 + You.com 网络搜索</li>
 * <li>RRF 融合排序：合并向量+关键词通道结果并按 RRF 评分排序</li>
 * <li>聚类分组：将连续的切片聚合为类簇</li>
 * <li>邻居窗口扩展：扩展上下文窗口以提供更完整的证据</li>
 * <li>You.com 结果追加：将 Web 搜索结果追加到证据列表</li>
 * <li>证据充分度评估：根据检索结果评估证据质量</li>
 * </ol>
 */
@Service
@Slf4j
public class HybridChunkRetrievalService {

    /** 默认邻居窗口大小（向前向后各扩展的切片数） */
    private static final int DEFAULT_NEIGHBOR_WINDOW = 1;

    /** 主构造函数（Spring 注入）使用的默认检索参数，与历史硬编码行为一致。 */
    private static final RetrievalOptions DEFAULT_OPTIONS = RetrievalOptions.defaults();

    /** 向量检索适配器 */
    private final PgVectorRetrievalAdapter vectorRetrievalAdapter;
    /** Elasticsearch 关键词检索服务 */
    private final ElasticsearchChunkIndexService elasticsearchChunkIndexService;
    /** You.com 网络搜索服务 */
    private final YoucomSearchService youcomSearchService;
    /** 文档切片数据访问层 */
    private final DocumentChunkMapper documentChunkMapper;
    /** 查询规划服务 */
    private final QueryPlanningService queryPlanningService;
    /** 文档元数据数据访问层 */
    private final DocumentMapper documentMapper;
    /** 重排序服务 */
    private final RerankingService rerankingService;
    /** 文档角色门槛（单一检索层授权点）；null 仅在历史/单元测试构造路径下使用，生产由 @Autowired 注入真实门 */
    private final DocumentRoleGate roleGate;
    /** 邻居窗口大小 */
    private final int neighborWindow;
    /** 证据文档组装器 */
    private final EvidenceDocumentAssembler evidenceAssembler;
    /** 可配置检索参数（通道 topK、重排召回、全局文档上限、每文档 chunk 上限） */
    private final RetrievalOptions retrievalOptions;

    /**
     * 检索并行通道（P2-04）：生产由 {@code AppThreadPoolConfig.retrievalExecutor} 注入，
     * 用于并行收集网页搜索子查询；未注入（单元测试/未配置）时自动退化为顺序执行。
     */
    private java.util.concurrent.Executor retrievalExecutor;

    /** 可选注入：检索并行执行器。缺失时不影响功能，只是并行度退化为 1。 */
    @Autowired(required = false)
    public void setRetrievalExecutor(
            @org.springframework.beans.factory.annotation.Qualifier("retrievalExecutor")
            java.util.concurrent.Executor retrievalExecutor) {
        this.retrievalExecutor = retrievalExecutor;
    }

    /**
     * 主构造函数（Spring 注入，含角色门槛），使用默认邻居窗口。
     */
    @Autowired
    public HybridChunkRetrievalService(
            PgVectorRetrievalAdapter vectorRetrievalAdapter,
            ElasticsearchChunkIndexService elasticsearchChunkIndexService,
            YoucomSearchService youcomSearchService,
            DocumentChunkMapper documentChunkMapper,
            QueryPlanningService queryPlanningService,
            DocumentMapper documentMapper,
            DocumentRoleGate roleGate,
            RetrievalOptions retrievalOptions) {
        this(vectorRetrievalAdapter, elasticsearchChunkIndexService, youcomSearchService, documentChunkMapper, queryPlanningService, documentMapper, null, roleGate, DEFAULT_NEIGHBOR_WINDOW, retrievalOptions);
    }

    public HybridChunkRetrievalService(
            PgVectorRetrievalAdapter vectorRetrievalAdapter,
            ElasticsearchChunkIndexService elasticsearchChunkIndexService,
            YoucomSearchService youcomSearchService,
            DocumentChunkMapper documentChunkMapper,
            QueryPlanningService queryPlanningService,
            DocumentMapper documentMapper,
            int neighborWindow) {
        this(vectorRetrievalAdapter, elasticsearchChunkIndexService, youcomSearchService,
                documentChunkMapper, queryPlanningService, documentMapper, null, null, neighborWindow);
    }

    public HybridChunkRetrievalService(
            PgVectorRetrievalAdapter vectorRetrievalAdapter,
            ElasticsearchChunkIndexService elasticsearchChunkIndexService,
            YoucomSearchService youcomSearchService,
            DocumentChunkMapper documentChunkMapper,
            QueryPlanningService queryPlanningService,
            DocumentMapper documentMapper,
            RerankingService rerankingService,
            int neighborWindow) {
        this(vectorRetrievalAdapter, elasticsearchChunkIndexService, youcomSearchService,
                documentChunkMapper, queryPlanningService, documentMapper, rerankingService, null, neighborWindow);
    }

    public HybridChunkRetrievalService(
            PgVectorRetrievalAdapter vectorRetrievalAdapter,
            ElasticsearchChunkIndexService elasticsearchChunkIndexService,
            YoucomSearchService youcomSearchService,
            DocumentChunkMapper documentChunkMapper,
            QueryPlanningService queryPlanningService,
            DocumentMapper documentMapper,
            RerankingService rerankingService,
            DocumentRoleGate roleGate,
            int neighborWindow) {
        this(vectorRetrievalAdapter, elasticsearchChunkIndexService, youcomSearchService, documentChunkMapper, queryPlanningService, documentMapper, rerankingService, roleGate, neighborWindow, DEFAULT_OPTIONS);
    }

    /**
     * 全参构造函数：支持注入可配置检索参数（生产从 {@code argus.rag.retrieval.*} 绑定）。
     */
    public HybridChunkRetrievalService(
            PgVectorRetrievalAdapter vectorRetrievalAdapter,
            ElasticsearchChunkIndexService elasticsearchChunkIndexService,
            YoucomSearchService youcomSearchService,
            DocumentChunkMapper documentChunkMapper,
            QueryPlanningService queryPlanningService,
            DocumentMapper documentMapper,
            RerankingService rerankingService,
            DocumentRoleGate roleGate,
            int neighborWindow,
            RetrievalOptions retrievalOptions) {
        this.vectorRetrievalAdapter = vectorRetrievalAdapter;
        this.elasticsearchChunkIndexService = elasticsearchChunkIndexService;
        this.youcomSearchService = youcomSearchService;
        this.documentChunkMapper = documentChunkMapper;
        this.queryPlanningService = queryPlanningService;
        this.documentMapper = documentMapper;
        this.rerankingService = rerankingService != null ? rerankingService : new RerankingService.NoOp();
        this.roleGate = roleGate;
        this.neighborWindow = Math.max(0, neighborWindow);
        this.retrievalOptions = retrievalOptions != null ? retrievalOptions : DEFAULT_OPTIONS;
        this.evidenceAssembler = new EvidenceDocumentAssembler(this.neighborWindow, documentChunkMapper);
    }

    /**
     * 执行混合检索，返回包含证据文档和证据等级的完整检索结果。
     * <p>
     * 流程：查询规划 → 三通道并行检索 → RRF 融合排序 → 聚类分组 → 窗口扩展 → You.com 结果追加 → 证据评估。
     * </p>
     *
     * @param groupId  群组 ID，限定检索范围
     * @param question 用户问题
     * @param topK     返回的最大文档数
     * @return 检索证据束
     */
    public RetrievedEvidenceBundle retrieve(Long groupId, String question, int topK) {
        long startNano = System.nanoTime();
        Long validGroupId = RetrievalRowGuard.requirePositiveGroupId(groupId);
        String normalizedQuestion = RetrievalRowGuard.requireQuestion(question);
        int validTopK = topK > 0 ? topK : 5;

        // 检索层角色门槛（单一授权点，fail-closed）：
        // 先按当前用户角色解析该群组可读的 doc 集合。null=未挂门（历史/单元测试构造路径）→ 放行；
        // 非空但为空集=当前角色无一可读文档 → 直接返回“无可检索信息”。
        // 该返回与“知识库内无此信息”完全一致，绝不返回“无权访问”，杜绝存在性泄露。
        Set<Long> allowedDocIds = effectiveAllowedDocumentIds(validGroupId);
        if (allowedDocIds != null && allowedDocIds.isEmpty()) {
            log.info("检索层角色门槛(fail-closed): 当前角色无可检索文档, groupId={}, role={}", validGroupId, currentRole());
            return RetrievedEvidenceBundle.empty();
        }

        log.info("混合检索开始: groupId={}, topK={}, questionLength={}", validGroupId, validTopK,
                normalizedQuestion.length());
        QueryPlanResult queryPlan = queryPlanningService.plan(normalizedQuestion);
        log.info("查询规划完成: groupId={}, strategy={}, queries={}", validGroupId, queryPlan.strategy(),
                queryPlan.queries());

        // GLOBAL（全局遍历）策略：由查询规划阶段让大模型判断，当问题是要"整体/概览"整个知识库时，
        // 模型会返回 GLOBAL 而非走"向量+关键词"的正常混合检索。此时改用 retrieveGlobal：
        // 直接扫同 group 所有 READY 文档的首个切片做一份概览，避免混合检索的精确命中错过全貌。
        if (queryPlan.strategy() == QueryPlanStrategy.GLOBAL) {
            log.info("执行全局遍历检索: groupId={}", validGroupId);
            return retrieveGlobal(validGroupId, normalizedQuestion);
        }

        Map<Long, RetrievalCandidate> candidates = new LinkedHashMap<>();

        for (String plannedQuery : queryPlan.queries()) {
            mergeVectorHits(candidates, validGroupId, plannedQuery);
            mergeKeywordHits(candidates, validGroupId, plannedQuery);
        }

        long vectorHitCount = candidates.values().stream().filter(c -> c.vectorMatched).count();
        long keywordHitCount = candidates.values().stream().filter(c -> c.keywordMatched).count();
        log.info("双路检索完成: groupId={}, candidateCount={}, vectorHits={}, keywordHits={}",
                validGroupId, candidates.size(), vectorHitCount, keywordHitCount);

        // 检索层角色门槛：全拢后仅保留当前角色可读文档的切片，杜绝受限切片进入重排/上下文/大模型。
        if (allowedDocIds != null) {
            int before = candidates.size();
            candidates.values().removeIf(c -> !allowedDocIds.contains(c.documentId()));
            if (candidates.size() < before) {
                log.info("检索层角色过滤剔除: groupId={}, removed={}, kept={}",
                        validGroupId, before - candidates.size(), candidates.size());
            }
        }

        if (candidates.isEmpty()) {
            long elapsedMs = (System.nanoTime() - startNano) / 1_000_000;
            log.info("混合检索结果为空: groupId={}, elapsedMs={}", validGroupId, elapsedMs);
            return RetrievedEvidenceBundle.empty();
        }

        List<RetrievalCandidate> sortedCandidates = candidates.values().stream()
                .sorted(Comparator
                        .comparingDouble(RetrievalCandidate::rankingScore).reversed()
                        .thenComparing(RetrievalCandidate::chunkId))
                .toList();
        // 重排步骤：用 Cross-Encoder 模型给最靠前的候选重新打分
        List<RetrievalCandidate> rerankedCandidates = applyReranking(normalizedQuestion, sortedCandidates, validTopK);
        boolean preferDocumentCoverage = RetrievalCandidateSelector.shouldPreferDocumentCoverage(normalizedQuestion);
        String coverageMode = preferDocumentCoverage ? "DOCUMENT_COVERAGE" : "RELEVANCE_ONLY";
        List<RetrievalCandidate> rankedCandidates = RetrievalCandidateSelector.selectRankedCandidates(
                rerankedCandidates,
                validTopK,
                preferDocumentCoverage,
                retrievalOptions.maxChunksPerDocument());
        List<RetrievalCluster> rankedClusters = RetrievalClusterBuilder.buildClusters(rankedCandidates);
        log.info("RRF融合排序完成: groupId={}, rankedCandidates={}, clusters={}",
                validGroupId, rankedCandidates.size(), rankedClusters.size());

        List<Long> chunkIds = rankedCandidates.stream().map(RetrievalCandidate::chunkId).toList();
        Map<Long, Map<String, Object>> rowByChunkId = EvidenceDocumentAssembler.indexRows(
                documentChunkMapper.selectQaReadyChunksByIds(validGroupId, chunkIds));
        Map<Long, List<DocumentChunkEntity>> chunkWindowCache = new LinkedHashMap<>();
        List<Document> documents = new ArrayList<>();
        int evidenceIndex = 1;
        for (RetrievalCluster cluster : rankedClusters) {
            Map<String, Object> row = rowByChunkId.get(cluster.primaryChunkId());
            if (row == null) {
                continue;
            }
            Document document = evidenceAssembler.toDocument("E" + evidenceIndex, row, cluster, chunkWindowCache, coverageMode);
            if (document == null) {
                continue;
            }
            documents.add(document);
            evidenceIndex++;
        }
        if (documents.isEmpty()) {
            long elapsedMs = (System.nanoTime() - startNano) / 1_000_000;
            log.info("混合检索证据组装为空: groupId={}, elapsedMs={}", validGroupId, elapsedMs);
            return RetrievedEvidenceBundle.empty();
        }

        // 并行收集 You.com 结果（服务未启用或未注入时跳过，避免日志链路报 NPE）
        List<Document> webDocs = searchWebQueries(queryPlan.queries());

        // 单线程编号并追加
        for (Document webDoc : webDocs) {
            String newId = "E" + evidenceIndex;
            Map<String, Object> metadata = new LinkedHashMap<>(webDoc.getMetadata());
            metadata.put("evidenceId", newId);
            documents.add(Document.builder()
                    .id(newId)
                    .text(webDoc.getText())
                    .metadata(metadata)
                    .build());
            evidenceIndex++;
        }
        log.info("You.com 搜索结果追加完成: groupId={}, webResults={}",
                validGroupId, evidenceIndex - rankedClusters.size() - 1);

        EvidenceLevel evidenceLevel = EvidenceAssessor.evaluate(documents);
        long elapsedMs = (System.nanoTime() - startNano) / 1_000_000;
        log.info("混合检索完成: groupId={}, evidenceCount={}, evidenceLevel={}, elapsedMs={}",
                validGroupId, documents.size(), evidenceLevel, elapsedMs);
        List<RetrievalEvidence> typedEvidences = documents.stream()
                .map(RetrievalEvidence::fromDocument)
                .toList();
        return new RetrievedEvidenceBundle(documents, evidenceLevel, EvidenceAssessor.guidanceFor(evidenceLevel), typedEvidences);
    }

    /**
     * 并行收集网页搜索结果（P2-04）。
     *
     * <p>此前用 {@code parallelStream()} 落在 JVM 公共 ForkJoinPool：既与 JVM 其他并行任务争抢线程，
     * 也无法限流/观测。现改为专用 {@code retrievalExecutor}（有界队列 + CallerRuns）：</p>
     * <ul>
     *   <li>执行器未注入（单元测试/未配置）或提交被拒绝 → 退化为顺序执行，结果不变；</li>
     *   <li>单个子查询失败只记日志并跳过，不影响其余查询与证据组装（网页搜索属可选增强通道）。</li>
     * </ul>
     */
    List<Document> searchWebQueries(List<String> queries) {
        if (youcomSearchService == null || queries == null || queries.isEmpty()) {
            return List.of();
        }
        if (retrievalExecutor == null || queries.size() == 1) {
            return searchWebSequentially(queries);
        }
        List<java.util.concurrent.CompletableFuture<List<Document>>> futures = new ArrayList<>(queries.size());
        try {
            for (String query : queries) {
                futures.add(java.util.concurrent.CompletableFuture.supplyAsync(
                        () -> youcomSearchService.search(query), retrievalExecutor));
            }
        } catch (java.util.concurrent.RejectedExecutionException e) {
            log.warn("网页搜索并行提交被拒绝，退化为顺序执行: {}", e.getMessage());
            return searchWebSequentially(queries);
        }
        List<Document> merged = new ArrayList<>();
        for (int i = 0; i < futures.size(); i++) {
            try {
                List<Document> docs = futures.get(i).join();
                if (docs != null) {
                    merged.addAll(docs);
                }
            } catch (Exception e) {
                log.warn("网页搜索子查询失败，已跳过: query={}, error={}", queries.get(i), e.getMessage());
            }
        }
        return merged;
    }

    /**
     * 顺序执行多个网页子查询，把各批次结果合并为一份文档列表。
     * <p>单个子查询失败只记日志并跳过、不影响其余查询（网页搜索是可选增强通道，
     * 不因一条失败拖垮整条检索）。</p>
     *
     * @param queries 待执行的网页搜索词
     * @return 合并后的网页文档列表
     */
    private List<Document> searchWebSequentially(List<String> queries) {
        List<Document> merged = new ArrayList<>();
        for (String query : queries) {
            try {
                List<Document> docs = youcomSearchService.search(query);
                if (docs != null) {
                    merged.addAll(docs);
                }
            } catch (Exception e) {
                log.warn("网页搜索子查询失败，已跳过: query={}, error={}", query, e.getMessage());
            }
        }
        return merged;
    }

    /**
     * 执行全局遍历检索，提取群组内所有 READY 状态文档的首个切片。
     */
    private RetrievedEvidenceBundle retrieveGlobal(Long groupId, String question) {
        List<DocumentEntity> docs = documentMapper.selectList(
                new LambdaQueryWrapper<DocumentEntity>()
                        .eq(DocumentEntity::getGroupId, groupId)
                        .eq(DocumentEntity::getStatus, "READY")
                        .eq(DocumentEntity::getDeleted, false)
                        .orderByAsc(DocumentEntity::getId)
                        .last("LIMIT " + retrievalOptions.maxGlobalDocuments())
        );

        if (docs.isEmpty()) {
            log.info("全局遍历检索: 知识库中无可用文档");
            return RetrievedEvidenceBundle.empty();
        }

        // 检索层角色门槛同样是全局遍历的唯一授权点：剔除当前角色不可读的文档。
        Set<Long> allowedDocIds = effectiveAllowedDocumentIds(groupId);
        if (allowedDocIds != null) {
            docs.removeIf(doc -> !allowedDocIds.contains(doc.getId()));
        }
        if (docs.isEmpty()) {
            log.info("全局遍历检索: 角色过滤后无可读文档");
            return RetrievedEvidenceBundle.empty();
        }

        List<Document> documents = new ArrayList<>();
        int evidenceIndex = 1;

        for (DocumentEntity doc : docs) {
            List<DocumentChunkEntity> activeChunks = documentChunkMapper.selectReadyActiveChunksByDocumentId(groupId, doc.getId());
            if (activeChunks.isEmpty()) {
                continue;
            }
            
            DocumentChunkEntity primaryChunk = activeChunks.stream()
                    .filter(c -> c.getChunkIndex() != null && c.getChunkIndex() == 0)
                    .findFirst()
                    .orElse(activeChunks.get(0));

            if (primaryChunk == null || !StringUtils.hasText(primaryChunk.getChunkText())) {
                continue;
            }

            Map<String, Object> metadata = new LinkedHashMap<>();
            String evidenceId = "E" + evidenceIndex;
            metadata.put("evidenceId", evidenceId);
            metadata.put("groupId", groupId);
            metadata.put("documentId", doc.getId());
            metadata.put("chunkId", primaryChunk.getId());
            metadata.put("chunkIndex", primaryChunk.getChunkIndex());
            metadata.put("fileName", doc.getFileName());
            metadata.put("score", 1.0D);
            metadata.put("retrievalSource", "GLOBAL");
            metadata.put("coverageMode", "GLOBAL_TRAVERSAL");

            String evidenceText = "文件名：" + doc.getFileName() + "\n" + primaryChunk.getChunkText().trim();

            documents.add(Document.builder()
                    .id(evidenceId)
                    .text(evidenceText)
                    .metadata(metadata)
                    .build());
            
            evidenceIndex++;
        }

        if (documents.isEmpty()) {
            return RetrievedEvidenceBundle.empty();
        }

        EvidenceLevel evidenceLevel = EvidenceLevel.SUFFICIENT;
        List<RetrievalEvidence> typedEvidences = documents.stream()
                .map(RetrievalEvidence::fromDocument)
                .toList();
        return new RetrievedEvidenceBundle(
                documents, 
                evidenceLevel, 
                "当前为全局遍历模式，已列出所有可用文档的概览。请根据这些文档分别总结回答，仍然不得超出提供的信息进行臆测。",
                typedEvidences
        );
    }

    /**
     * 选择最终进入上下文的候选切片。
     * 委托给 {@link RetrievalCandidateSelector}。
     */
    private List<RetrievalCandidate> selectRankedCandidates(
            List<RetrievalCandidate> sortedCandidates,
            int topK,
            boolean preferDocumentCoverage) {
        return RetrievalCandidateSelector.selectRankedCandidates(sortedCandidates, topK, preferDocumentCoverage,
                retrievalOptions.maxChunksPerDocument());
    }

    /** 解析该群组下、当前用户可读的 docId 集合；未挂门（null）时返回 null 表示不限制。 */
    private Set<Long> effectiveAllowedDocumentIds(Long groupId) {
        if (roleGate == null) {
            return null;
        }
        return roleGate.allowedDocumentIds(groupId);
    }

    /** 当前请求的系统角色；未认证/缺失 → USER（最低权限，fail-closed）。 */
    private SystemRole currentRole() {
        AuthenticatedUser user = UserContext.get();
        return user != null && user.systemRole() != null ? user.systemRole() : SystemRole.USER;
    }

    /** 判断问题是否更需要跨文档覆盖。委托给 {@link RetrievalCandidateSelector}。 */
    private static boolean shouldPreferDocumentCoverage(String question) {
        return RetrievalCandidateSelector.shouldPreferDocumentCoverage(question);
    }

    /** 合并向量检索结果到候选集合，累加 RRF 评分 */
    private void mergeVectorHits(Map<Long, RetrievalCandidate> candidates, Long groupId, String query) {

        List<PgVectorRetrievalAdapter.VectorHit> vectorHits = vectorRetrievalAdapter.search(groupId, query, retrievalOptions.channelTopK());

        for (int index = 0; index < vectorHits.size(); index++) {
            PgVectorRetrievalAdapter.VectorHit hit = vectorHits.get(index);
            RetrievalCandidate candidate = candidates.computeIfAbsent(hit.chunkId(), ignored -> RetrievalCandidate.fromVectorHit(hit));
            candidate.mergeVectorHit(hit, index + 1);
        }
    }

    /** 合并关键词检索结果到候选集合，累加 RRF 评分 */
    private void mergeKeywordHits(Map<Long, RetrievalCandidate> candidates, Long groupId, String query) {

        List<ElasticsearchChunkIndexService.KeywordHit> keywordHits = elasticsearchChunkIndexService.search(groupId, query, retrievalOptions.channelTopK());
        for (int index = 0; index < keywordHits.size(); index++) {

            ElasticsearchChunkIndexService.KeywordHit hit = keywordHits.get(index);
            RetrievalCandidate candidate = candidates.computeIfAbsent(hit.chunkId(), ignored -> RetrievalCandidate.fromKeywordHit(hit));
            candidate.mergeKeywordHit(hit, index + 1);
        }
    }

    /** 将数据库查询结果按 chunkId 索引。委托给 {@link EvidenceDocumentAssembler}。 */
    private static Map<Long, Map<String, Object>> indexRows(List<Map<String, Object>> rows) {
        return EvidenceDocumentAssembler.indexRows(rows);
    }

    /** 将检索候选和数据库数据组装为 Document。委托给 {@link EvidenceDocumentAssembler}。 */
    private Document toDocument(
            String evidenceId,
            Map<String, Object> row,
            RetrievalCluster cluster,
            Map<Long, List<DocumentChunkEntity>> chunkWindowCache,
            String coverageMode) {
        return evidenceAssembler.toDocument(evidenceId, row, cluster, chunkWindowCache, coverageMode);
    }

    /** 构建证据窗口文本。委托给 {@link EvidenceDocumentAssembler}。 */
    private String buildEvidenceWindow(
            Map<String, Object> row,
            RetrievalCluster cluster,
            Map<Long, List<DocumentChunkEntity>> chunkWindowCache) {
        return evidenceAssembler.buildEvidenceWindow(row, cluster, chunkWindowCache);
    }

    /** 将候选切片聚合为类簇。委托给 {@link RetrievalClusterBuilder}。 */
    private List<RetrievalCluster> buildClusters(List<RetrievalCandidate> rankedCandidates) {
        return RetrievalClusterBuilder.buildClusters(rankedCandidates);
    }

    /**
     * 在挑选最终上下文前，对最靠前的候选做重排序（rerank）。
     * <p>出错时回退到原始顺序，保证链路不中断。</p>
     */
    private List<RetrievalCandidate> applyReranking(
            String query,
            List<RetrievalCandidate> candidates,
            int topK
    ) {
        if (candidates.isEmpty()) return candidates;
        try {
            int recallSize = Math.min(candidates.size(), retrievalOptions.recallK());
            List<RetrievalCandidate> recall = candidates.subList(0, recallSize);
            List<String> texts = recall.stream()
                    .map(c -> c.chunkText != null ? c.chunkText : "")
                    .toList();
            List<RerankingService.RerankResult> results = rerankingService.rerank(query, texts);
            if (results.size() != recall.size()) return candidates;
            // 重排：把结果映射回原始召回位置，再按分数降序
            List<RetrievalCandidate> reranked = new ArrayList<>();
            for (RerankingService.RerankResult r : results) {
                RetrievalCandidate c = recall.get(r.index());
                c.rerankScore = r.score();
                reranked.add(c);
            }
            // 超出召回数量的候选保持原序不动，直接接在重排结果后面
            if (recallSize < candidates.size()) {
                reranked.addAll(candidates.subList(recallSize, candidates.size()));
            }
            return reranked;
        } catch (Exception e) {
            log.warn("Reranking failed, falling back to RRF order: {}", e.getMessage());
            return candidates;
        }
    }
}
