# assistant · AssistantChatController.streamChat 调用链

> 由 `docs/callgraph/gen_all.py` 静态分析自动生成（纯说明，不改任何代码）。
> 每个节点 = 一个被调用的方法（`方法名:文件内行号`）；箭头 = 调用方向；getter/简单投影已省略。

```mermaid
flowchart TD
  m1["AssistantChatController.streamChat :83"]
  m2["UserContext.get :22"]
  m3["UserContext.set :17"]
  m4["AssistantService.streamChat :140"]
  m5["AssistantChatStreamEvent.error :168"]
  m6["GlobalExceptionHandler.userFacingMessage :143"]
  m7["UserContext.clear :27"]
  m8["CurrentUserService.requireBusinessUser :66"]
  m9["AssistantService.requireChatRequest :240"]
  m10["AssistantService.resolveKnowledgeScope :259"]
  m11["AssistantAgentFacade.streamChat :218"]
  m12["AssistantService.requireKnowledgeBaseReadableIfNeeded :348"]
  m13["KnowledgeGroupRouter.route :60"]
  m14["LlmDialogueMetrics.start :33"]
  m15["AssistantPromptContextBuilder.buildChatInstruction :60"]
  m16["AssistantRunnableConfigFactory.create :36"]
  m17["ToolEventEmitter.register :22"]
  m18["AssistantReactAgentFactory.createAgent :67"]
  m19["AssistantAgentFacade.handleStreamingOutput :289"]
  m20["ToolEventEmitter.clear :29"]
  m21["AssistantAgentFacade.abbreviate :383"]
  m22["LlmDialogueMetrics.finish :45"]
  m23["GroupMembershipService.requireGroupReadable :66"]
  m24["GroupMembershipService.listVisibleGroupsForUser :52"]
  m25["KnowledgeGroupRouter.filterCandidates :112"]
  m26["KnowledgeGroupRouter.tokenize :178"]
  m27["KnowledgeGroupRouter.documentSample :130"]
  m28["KnowledgeGroupRouter.normalizedTokenHits :164"]
  m29["KnowledgeGroupRouter.safeText :214"]
  m30["PromptTemplateManager.render :91"]
  m31["AssistantPromptContextBuilder.fallbackBasePrompt :113"]
  m32["AssistantAgentFacade.normalizeStreamingDelta :321"]
  m33["LlmDialogueMetrics.timer :54"]
  m34["GroupMembershipService.requireGroupId :113"]
  m35["GroupMembershipService.requireUserId :120"]
  m36["GroupMembershipService.toVisibleGroups :131"]
  m37["KnowledgeGroupRouter.normalize :203"]

  m1 --> m2
  m1 --> m3
  m1 --> m4
  m1 --> m5
  m1 --> m6
  m1 --> m7
  m4 --> m8
  m4 --> m9
  m4 --> m10
  m4 --> m11
  m10 --> m12
  m10 --> m13
  m11 --> m14
  m11 --> m15
  m11 --> m16
  m11 --> m17
  m11 --> m18
  m11 --> m19
  m11 --> m20
  m11 --> m21
  m11 --> m22
  m12 --> m23
  m13 --> m24
  m13 --> m25
  m13 --> m26
  m13 --> m27
  m13 --> m28
  m13 --> m29
  m15 --> m30
  m15 --> m31
  m19 --> m32
  m22 --> m33
  m23 --> m34
  m24 --> m35
  m24 --> m36
  m26 --> m37
```
