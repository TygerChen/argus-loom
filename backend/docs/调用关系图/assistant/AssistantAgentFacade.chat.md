# assistant · AssistantAgentFacade.chat 调用链

> 由 `docs/callgraph/gen_all.py` 静态分析自动生成（纯说明，不改任何代码）。
> 每个节点 = 一个被调用的方法（`方法名:文件内行号`）；箭头 = 调用方向；getter/简单投影已省略。

```mermaid
flowchart TD
  m1["AssistantAgentFacade.chat :114"]
  m2["LlmDialogueMetrics.start :33"]
  m3["AssistantPromptContextBuilder.buildChatInstruction :60"]
  m4["AssistantRunnableConfigFactory.create :36"]
  m5["AssistantReactAgentFactory.createAgent :67"]
  m6["AssistantAgentFacade.callWithTimeout :343"]
  m7["AssistantAgentFacade.abbreviate :383"]
  m8["LlmDialogueMetrics.finish :45"]
  m9["PromptTemplateManager.render :91"]
  m10["AssistantPromptContextBuilder.fallbackBasePrompt :113"]
  m11["LlmDialogueMetrics.timer :54"]

  m1 --> m2
  m1 --> m3
  m1 --> m4
  m1 --> m5
  m1 --> m6
  m1 --> m7
  m1 --> m8
  m3 --> m9
  m3 --> m10
  m8 --> m11
```
