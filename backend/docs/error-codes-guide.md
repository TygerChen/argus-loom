# Argus-Loom · 稳定业务错误码 · 前端使用手册

本文档为前端提供**结构化的业务错误码全集**与建议行为。后端统一响应形如：

```json
{ "success": false, "message": "模型服务暂时不可用，请稍后重试。", "code": "MODEL_UNAVAILABLE" }
```

- `code` **由后端稳定输出**，前端应**以码为准做行为分流**，仅用 `message` 做兜底展示（文案可能随语言调整）。
- 当 `code` 为 `null` 时，通常是**入参校验类错误**（如「密码长度非法」「文档ID非法」）：前端在发请求前已做过同等校验，按一般错误提示即可，无需专门分支。
- 顶层常量定义见 [`common/exception/ErrorCodes.java`](../src/main/java/com/argus/rag/common/exception/ErrorCodes.java)，与本文档保持同一来源。

> 本文档所列全部码均接入 `GlobalExceptionHandler → ApiResponse.code` 契约测试（`GlobalExceptionHandlerTest`,9 例），改动会自动回归，请勿手写绕过。

---

## ⛔ 第 0 条 · 财务数据隔离（红线，最高优先级）

涉及财务的 Excel **只允许领导和财务访问**（领导权限最高、财务次之），普通用户与各系统管理员一律不可触及，**无权限即视同「查无此文档」**。后端在数据出口处 fail-closed；前端在此基础上遵守：

| 码 | 后端触发场景 | 前端必须 |
|---|---|---|
| `EXCEL_FINANCE_FORBIDDEN` | 财务文件不允许生成分享链接 / 无权导出财务文件 | **强阻断**：隐藏该功能入口、不做降级、不尝试绕过，展示「无权操作财务数据」；绝不允许拼回执绕过 |

**禁止**任何宏/插件/分享链接产物绕过以上码的控。其余任何码都不代表对财务数据的授权。

---

## 二、账号 / 身份域（auth、user）

| 码 | 触发场景 | 前端建议动作 |
|---|---|---|
| `AUTH_TOKEN_INVALID` | access/refresh token 非法或已过期 | 静默刷新 token；刷新失败则跳登录页 |
| `AUTH_TOKEN_REPLAYED` | refresh token 被重放使用（疑似复用/攻击） | **强制重新登录**，可提示「会话已被异地使用，请重新登录」 |
| `AUTH_USER_NOT_FOUND` | 当前用户/用户会话主体缺失 | 按会话失效处理 → 跳登录 |
| `AUTH_CREDENTIALS_INVALID` | 用户名或密码错误 | 登录表单提示「账号或密码错误」，**不区分**账号是否存在（防枚举） |
| `AUTH_IDENTITY_EXISTS` | 注册时用户名/邮箱已被占用 | 注册表单定位到对应输入框提示「已被占用」，请用户换一个 |
| `USER_NOT_FOUND` | 管理/查询/改密的目标用户不存在 | 提示「用户不存在/已删除」，刷新用户列表 |
| `USER_PASSWORD_MISMATCH` | 修改密码时当前密码不正确 | 定位「当前密码」框提示 |
| `USER_PASSWORD_SAME_AS_OLD` | 新密码与当前相同 | 提示换一个新密码 |
| `USER_ROLE_ALREADY_SET` | 待设角色与当前角色一致 | 提示「已是该角色」，无需重复操作 |

## 三、组织 / 知识库成员域（Group）

| 码 | 触发场景 | 前端建议动作 |
|---|---|---|
| `GROUP_NOT_FOUND` | 成员/邀请/申请/被邀用户/组织不存在 | 提示目标已被移除，刷新成员/邀请面板 |
| `GROUP_ALREADY_HANDLED` | 申请/邀请已被处理过 | **刷新列表**，不要再重复点「同意/拒绝/处理」 |
| `GROUP_REQUEST_PENDING` | 已存在待处理的申请/邀请 | 提示「已有待处理邀请/申请，请先处理」，跳转到对应 tab |
| `GROUP_ACTION_FORBIDDEN` | 不能移除 OWNER / 非本组成员 / 已入群 / 无权处理邀请 | 提示无权限或操作不合法，可置灰相关操作按钮 |
| `GROUP_INVALID_TARGET` | groupId / 被邀请用户参数非法 | 按普通入参错误提示 |

---

## 四、Excel 协作域（Excel）

