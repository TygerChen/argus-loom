# 离线评测集说明

方案（任务 5）要求在 `scripts/rag_eval/fixtures/` 放置 50-100 条脱敏问答，
覆盖：制度、项目、财务角色隔离、无答案、同名文档、多版本文档。

## 现状

正式评测集在 `../testset.json`（40 条，`prepare_testset.py` 生成），配合
`../eval_docs/` 三个测试文档与 `../corpus/` 全量语料使用，运行方式：

```powershell
python scripts/rag_eval/run_eval.py --profile local
```

## 与方案覆盖项的对应关系

| 方案要求 | 覆盖方式 |
| --- | --- |
| 制度类问答 | 全部 36 条正例均为制度问答 |
| 无答案 / 拒答 | 用例 37-40（`should_answer: false`），后端返回 `RETRIEVAL_NOT_GROUNDED` |
| 同名 / 多版本文档 | 语料 `../corpus/` 中每份制度都带「-2025存档」版本，问题命中时验证引用指向最新版 |
| 财务角色隔离 | 代码级测试覆盖（`DocumentRoleGateTest` / `HybridChunkRetrievalRoleIsolationTest`）；在线评测群组为普通权限，财务隔离不适用 |
| 项目 / 财务场景 | 待财务导入（任务 10-11）落地后，在 `fixtures/` 追加财务专属用例 |

## 扩展方法

1. 在 `../eval_docs/` 增加事实性文档，在 `../corpus/` 增加语料；
2. 修改 `../prepare_testset.py` 的用例表（含 `expected_files`、`expected_keywords`、`should_answer`）；
3. 重新生成 `testset.json` 后运行上面的评测命令。

每条用例可按方案要求补充 `forbidden_files`（禁止命中的文档）与 `min_score`（最低证据分），
当前脚本未消费这两个字段，属于后续增强项。
