# qa · QaController.streamAsk 调用链

> 由 `docs/callgraph/gen_all.py` 静态分析自动生成（纯说明，不改任何代码）。
> 每个节点 = 一个被调用的方法（`方法名:文件内行号`）；箭头 = 调用方向；getter/简单投影已省略。

```mermaid
flowchart TD
  m1["QaController.streamAsk :87"]
  m2["UserContext.get :22"]
  m3["UserContext.set :17"]
  m4["QaService.askStream :150"]
  m5["GlobalExceptionHandler.userFacingMessage :143"]
  m6["UserContext.clear :27"]
  m7["GroupMembershipService.requireGroupReadable :66"]
  m8["QaChatService.askStream :353"]
  m9["QaService.recordUsage :220"]
  m10["AskQuestionResponse.answered :50"]
  m11["QaService.saveQaRecord :117"]
  m12["AskQuestionResponse.unanswered :66"]
  m13["CurrentUserService.requireBusinessUser :66"]
  m14["GroupMembershipService.requireGroupId :113"]
  m15["AskQuestionResponse.AskQuestionResponse :39"]
  m16["QaRecordPersistenceService.saveCompleted :51"]
  m17["QaRecordPersistenceService.toCitationEntities :101"]
  m18["QaRecordPersistenceService.toCitationEntity :116"]
  m19["QaRecordPersistenceService.readText :163"]
  m20["QaRecordPersistenceService.readLong :148"]
  m21["QaRecordPersistenceService.readInteger :153"]
  m22["QaRecordPersistenceService.readDouble :158"]
  m23["QaRecordPersistenceService.snippet :169"]
  m24["MetadataReaders.longValue :23"]
  m25["MetadataReaders.intValue :29"]
  m26["MetadataReaders.doubleValue :35"]

  m1 --> m2
  m1 --> m3
  m1 --> m4
  m1 --> m5
  m1 --> m6
  m4 --> m7
  m4 --> m8
  m4 --> m9
  m4 --> m10
  m4 --> m11
  m4 --> m12
  m7 --> m13
  m7 --> m14
  m10 --> m15
  m11 --> m16
  m16 --> m17
  m17 --> m18
  m18 --> m19
  m18 --> m20
  m18 --> m21
  m18 --> m22
  m18 --> m23
  m20 --> m24
  m21 --> m25
  m22 --> m26
```
