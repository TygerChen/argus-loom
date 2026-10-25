# qa · QaChatService.askWithUsage 调用链

> 由 `docs/callgraph/gen_all.py` 静态分析自动生成（纯说明，不改任何代码）。
> 每个节点 = 一个被调用的方法（`方法名:文件内行号`）；箭头 = 调用方向；getter/简单投影已省略。

```mermaid
flowchart TD
  m1["QaChatService.askWithUsage :158"]
  m2["LlmDialogueMetrics.start :33"]
  m3["ReadyChunkDocumentRetriever.retrieveEvidence :95"]
  m4["QaChatService.AskResult :93"]
  m5["AskQuestionResponse.unanswered :66"]
  m6["EvidenceOverviewAssembler.assemble :36"]
  m7["AskQuestionResponse.answered :50"]
  m8["CitationAssembler.assembleDocuments :30"]
  m9["LlmDialogueMetrics.finish :45"]
  m10["HybridChunkRetrievalService.retrieve :174"]
  m11["AskQuestionResponse.AskQuestionResponse :39"]
  m12["EvidenceOverviewAssembler.readCoverageMode :116"]
  m13["EvidenceOverviewAssembler.readLong :147"]
  m14["EvidenceOverviewAssembler.readFileName :172"]
  m15["EvidenceOverviewAssembler.GroupAccumulator :192"]
  m16["EvidenceOverviewAssembler.toSnippet :91"]
  m17["EvidenceOverviewAssembler.buildWarnings :126"]
  m18["CitationAssembler.toCitation :48"]
  m19["LlmDialogueMetrics.timer :54"]
  m20["HybridChunkRetrievalService.effectiveAllowedDocumentIds :455"]
  m21["HybridChunkRetrievalService.currentRole :463"]
  m22["QueryPlanningService.plan :62"]
  m23["HybridChunkRetrievalService.retrieveGlobal :365"]
  m24["HybridChunkRetrievalService.mergeVectorHits :474"]
  m25["HybridChunkRetrievalService.mergeKeywordHits :486"]
  m26["HybridChunkRetrievalService.applyReranking :533"]
  m27["EvidenceOverviewAssembler.readText :168"]
  m28["EvidenceOverviewAssembler.readInteger :155"]
  m29["EvidenceOverviewAssembler.readScore :159"]
  m30["EvidenceOverviewAssembler.readTextOrDefault :163"]
  m31["EvidenceOverviewAssembler.summarize :134"]
  m32["CitationAssembler.readFileName :90"]
  m33["AskQuestionResponse.Citation :108"]
  m34["CitationAssembler.readLong :66"]
  m35["CitationAssembler.readInteger :75"]
  m36["CitationAssembler.readScore :80"]
  m37["DocumentRoleGate.allowedDocumentIds :57"]
  m38["UserContext.get :22"]
  m39["QueryPlanningService.requireQuestion :142"]
  m40["QueryPlanningService.validatePlan :84"]
  m41["QueryPlanResult.fallback :30"]
  m42["PgVectorRetrievalAdapter.search :73"]
  m43["ElasticsearchChunkIndexService.search :210"]
  m44["RerankingService.rerank :34"]
  m45["MetadataReaders.text :55"]
  m46["MetadataReaders.intValue :29"]
  m47["MetadataReaders.score :45"]
  m48["CitationAssembler.readText :85"]
  m49["DocumentRoleGate.currentRole :42"]
  m50["QueryPlanningService.normalizeQueries :116"]
  m51["QueryPlanningService.buildRewriteQueries :108"]
  m52["QueryPlanningService.limitQueries :134"]
  m53["ElasticsearchChunkIndexService.ensureIndexInitialized :378"]
  m54["ElasticsearchChunkIndexService.sendJsonRequest :624"]
  m55["MetadataReaders.doubleValue :35"]
  m56["ElasticsearchChunkIndexService.indexExists :402"]
  m57["ElasticsearchChunkIndexService.createIndex :432"]

  m1 --> m2
  m1 --> m3
  m1 --> m4
  m1 --> m5
  m1 --> m6
  m1 --> m7
  m1 --> m8
  m1 --> m9
  m3 --> m10
  m5 --> m11
  m6 --> m12
  m6 --> m13
  m6 --> m14
  m6 --> m15
  m6 --> m16
  m6 --> m17
  m8 --> m18
  m9 --> m19
  m10 --> m20
  m10 --> m21
  m10 --> m22
  m10 --> m23
  m10 --> m24
  m10 --> m25
  m10 --> m26
  m12 --> m27
  m16 --> m28
  m16 --> m29
  m16 --> m30
  m16 --> m31
  m18 --> m32
  m18 --> m33
  m18 --> m34
  m18 --> m35
  m18 --> m36
  m20 --> m37
  m21 --> m38
  m22 --> m39
  m22 --> m40
  m22 --> m41
  m24 --> m42
  m25 --> m43
  m26 --> m44
  m27 --> m45
  m28 --> m46
  m29 --> m47
  m32 --> m48
  m37 --> m49
  m40 --> m50
  m40 --> m51
  m40 --> m52
  m43 --> m53
  m43 --> m54
  m47 --> m55
  m53 --> m56
  m53 --> m57
```