| 码 | 触发场景 | 前端建议动作 |
|---|---|---|
| `EXCEL_NOT_FOUND` | 文档/版本/导出任务不存在 | 提示已不存在/刷新，必要时回到列表 |
| `EXCEL_TRASH_STATE` | 回收站流转冲突（重复移入、对非回收站文档做回收站操作等） | 同步回收站状态，刷新列表或提示「该文档已被其他操作移动」 |
| `EXCEL_SHARE_INVALID` | 分享链接无效/过期/不存在/角色配置非法 | 若打开过期分享链接：提示链接失效并回首页；若创建：定位角色/链接配置 |
| `EXCEL_EXPORT_STATE` | 导出任务已完结无法取消 / 未完成无法下载 / 文件已清理 | 同步任务状态（含重新发起/已取消），提示「文件已清理，请重新导出」 |
| `EXCEL_PARSE_ERROR` | Sheet/文件解析失败（空 sheet、无有效 sheet、读取失败） | 提示「文件无法解析，请重新上传/更换文件」 |
| `PERMISSION_DENIED` | 无权访问某分享/导出任务/协作对象 | 提示无权限；不展示内容详情 |
| `CONCURRENT_MODIFIED` | 资源被其他操作并发修改（乐观锁冲突） | 提示「已被他人修改，请刷新后重试」，并刷新数据 |

---

## 五、财务域（Finance）

| 码 | 触发场景 | 前端建议动作 |
|---|---|---|
| `FINANCE_DATA_EMPTY` | 薪酬/考勤等导出数据为空/缺失 | 提示「数据未完整或未录入」，协助到对应录入页 |
| `FINANCE_PAYROLL_NOT_AVAILABLE` | 目标月薪酬尚未发布或不存在 | 提示「该月薪酬尚未发布」，可展示发布时间 |
| `FINANCE_REPORT_NOT_FOUND` | 财务简报记录不存在 | 刷新简报列表 |
| `FINANCE_PDF_NOT_FOUND` | PDF 附件不存在 | 提示重新生成 PDF |
| `FINANCE_PDF_FILE_FAILED` | PDF 生成链路失败（建目录/渲染/写盘/读字节） | 提示稍后重试或联系管理员 |
| `FINANCE_AI_REPORT_FAILED` | AI 简报生成失败（模型侧异常） | 提示稍后重试；可提供降级（如仅展示数据表格） |
| `FINANCE_MONTH_FORMAT_INVALID` | 月份入参非法（应 YYYY-MM） | 表单日期控件约束，提示正确格式 |

---

## 六、知识库文档域（Document / Upload）

| 码 | 触发场景 | 前端建议动作 |
|---|---|---|
| `DOCUMENT_NOT_FOUND` | 文档不存在或已删除 | 提示刷新列表，刷新 |
| `DOCUMENT_NOT_READY` | 文档处理中 / 存储信息缺失，尚不可预览/下载 | 提示「文档处理中，稍后再试」，可做轮询重试 |
| `DOCUMENT_PROCESSING_FAILED` | 解析失败/取存储失败/重处理非法/预览写入失败 | 提示「文档解析/处理失败」，允许重新上传/重新处理 |
| `UPLOAD_SESSION_INVALID` | 上传会话不存在/过期/非本人/已结束 | 重新发起上传（重新 init 会话） |
| `UPLOAD_INCOMPLETE` | 分片缺失/校验失败/读取失败 | 断点续传或重传对应分片，提示「传输未完成，请重试」 |

---

## 七、模型侧（对话 / QA / 简报）

| 码 | 触发场景 | 前端建议动作 |
|---|---|---|
| `MODEL_UNAVAILABLE` | 模型调用失败/超时/中断 | 展示「模型服务暂时不可用，请稍后重试」(对应 `MODEL_UNAVAILABLE_MESSAGE`)，提供重试按钮；不要重放自动化 |

---

## 附 · 前端统一约定（建议）

1. 前端建一层 `ApiError` 拦截器：统一读取 `body.code`，命中上表做行为，未命中回落 `message` 兜底提示。
2. 对 `AUTH_TOKEN_INVALID / AUTH_USER_NOT_FOUND / AUTH_TOKEN_REPLAYED`：前端可统一走「会话失效」流程（重登）。
3. 对 `*_NOT_FOUND / *_ALREADY_HANDLED / *_TRASH_STATE / CONCURRENT_MODIFIED`：优先**刷新本地数据**而非报错弹窗。
4. 码值一律以**后端 `ErrorCodes` 为准**，前端勿硬编码重复字符串——可由后端 `/api/meta/error-codes` 接口下发给前端（如后外不对外，可联动网关同步）。