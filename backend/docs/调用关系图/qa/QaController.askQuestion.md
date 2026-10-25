# qa · QaController.askQuestion 调用链

> 由 `docs/callgraph/gen_all.py` 静态分析自动生成（纯说明，不改任何代码）。
> 每个节点 = 一个被调用的方法（`方法名:文件内行号`）；箭头 = 调用方向；getter/简单投影已省略。

```mermaid
flowchart TD
  m1["QaController.askQuestion :75"]
  m2["QaService.ask :86"]
  m3["GroupMembershipService.requireGroupReadable :66"]
  m4["QaChatService.askWithUsage :158"]
  m5["QaService.saveQaRecord :117"]
  m6["QaService.recordUsage :220"]
  m7["CurrentUserService.requireBusinessUser :66"]
  m8["GroupMembershipService.requireGroupId :113"]
  m9["LlmDialogueMetrics.start :33"]
  m10["ReadyChunkDocumentRetriever.retrieveEvidence :95"]
  m11["QaChatService.AskResult :93"]
  m12["AskQuestionResponse.unanswered :66"]
  m13["EvidenceOverviewAssembler.assemble :36"]
  m14["AskQuestionResponse.answered :50"]
  m15["CitationAssembler.assembleDocuments :30"]
  m16["LlmDialogueMetrics.finish :45"]
  m17["QaRecordPersistenceService.saveCompleted :51"]
  m18["HybridChunkRetrievalService.retrieve :174"]
  m19["AskQuestionResponse.AskQuestionResponse :39"]
  m20["EvidenceOverviewAssembler.readCoverageMode :116"]
  m21["EvidenceOverviewAssembler.readLong :147"]
  m22["EvidenceOverviewAssembler.readFileName :172"]
  m23["EvidenceOverviewAssembler.GroupAccumulator :192"]
  m24["EvidenceOverviewAssembler.toSnippet :91"]
  m25["EvidenceOverviewAssembler.buildWarnings :126"]
  m26["CitationAssembler.toCitation :48"]
  m27["LlmDialogueMetrics.timer :54"]
  m28["QaRecordPersistenceService.toCitationEntities :101"]
  m29["HybridChunkRetrievalService.effectiveAllowedDocumentIds :455"]
  m30["HybridChunkRetrievalService.currentRole :463"]
  m31["QueryPlanningService.plan :62"]
  m32["HybridChunkRetrievalService.retrieveGlobal :365"]
  m33["HybridChunkRetrievalService.mergeVectorHits :474"]
  m34["HybridChunkRetrievalService.mergeKeywordHits :486"]
  m35["HybridChunkRetrievalService.applyReranking :533"]
  m36["EvidenceOverviewAssembler.readText :168"]
  m37["EvidenceOverviewAssembler.readInteger :155"]
  m38["EvidenceOverviewAssembler.readScore :159"]
  m39["EvidenceOverviewAssembler.readTextOrDefault :163"]
  m40["EvidenceOverviewAssembler.summarize :134"]
  m41["CitationAssembler.readFileName :90"]
  m42["AskQuestionResponse.Citation :108"]
  m43["CitationAssembler.readLong :66"]
  m44["CitationAssembler.readInteger :75"]
  m45["CitationAssembler.readScore :80"]
  m46["QaRecordPersistenceService.toCitationEntity :116"]
  m47["DocumentRoleGate.allowedDocumentIds :57"]
  m48["UserContext.get :22"]
  m49["QueryPlanningService.requireQuestion :142"]
  m50["QueryPlanningService.validatePlan :84"]
  m51["QueryPlanResult.fallback :30"]
  m52["PgVectorRetrievalAdapter.search :73"]
  m53["ElasticsearchChunkIndexService.search :210"]
  m54["RerankingService.rerank :34"]
  m55["MetadataReaders.text :55"]
  m56["MetadataReaders.intValue :29"]
  m57["MetadataReaders.score :45"]
  m58["CitationAssembler.readText :85"]
  m59["QaRecordPersistenceService.readText :163"]
  m60["QaRecordPersistenceService.readLong :148"]
  m61["QaRecordPersistenceService.readInteger :153"]
  m62["QaRecordPersistenceService.readDouble :158"]
  m63["QaRecordPersistenceService.snippet :169"]
  m64["DocumentRoleGate.currentRole :42"]
  m65["QueryPlanningService.normalizeQueries :116"]
  m66["QueryPlanningService.buildRewriteQueries :108"]
  m67["QueryPlanningService.limitQueries :134"]
  m68["ElasticsearchChunkIndexService.ensureIndexInitialized :378"]
  m69["ElasticsearchChunkIndexService.sendJsonRequest :624"]
  m70["MetadataReaders.doubleValue :35"]
  m71["MetadataReaders.longValue :23"]
  m72["ElasticsearchChunkIndexService.indexExists :402"]
  m73["ElasticsearchChunkIndexService.createIndex :432"]

  m1 --> m2
  m2 --> m3
  m2 --> m4
  m2 --> m5
  m2 --> m6
  m3 --> m7
  m3 --> m8
  m4 --> m9
  m4 --> m10
  m4 --> m11
  m4 --> m12
  m4 --> m13
  m4 --> m14
  m4 --> m15
  m4 --> m16
  m5 --> m17
  m10 --> m18
  m12 --> m19
  m13 --> m20
  m13 --> m21
  m13 --> m22
  m13 --> m23
  m13 --> m24
  m13 --> m25
  m15 --> m26
  m16 --> m27
  m17 --> m28
  m18 --> m29
  m18 --> m30
  m18 --> m31
  m18 --> m32
  m18 --> m33
  m18 --> m34
  m18 --> m35
  m20 --> m36
  m24 --> m37
  m24 --> m38
  m24 --> m39
  m24 --> m40
  m26 --> m41
  m26 --> m42
  m26 --> m43
  m26 --> m44
  m26 --> m45
  m28 --> m46
  m29 --> m47
  m30 --> m48
  m31 --> m49
  m31 --> m50
  m31 --> m51
  m33 --> m52
  m34 --> m53
  m35 --> m54
  m36 --> m55
  m37 --> m56
  m38 --> m57
  m41 --> m58
  m46 --> m59
  m46 --> m60
  m46 --> m61
  m46 --> m62
  m46 --> m63
  m47 --> m64
  m50 --> m65
  m50 --> m66
  m50 --> m67
  m53 --> m68
  m53 --> m69
  m57 --> m70
  m60 --> m71
  m68 --> m72
  m68 --> m73
```
