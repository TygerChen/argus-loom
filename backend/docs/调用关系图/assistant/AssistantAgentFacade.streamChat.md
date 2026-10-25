# assistant · AssistantAgentFacade.streamChat 调用链

> 由 `docs/callgraph/gen_all.py` 静态分析自动生成（纯说明，不改任何代码）。
> 每个节点 = 一个被调用的方法（`方法名:文件内行号`）；箭头 = 调用方向；getter/简单投影已省略。

```mermaid
flowchart TD
  m1["AssistantAgentFacade.streamChat :218"]
  m2["LlmDialogueMetrics.start :33"]
  m3["AssistantPromptContextBuilder.buildChatInstruction :60"]
  m4["AssistantRunnableConfigFactory.create :36"]
  m5["ToolEventEmitter.register :22"]
  m6["AssistantReactAgentFactory.createAgent :67"]
  m7["AssistantAgentFacade.handleStreamingOutput :289"]
  m8["ToolEventEmitter.clear :29"]
  m9["AssistantAgentFacade.abbreviate :383"]
  m10["LlmDialogueMetrics.finish :45"]
  m11["PromptTemplateManager.render :91"]
  m12["AssistantPromptContextBuilder.fallbackBasePrompt :113"]
  m13["AssistantAgentFacade.normalizeStreamingDelta :321"]
  m14["LlmDialogueMetrics.timer :54"]

  m1 --> m2
  m1 --> m3
  m1 --> m4
  m1 --> m5
  m1 --> m6
  m1 --> m7
  m1 --> m8
  m1 --> m9
  m1 --> m10
  m3 --> m11
  m3 --> m12
  m7 --> m13
  m10 --> m14
```
