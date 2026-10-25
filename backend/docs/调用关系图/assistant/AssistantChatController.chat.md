# assistant · AssistantChatController.chat 调用链

> 由 `docs/callgraph/gen_all.py` 静态分析自动生成（纯说明，不改任何代码）。
> 每个节点 = 一个被调用的方法（`方法名:文件内行号`）；箭头 = 调用方向；getter/简单投影已省略。

```mermaid
flowchart TD
  m1["AssistantChatController.chat :65"]
  m2["ApiResponse.success :24"]
  m3["AssistantService.chat :79"]
  m4["CurrentUserService.requireBusinessUser :66"]
  m5["AssistantService.requireChatRequest :240"]
  m6["AssistantService.resolveKnowledgeScope :259"]
  m7["AssistantService.saveUserMessage :310"]
  m8["AssistantService.executeAssistant :284"]
  m9["AssistantService.saveAssistantMessage :330"]
  m10["AssistantService.firstGroupId :357"]
  m11["AssistantService.recordUsage :381"]
  m12["AssistantService.requireKnowledgeBaseReadableIfNeeded :348"]
  m13["KnowledgeGroupRouter.route :60"]
  m14["AssistantConversationService.saveUserMessage :85"]
  m15["AssistantAgentFacade.chat :114"]
  m16["AssistantConversationService.saveAssistantMessage :99"]
  m17["AssistantService.estimateTokens :364"]
  m18["GroupMembershipService.requireGroupReadable :66"]
  m19["GroupMembershipService.listVisibleGroupsForUser :52"]
  m20["KnowledgeGroupRouter.filterCandidates :112"]
  m21["KnowledgeGroupRouter.tokenize :178"]
  m22["KnowledgeGroupRouter.documentSample :130"]
  m23["KnowledgeGroupRouter.normalizedTokenHits :164"]
  m24["KnowledgeGroupRouter.safeText :214"]
  m25["AssistantConversationService.saveMessage :242"]
  m26["LlmDialogueMetrics.start :33"]
  m27["AssistantPromptContextBuilder.buildChatInstruction :60"]
  m28["AssistantRunnableConfigFactory.create :36"]
  m29["AssistantReactAgentFactory.createAgent :67"]
  m30["AssistantAgentFacade.callWithTimeout :343"]
  m31["AssistantAgentFacade.abbreviate :383"]
  m32["LlmDialogueMetrics.finish :45"]
  m33["GroupMembershipService.requireGroupId :113"]
  m34["GroupMembershipService.requireUserId :120"]
  m35["GroupMembershipService.toVisibleGroups :131"]
  m36["KnowledgeGroupRouter.normalize :203"]
  m37["AssistantConversationService.requireUserId :325"]
  m38["AssistantConversationService.requireCreateDTO :297"]
  m39["AssistantConversationService.requireOwnedSession :289"]
  m40["AssistantConversationService.requireSessionId :332"]
  m41["AssistantConversationService.buildMessageEntity :276"]
  m42["AssistantSessionService.autoRenameSessionIfNeeded :170"]
  m43["AssistantConversationService.maintainShortTermMemory :467"]
  m44["AssistantConversationService.publishMemoryExtractionEvent :311"]
  m45["AssistantConversationService.toMessageVO :488"]
  m46["PromptTemplateManager.render :91"]
  m47["AssistantPromptContextBuilder.fallbackBasePrompt :113"]
  m48["LlmDialogueMetrics.timer :54"]
  m49["AssistantConversationService.resolveGroupIds :371"]
  m50["AssistantConversationService.normalizeGroupId :361"]
  m51["AssistantConversationService.firstGroupId :386"]
  m52["AssistantConversationService.requireContent :339"]
  m53["AssistantConversationService.enrichStructuredPayload :391"]
  m54["AssistantSessionService.requireOwnedSession :190"]
  m55["AssistantSessionService.generateSessionTitle :220"]
  m56["AssistantShortTermMemoryMaintenanceService.maintainBeforeResponse :82"]
  m57["AssistantShortTermMemoryMaintenanceService.maintainAfterResponse :100"]
  m58["AssistantConversationService.readStoredGroupIds :413"]
  m59["AssistantConversationService.readStoredRouteStrategy :434"]
  m60["AssistantShortTermMemoryMaintenanceService.maintain :155"]
  m61["AssistantShortTermMemoryMaintenanceService.shouldMaintainSessionMemory :113"]
  m62["AssistantMemorySummarizer.summarizeSessionMemory :60"]
  m63["AssistantShortTermMemoryMaintenanceService.estimateTokens :143"]
  m64["AssistantShortTermMemoryMaintenanceService.shouldCompactSession :131"]
  m65["AssistantMemorySummarizer.summarizeCompactSummary :82"]
  m66["AssistantShortTermMemoryMaintenanceService.collectMessagesToCompact :211"]
  m67["AssistantMemorySummarizer.callForText :146"]
  m68["AssistantMemorySummarizer.defaultText :171"]
  m69["AssistantMemorySummarizer.normalize :164"]

  m1 --> m2
  m1 --> m3
  m3 --> m4
  m3 --> m5
  m3 --> m6
  m3 --> m7
  m3 --> m8
  m3 --> m9
  m3 --> m10
  m3 --> m11
  m6 --> m12
  m6 --> m13
  m7 --> m14
  m8 --> m15
  m9 --> m16
  m11 --> m17
  m12 --> m18
  m13 --> m19
  m13 --> m20
  m13 --> m21
  m13 --> m22
  m13 --> m23
  m13 --> m24
  m14 --> m25
  m15 --> m26
  m15 --> m27
  m15 --> m28
  m15 --> m29
  m15 --> m30
  m15 --> m31
  m15 --> m32
  m18 --> m33
  m19 --> m34
  m19 --> m35
  m21 --> m36
  m25 --> m37
  m25 --> m38
  m25 --> m39
  m25 --> m40
  m25 --> m41
  m25 --> m42
  m25 --> m43
  m25 --> m44
  m25 --> m45
  m27 --> m46
  m27 --> m47
  m32 --> m48
  m41 --> m49
  m41 --> m50
  m41 --> m51
  m41 --> m52
  m41 --> m53
  m42 --> m54
  m42 --> m55
  m43 --> m56
  m43 --> m57
  m45 --> m58
  m45 --> m59
  m56 --> m60
  m60 --> m61
  m60 --> m62
  m60 --> m63
  m60 --> m64
  m60 --> m65
  m60 --> m66
  m62 --> m67
  m62 --> m68
  m67 --> m69
```
