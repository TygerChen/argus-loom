# qa · QaChatService.ask 调用链

> 由 `docs/callgraph/gen_all.py` 静态分析自动生成（纯说明，不改任何代码）。
> 每个节点 = 一个被调用的方法（`方法名:文件内行号`）；箭头 = 调用方向；getter/简单投影已省略。

```mermaid
flowchart TD
  m1["QaChatService.ask :151"]
  m2["QaChatService.askWithUsage :158"]
  m3["LlmDialogueMetrics.start :33"]
  m4["ReadyChunkDocumentRetriever.retrieveEvidence :95"]
  m5["QaChatService.AskResult :93"]
  m6["AskQuestionResponse.unanswered :66"]
  m7["EvidenceOverviewAssembler.assemble :36"]
  m8["AskQuestionResponse.answered :50"]
  m9["CitationAssembler.assembleDocuments :30"]
  m10["LlmDialogueMetrics.finish :45"]
  m11["HybridChunkRetrievalService.retrieve :174"]
  m12["AskQuestionResponse.AskQuestionResponse :39"]
  m13["EvidenceOverviewAssembler.readCoverageMode :116"]
  m14["EvidenceOverviewAssembler.readLong :147"]
  m15["EvidenceOverviewAssembler.readFileName :172"]
  m16["EvidenceOverviewAssembler.GroupAccumulator :192"]
  m17["EvidenceOverviewAssembler.toSnippet :91"]
  m18["EvidenceOverviewAssembler.buildWarnings :126"]
  m19["CitationAssembler.toCitation :48"]
  m20["LlmDialogueMetrics.timer :54"]
  m21["HybridChunkRetrievalService.effectiveAllowedDocumentIds :455"]
  m22["HybridChunkRetrievalService.currentRole :463"]
  m23["QueryPlanningService.plan :62"]
  m24["HybridChunkRetrievalService.retrieveGlobal :365"]
  m25["HybridChunkRetrievalService.mergeVectorHits :474"]
  m26["HybridChunkRetrievalService.mergeKeywordHits :486"]
  m27["HybridChunkRetrievalService.applyReranking :533"]
  m28["EvidenceOverviewAssembler.readText :168"]
  m29["EvidenceOverviewAssembler.readInteger :155"]
  m30["EvidenceOverviewAssembler.readScore :159"]
  m31["EvidenceOverviewAssembler.readTextOrDefault :163"]
  m32["EvidenceOverviewAssembler.summarize :134"]
  m33["CitationAssembler.readFileName :90"]
  m34["AskQuestionResponse.Citation :108"]
  m35["CitationAssembler.readLong :66"]
  m36["CitationAssembler.readInteger :75"]
  m37["CitationAssembler.readScore :80"]
  m38["DocumentRoleGate.allowedDocumentIds :57"]
  m39["UserContext.get :22"]
  m40["QueryPlanningService.requireQuestion :142"]
  m41["QueryPlanningService.validatePlan :84"]
  m42["QueryPlanResult.fallback :30"]
  m43["PgVectorRetrievalAdapter.search :73"]
  m44["ElasticsearchChunkIndexService.search :210"]
  m45["RerankingService.rerank :34"]
  m46["MetadataReaders.text :55"]
  m47["MetadataReaders.intValue :29"]
  m48["MetadataReaders.score :45"]
  m49["CitationAssembler.readText :85"]
  m50["DocumentRoleGate.currentRole :42"]
  m51["QueryPlanningService.normalizeQueries :116"]
  m52["QueryPlanningService.buildRewriteQueries :108"]
  m53["QueryPlanningService.limitQueries :134"]
  m54["ElasticsearchChunkIndexService.ensureIndexInitialized :378"]
  m55["ElasticsearchChunkIndexService.sendJsonRequest :624"]
  m56["MetadataReaders.doubleValue :35"]
  m57["ElasticsearchChunkIndexService.indexExists :402"]
  m58["ElasticsearchChunkIndexService.createIndex :432"]

  m1 --> m2
  m2 --> m3
  m2 --> m4
  m2 --> m5
  m2 --> m6
  m2 --> m7
  m2 --> m8
  m2 --> m9
  m2 --> m10
  m4 --> m11
  m6 --> m12
  m7 --> m13
  m7 --> m14
  m7 --> m15
  m7 --> m16
  m7 --> m17
  m7 --> m18
  m9 --> m19
  m10 --> m20
  m11 --> m21
  m11 --> m22
  m11 --> m23
  m11 --> m24
  m11 --> m25
  m11 --> m26
  m11 --> m27
  m13 --> m28
  m17 --> m29
  m17 --> m30
  m17 --> m31
  m17 --> m32
  m19 --> m33
  m19 --> m34
  m19 --> m35
  m19 --> m36
  m19 --> m37
  m21 --> m38
  m22 --> m39
  m23 --> m40
  m23 --> m41
  m23 --> m42
  m25 --> m43
  m26 --> m44
  m27 --> m45
  m28 --> m46
  m29 --> m47
  m30 --> m48
  m33 --> m49
  m38 --> m50
  m41 --> m51
  m41 --> m52
  m41 --> m53
  m44 --> m54
  m44 --> m55
  m48 --> m56
  m54 --> m57
  m54 --> m58
```
