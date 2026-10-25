# 企业 RAG 与 FastGPT 集成改造计划

> **面向 AI 代理的工作者：** 按任务顺序实施；每个任务先写失败测试，再完成最小实现并运行指定验证。任务完成后再开始下一任务。

**目标：** 将 Argus Loom 演进为面向企业财务与经营协作的可信 RAG 应用：Argus Loom 负责业务数据、权限、工作流和审计，FastGPT 负责通用知识库、检索、模型调用和对话工作流。

**架构：** 保留现有 Spring Boot 3 / Java 21 与 Vue 3 前端，先修复现有资源越权和注入问题。随后建立工作空间、统一资源和同步 Outbox；FastGPT 作为独立部署的 RAG 服务，只通过后端网关访问。用户始终从 Argus Loom 进入，权限判断、数据脱敏和审计留在 Argus Loom。

**技术栈：** Spring Boot 3.5、Java 21、MyBatis-Plus、PostgreSQL、Flyway、Vue 3、TypeScript、Vitest、FastGPT Community Edition、Docker Compose、Redis、对象存储、pgvector。

**规格：** 本文是当前仓库的实施规格。执行者应同时阅读 `backend/docs/权限红线速记-新手向.md`、`backend/docs/核心链路-陪读-RAG与AI助手.md`、`docs/MEMORY-知识库问答.md`。

## 全局约束

- 所有新建或修改的业务接口必须通过 `CurrentUserService` 和资源级权限服务，不允许仅依赖 Spring Security 的“已登录”状态。
- 财务数据只能由 `FINANCE` 角色访问；`ADMIN` 不能自动获得财务数据权限。
- 对外暴露的资源标识使用 UUID；内部数据库自增 ID 只能用于服务内部关联。
- 前端不得把未经净化的富文本写入 `v-html`。
- 后端不得根据客户端传入的 `sheetId`、`versionId`、`taskId`、`userId` 或协作时间戳直接执行授权或写入。
- FastGPT API Key 只保存在后端环境变量或密钥管理系统；浏览器不得持有该 Key。
- 每项数据库变更使用新的 Flyway SQL 文件，禁止修改已经执行过的 `V1` 至 `V4`。
- 每项功能至少包含权限反向测试、正常流程测试和失败恢复测试。
- 生产部署使用 HTTPS、外部对象存储、独立数据库备份和集中日志；开发环境默认凭据不得进入生产配置。

## 审查重点（Review Focus）

- 用户有文档 A 的权限却传入文档 B 的 sheet、version、导出任务或审计记录 ID 时，系统必须返回 403，且不能泄露存在性。
- 财务工作簿、财务导出、财务向量知识库和财务引用必须对普通用户及仅有 ADMIN 角色的用户不可见。
- 文档 Markdown、模型回答、网页抓取结果包含 HTML、危险 URL 或脚本载荷时，浏览器只能呈现安全文本。
- FastGPT 同步任务重复投递、重试、删除和权限收回时，远端知识库必须最终与本地资源状态一致。
- 模型、向量库、对象存储、FastGPT 任一依赖不可用时，用户得到可读错误，任务可重试，且不会产生越权回答或半完成状态。

---

## 产品范围与边界

### 首期用户与三个真实场景

| 场景 | 目标用户 | 输入 | 可交付结果 |
| --- | --- | --- | --- |
| 制度知识助手 | 全体员工、HR、财务 | 制度、流程、操作手册 | 带原文引用、版本和生效日期的回答 |
| 月度经营分析 | 财务、经营分析师、管理层 | 已审核的财务工作簿与汇总数据 | 指标解释、异常科目、关联流水、待处理事项 |
| 财务协作闭环 | 财务负责人、业务负责人 | 导入批次、异常项、审批意见 | 指派、审批、关账与不可篡改审计记录 |

### 首期不纳入核心产品的能力

- 通用媒体生成接口不进入生产版本；现有 `/api/generate/image` 与 `/api/generate/video` 在首期下线或仅在隔离开发环境保留。
- 联网搜索不参与敏感数据回答；首期仅允许管理员为公开情报场景单独启用。
- 不在浏览器直接访问 FastGPT 管理 API、知识库 API 或向量数据库。

### 目标边界

```text
浏览器
  -> Argus Loom 前端
  -> Argus Loom 后端：身份、工作空间、业务权限、审计、同步、业务数据 API
  -> FastGPT：知识库解析、检索、模型调用、通用工作流
  -> PostgreSQL / Redis / 对象存储 / FastGPT 依赖
```

FastGPT 不是系统权限的唯一来源。Argus Loom 必须在发起检索、读取引用、打开原文、同步文档和展示工作簿数据前完成本地授权。

### FastGPT 基线部署决策

- 上游项目固定为 [labring/FastGPT](https://github.com/labring/FastGPT)，部署方式以其 [Docker Compose 官方文档](https://github.com/labring/FastGPT/blob/main/document/content/self-host/deploy/docker.mdx) 为准。
- 首次部署必须固定一个已验证的 Git tag 或镜像 tag，并在 `deploy/fastgpt/.env.production.example` 记录 `FASTGPT_VERSION`；禁止生产环境跟随 `latest`。
- FastGPT、PostgreSQL、Redis、对象存储和向量库只加入私有容器网络；公网只暴露 Argus Loom 与反向代理的 HTTPS 入口。
- 上线前由项目负责人核对 FastGPT 当前许可证、品牌条款和目标使用模式；许可证结论写入 `deploy/fastgpt/README.md`。
- 在隔离测试工作空间完成文档上传、删除、引用、API 鉴权和升级回滚演练后，才允许绑定生产工作空间。

## 数据模型决策

### 工作空间

新增工作空间，作为企业、部门或独立客户的顶层隔离边界。

```text
workspaces
  id BIGSERIAL PK
  resource_uuid UUID UNIQUE NOT NULL
  code VARCHAR(64) UNIQUE NOT NULL
  name VARCHAR(128) NOT NULL
  status VARCHAR(16) NOT NULL
  created_by BIGINT NOT NULL
  created_at / updated_at

workspace_members
  workspace_id BIGINT NOT NULL
  user_id BIGINT NOT NULL
  role VARCHAR(32) NOT NULL
  status VARCHAR(16) NOT NULL
  PRIMARY KEY (workspace_id, user_id)
```

角色固定为：`OWNER`、`FINANCE_ADMIN`、`ANALYST`、`EDITOR`、`VIEWER`、`AUDITOR`。`SystemRole` 保留为全局系统治理角色，不能替代工作空间权限。

### 统一资源

保留 `documents` 与 `excel_document` 的现有领域表，但新增统一资源层，消除两个自增 ID 命名空间混用问题。

```text
business_resources
  id BIGSERIAL PK
  resource_uuid UUID UNIQUE NOT NULL
  workspace_id BIGINT NOT NULL
  resource_type VARCHAR(32) NOT NULL
  owner_user_id BIGINT NOT NULL
  sensitivity VARCHAR(16) NOT NULL
  status VARCHAR(16) NOT NULL
  source_domain_id BIGINT NOT NULL
  created_at / updated_at
  UNIQUE (resource_type, source_domain_id)

resource_acl
  resource_id BIGINT NOT NULL
  subject_type VARCHAR(16) NOT NULL
  subject_id BIGINT NOT NULL
  permission VARCHAR(16) NOT NULL
  granted_by BIGINT NOT NULL
  PRIMARY KEY (resource_id, subject_type, subject_id)
```

`resource_type` 取值：`KNOWLEDGE_DOCUMENT`、`WORKBOOK`、`DATASET`、`REPORT`。`sensitivity` 取值：`NORMAL`、`INTERNAL`、`FINANCE`、`CONFIDENTIAL`。

### FastGPT 映射与同步

```text
fastgpt_workspace_bindings
  workspace_id BIGINT PK
  fastgpt_team_id VARCHAR(128) NOT NULL
  default_dataset_id VARCHAR(128) NOT NULL
  status VARCHAR(16) NOT NULL

fastgpt_resource_bindings
  resource_id BIGINT PK
  fastgpt_dataset_id VARCHAR(128) NOT NULL
  fastgpt_collection_id VARCHAR(128)
  fastgpt_document_id VARCHAR(128)
  synced_content_hash CHAR(64)
  sync_status VARCHAR(16) NOT NULL
  last_synced_at TIMESTAMP
  last_error TEXT

rag_sync_outbox
  id BIGSERIAL PK
  event_uuid UUID UNIQUE NOT NULL
  workspace_id BIGINT NOT NULL
  resource_id BIGINT NOT NULL
  action VARCHAR(16) NOT NULL
  payload_json JSONB NOT NULL
  status VARCHAR(16) NOT NULL
  retry_count INTEGER NOT NULL DEFAULT 0
  next_retry_at TIMESTAMP
  locked_at TIMESTAMP
  processed_at TIMESTAMP
  last_error TEXT
```

`action` 取值：`UPSERT`、`DELETE`、`REVOKE`。Outbox 由同一数据库事务写入，异步工作器负责投递、退避重试和死信记录。

## 外部接口约定

### Argus Loom 对前端的新增接口

| 方法 | 路径 | 权限 | 行为 |
| --- | --- | --- | --- |
| POST | `/api/workspaces` | 系统业务用户 | 创建工作空间，创建者成为 OWNER |
| GET | `/api/workspaces` | 已登录 | 返回当前用户成员资格有效的工作空间 |
| POST | `/api/workspaces/{workspaceUuid}/members` | OWNER | 邀请或添加成员 |
| PUT | `/api/workspaces/{workspaceUuid}/members/{userId}` | OWNER | 更新工作空间角色 |
| POST | `/api/resources/{resourceUuid}/sync` | OWNER 或资源 MANAGE | 触发资源同步到 FastGPT |
| GET | `/api/resources/{resourceUuid}/sync-status` | 可查看资源 | 返回同步状态，不泄露远端密钥 |
| POST | `/api/rag/chat` | 工作空间成员 | 经过本地权限检查后代理 FastGPT 对话 |
| GET | `/api/rag/citations/{citationId}` | 可查看引用资源 | 返回本地可验证的引用和跳转信息 |
| POST | `/api/finance/import-jobs` | FINANCE_ADMIN | 上传、映射并启动财务导入任务 |
| POST | `/api/finance/periods/{periodUuid}/close` | FINANCE_ADMIN | 发起期间关账审批 |

### FastGPT 后端适配器接口

创建接口，隔离 FastGPT 版本差异；任何 Controller 不得直接调用 HTTP 客户端。

```java
public interface FastGptClient {
    FastGptDatasetRef ensureDataset(FastGptDatasetCommand command);
    FastGptDocumentRef upsertDocument(FastGptDocumentCommand command);
    void deleteDocument(FastGptDocumentRef document);
    FastGptChatResult chat(FastGptChatCommand command);
}
```

FastGPT 实际 REST 路径、认证 Header 与请求体必须按部署时固定的 FastGPT 版本官方 OpenAPI 实现。将版本写入 `FastGptProperties.apiVersion`，禁止在业务代码中散落 URL 字符串。

## 文件结构

### 新增后端目录

| 路径 | 职责 |
| --- | --- |
| `backend/src/main/java/com/argus/rag/workspace/` | 工作空间、成员和角色 |
| `backend/src/main/java/com/argus/rag/resource/` | 统一资源、资源 ACL、敏感级别 |
| `backend/src/main/java/com/argus/rag/integration/fastgpt/` | FastGPT 配置、HTTP 客户端、DTO、同步适配器 |
| `backend/src/main/java/com/argus/rag/sync/` | Outbox、重试、死信和同步任务 |
| `backend/src/main/java/com/argus/rag/finance/importjob/` | 导入模板、字段映射、校验错误、导入任务 |
| `backend/src/main/java/com/argus/rag/finance/period/` | 财务期间、关账、调整和审批 |

### 新增前端目录

| 路径 | 职责 |
| --- | --- |
| `frontend/src/api/workspace.ts` | 工作空间 API 类型与请求 |
| `frontend/src/api/rag.ts` | 受 Argus 网关保护的 RAG 对话和引用 API |
| `frontend/src/stores/workspace.ts` | 当前工作空间与成员角色 |
| `frontend/src/views/workspaces/` | 工作空间选择、成员管理、资源同步状态 |
| `frontend/src/views/finance/imports/` | 导入模板、字段映射、错误行和任务状态 |
| `frontend/src/views/finance/periods/` | 财务期间、异常项、审批和关账 |

## 发布包 A：安全与权限基线

### 任务 1：建立资源归属校验

**文件：**

- 修改：`backend/src/main/java/com/argus/rag/excel/service/ExcelPermissionService.java`
- 修改：`backend/src/main/java/com/argus/rag/excel/service/ExcelSheetService.java`
- 修改：`backend/src/main/java/com/argus/rag/excel/controller/ExcelDocumentController.java`
- 新建：`backend/src/test/java/com/argus/rag/excel/service/ExcelPermissionServiceOwnershipTest.java`
- 新建：`backend/src/test/java/com/argus/rag/excel/controller/ExcelDocumentAuthorizationIT.java`

- [ ] **步骤 1：编写失败测试**

覆盖 `requireViewDocumentAndSheet(documentId, sheetId)` 和 `requireEditDocumentAndSheet(documentId, sheetId)`：文档 A 的授权用户传入文档 B 的 Sheet 必须抛出 `ForbiddenException`；合法 Sheet 正常返回。

- [ ] **步骤 2：运行测试验证失败**

运行：`mvn -f backend/pom.xml -Dtest=ExcelPermissionServiceOwnershipTest,ExcelDocumentAuthorizationIT test`

预期：FAIL，现有实现只按 `sheetId` 加载数据。

- [ ] **步骤 3：实现资源归属守卫**

在 `ExcelPermissionService` 添加以下公开方法，并在内部先加载文档、检查 `financeRequired` 和 ACL，再验证 `ExcelSheet.documentId`：

```java
public ExcelSheet requireViewDocumentAndSheet(Long documentId, Long sheetId)
public ExcelSheet requireEditDocumentAndSheet(Long documentId, Long sheetId)
public ExcelDocument requireManageDocument(Long documentId)
```

`ExcelSheetService` 的读写方法改为接收已验证的 `ExcelSheet` 或 `documentId + sheetId`，禁止仅凭 `sheetId` 查询后读写。

- [ ] **步骤 4：替换控制器调用点**

`ExcelDocumentController` 的 sheet 全量读取、chunk 读取、单元格批量更新、修复、工作簿操作均调用新守卫。STOMP 协作入口也复用同一守卫。

- [ ] **步骤 5：运行验证**

运行：`mvn -f backend/pom.xml -Dtest=ExcelPermissionServiceOwnershipTest,ExcelDocumentAuthorizationIT test`

预期：PASS。

- [ ] **步骤 6：Commit**

```text
fix(excel): enforce document-sheet ownership
```

### 任务 2：收紧版本、导出、审计、回收站与结构化查询

**文件：**

- 修改：`backend/src/main/java/com/argus/rag/excel/controller/ExcelVersionController.java`
- 修改：`backend/src/main/java/com/argus/rag/excel/service/ExcelVersionService.java`
- 修改：`backend/src/main/java/com/argus/rag/excel/service/ExcelExportService.java`
- 修改：`backend/src/main/java/com/argus/rag/excel/audit/OperationLogController.java`
- 修改：`backend/src/main/java/com/argus/rag/excel/controller/ExcelRecycleBinController.java`
- 修改：`backend/src/main/java/com/argus/rag/excel/controller/StructuredDataController.java`
- 新建：`backend/src/test/java/com/argus/rag/excel/ExcelSensitiveEndpointAuthorizationIT.java`

- [ ] **步骤 1：编写失败测试**

针对不同所有者的两份工作簿，测试版本预览、版本恢复、导出创建、审计日志、撤销、回收站删除与恢复、Sheet Schema、结构化查询。每个跨资源请求预期 403。

- [ ] **步骤 2：实现路径与服务层双重校验**

版本查询必须按 `(versionId, documentId)` 查询。导出创建与下载均验证当前用户对文档有 VIEW 权限。回收站批量操作先逐条加载并要求 MANAGE 权限。结构化查询必须调用 `requireViewDocumentAndSheet`。审计读取要求 VIEW，撤销与重做要求 EDIT。

- [ ] **步骤 3：修复财务角色绕过**

删除或改为私有化 `ExcelPermissionService` 中只按 `documentId` 判断管理员可编辑或管理的重载；所有入口使用包含当前用户与文档实体的安全方法。

- [ ] **步骤 4：运行验证**

运行：`mvn -f backend/pom.xml -Dtest=ExcelSensitiveEndpointAuthorizationIT test`

预期：PASS；财务文档对仅 ADMIN 用户全部返回 403。

- [ ] **步骤 5：Commit**

```text
fix(excel): protect related resource endpoints
```

### 任务 3：修复协作审计、QA 评分与资产访问

**文件：**

- 修改：`backend/src/main/java/com/argus/rag/excel/collab/CollabController.java`
- 修改：`backend/src/main/java/com/argus/rag/excel/audit/OperationLogService.java`
- 修改：`backend/src/main/java/com/argus/rag/qa/service/QaRecordPersistenceService.java`
- 修改：`backend/src/main/java/com/argus/rag/qa/controller/QaRecordController.java`
- 修改：`backend/src/main/java/com/argus/rag/excel/controller/ExcelAssetController.java`
- 修改：`backend/src/main/java/com/argus/rag/excel/service/ExcelAssetService.java`
- 新建：`backend/src/test/java/com/argus/rag/excel/CollaborationAuditIntegrityTest.java`
- 新建：`backend/src/test/java/com/argus/rag/qa/QaRecordRatingAuthorizationTest.java`

- [ ] **步骤 1：编写失败测试**

协作消息伪造 `userId`、`displayName` 和极大时间戳时，审计用户必须来自认证上下文，时间戳不能阻塞后续正常编辑。非记录创建者不能评分其他人的 QA 记录。资产读取和迁移不能跳过资源权限。

- [ ] **步骤 2：实现可信协作消息**

控制器将认证用户 ID、展示名和服务器接收时间写入不可由客户端覆盖的内部命令对象。`OperationLogService` 仅使用此对象；LWW 时间戳使用服务器单调序列或服务端时间加客户端序列，不接受任意 `long`。

- [ ] **步骤 3：实现记录与资产权限**

`updateRating(recordId, currentUser)` 只允许记录创建者更新一次评分。资产上传需 EDIT，读取先根据资产绑定的 documentId 做 VIEW 校验；废弃永久 `return true` 的签名校验。`migrate-legacy` 限为系统管理员的维护任务。

- [ ] **步骤 4：运行验证与 Commit**

运行：`mvn -f backend/pom.xml -Dtest=CollaborationAuditIntegrityTest,QaRecordRatingAuthorizationTest test`

提交：`fix(security): protect collaboration ratings and assets`

### 任务 4：消除 XSS、SSRF 与无配额生成接口

**文件：**

- 修改：`frontend/src/components/DocumentPreviewModal.vue`
- 修改：`frontend/src/views/qa/components/QaMessage.vue`
- 修改：`frontend/src/views/assistant/components/AssistantMessage.vue`
- 新建：`frontend/src/utils/safeMarkdown.ts`
- 新建：`frontend/src/utils/safeMarkdown.test.ts`
- 修改：`backend/src/main/java/com/argus/rag/engine/freeweb/FreeWebSearchService.java`
- 新建：`backend/src/main/java/com/argus/rag/engine/freeweb/SafeRemoteUrlPolicy.java`
- 新建：`backend/src/test/java/com/argus/rag/engine/freeweb/SafeRemoteUrlPolicyTest.java`
- 修改：`backend/src/main/java/com/argus/rag/config/GenerationController.java`

- [ ] **步骤 1：增加前端净化测试**

输入原始 HTML、事件属性、`javascript:` URL 和普通 Markdown。断言输出无脚本元素、无事件属性、无危险 URL，正常标题、列表、链接和代码块仍可渲染。

- [ ] **步骤 2：实现安全 Markdown 渲染**

添加 `dompurify`。`safeMarkdown.ts` 统一执行 `marked.parse()` 与 DOMPurify 净化，禁止原始 HTML，链接协议仅允许 `https`、`http`、`mailto`。三个现有组件只调用此工具函数。

- [ ] **步骤 3：增加 SSRF 策略测试并实现**

`SafeRemoteUrlPolicy.validate(URI)` 必须拒绝非 HTTP(S)、用户名混淆 URL、loopback、私网、链路本地、保留地址和 DNS 解析后的上述地址。禁用客户端自动重定向；每一次重定向最多 3 次并重新校验。单次响应上限 2 MB，总超时 10 秒。

- [ ] **步骤 4：收紧媒体生成入口**

生产路由删除 `GenerationController` 的媒体生成接口。若业务必须保留，改为 `ROLE_ADMIN`、固定模型/尺寸枚举、`n <= 1`、每用户每日额度与审计；首期默认关闭。

- [ ] **步骤 5：运行验证与 Commit**

运行：`npm --prefix frontend run test`，`mvn -f backend/pom.xml -Dtest=SafeRemoteUrlPolicyTest test`

提交：`fix(security): sanitize rendered content and outbound fetches`

## 发布包 B：工作空间与统一资源

### 任务 5：创建工作空间和成员角色

**文件：**

- 创建：`backend/src/main/resources/db/migration/V5__workspace_and_resource_schema.sql`
- 创建：`backend/src/main/java/com/argus/rag/workspace/entity/Workspace.java`
- 创建：`backend/src/main/java/com/argus/rag/workspace/entity/WorkspaceMember.java`
- 创建：`backend/src/main/java/com/argus/rag/workspace/service/WorkspaceService.java`
- 创建：`backend/src/main/java/com/argus/rag/workspace/controller/WorkspaceController.java`
- 创建：`frontend/src/api/workspace.ts`
- 创建：`frontend/src/stores/workspace.ts`
- 创建：`backend/src/test/java/com/argus/rag/workspace/WorkspaceServiceTest.java`

- [ ] **步骤 1：编写工作空间测试**

覆盖创建者自动成为 OWNER、OWNER 可邀请成员、非 OWNER 无法改角色、禁用成员无法进入工作空间、FINANCE_ADMIN 不自动拥有其他工作空间权限。

- [ ] **步骤 2：编写 V5 迁移**

创建 `workspaces`、`workspace_members`、`business_resources`、`resource_acl` 表、约束和索引。增加 `workspace_id`、`resource_id` 可空列到现有 `documents` 和 `excel_document`，用于渐进迁移。

- [ ] **步骤 3：实现 `WorkspaceService`**

公开方法：

```java
WorkspaceView create(CreateWorkspaceCommand command);
List<WorkspaceView> listVisible();
void addMember(UUID workspaceUuid, AddWorkspaceMemberCommand command);
void updateMemberRole(UUID workspaceUuid, Long userId, WorkspaceRole role);
CurrentWorkspaceMember requireMember(Long workspaceId);
CurrentWorkspaceMember requireRole(Long workspaceId, WorkspaceRole... roles);
```

- [ ] **步骤 4：增加工作空间前端入口**

在 `DefaultLayout.vue` 显示工作空间切换器；路由守卫要求业务页面存在有效 `workspaceUuid`。旧数据暂时进入默认迁移工作空间。

- [ ] **步骤 5：验证与 Commit**

运行：`mvn -f backend/pom.xml -Dtest=WorkspaceServiceTest test`，`npm --prefix frontend run type-check`

提交：`feat(workspace): add tenant membership and roles`

### 任务 6：迁移资源并统一 ACL

**文件：**

- 创建：`backend/src/main/resources/db/migration/V6__backfill_business_resources.sql`
- 创建：`backend/src/main/java/com/argus/rag/resource/service/BusinessResourceService.java`
- 创建：`backend/src/main/java/com/argus/rag/resource/service/ResourceAccessService.java`
- 修改：`backend/src/main/java/com/argus/rag/document/service/DocumentPreviewService.java`
- 修改：`backend/src/main/java/com/argus/rag/excel/service/ExcelPermissionService.java`
- 修改：`backend/src/main/java/com/argus/rag/assistant/agent/StructuredDataQueryTool.java`
- 新建：`backend/src/test/java/com/argus/rag/resource/ResourceAccessServiceTest.java`

- [ ] **步骤 1：编写跨域 ID 回归测试**

创建一条 RAG 文档和一条 Excel 文档，故意让内部数值 ID 相同。查询 Excel 时使用 RAG 资源 UUID 必须返回资源类型不匹配；无 ACL 时必须返回 403。

- [ ] **步骤 2：执行资源回填迁移**

为存量 `documents`、`excel_document` 生成 UUID 资源，建立 `business_resources` 行，填入默认工作空间、所有者、敏感等级。财务工作簿的 `sensitivity` 固定为 `FINANCE`。

- [ ] **步骤 3：实现资源权限服务**

公开方法：

```java
BusinessResource requireView(UUID resourceUuid);
BusinessResource requireEdit(UUID resourceUuid);
BusinessResource requireManage(UUID resourceUuid);
void assertType(BusinessResource resource, ResourceType expected);
```

实现顺序固定为：资源存在性、工作空间成员资格、敏感等级、资源 ACL、领域实体状态。禁止领域服务再自行拼接不一致的权限逻辑。

- [ ] **步骤 4：替换结构化数据工具授权来源**

`StructuredDataQueryTool` 改为接收 `workbookResourceUuid`，先通过 `ResourceAccessService.requireView` 和 `ResourceType.WORKBOOK`，再定位 `excel_document`。删除对 RAG `DocumentMapper` 的授权依赖。

- [ ] **步骤 5：验证与 Commit**

运行：`mvn -f backend/pom.xml -Dtest=ResourceAccessServiceTest,StructuredDataRetrievalServiceTest test`

提交：`feat(resources): unify workspace resource access`

## 发布包 C：FastGPT 集成与可信问答

### 任务 7：新增 FastGPT 配置和客户端

**文件：**

- 创建：`backend/src/main/java/com/argus/rag/integration/fastgpt/FastGptProperties.java`
- 创建：`backend/src/main/java/com/argus/rag/integration/fastgpt/FastGptClient.java`
- 创建：`backend/src/main/java/com/argus/rag/integration/fastgpt/HttpFastGptClient.java`
- 创建：`backend/src/main/java/com/argus/rag/integration/fastgpt/model/`
- 修改：`backend/src/main/resources/application.yml`
- 修改：`backend/src/main/resources/application-prod.yml`
- 新建：`backend/src/test/java/com/argus/rag/integration/fastgpt/HttpFastGptClientTest.java`

- [ ] **步骤 1：定义配置绑定**

配置前缀固定为 `argus.rag.fastgpt`：`enabled`、`base-url`、`api-version`、`api-key`、`connect-timeout`、`read-timeout`、`max-retries`。`api-key` 只能来自 `${ARGUS_FASTGPT_API_KEY}`，生产 YAML 不得写入值。

- [ ] **步骤 2：编写 WireMock 或 MockWebServer 契约测试**

覆盖认证 Header、超时、429/5xx 指数退避、非法响应转换、请求 ID 透传。不得在日志中输出 API Key 或完整用户问题。

- [ ] **步骤 3：实现 `HttpFastGptClient`**

使用 Spring `RestClient` 或 `WebClient`，集中实现数据集创建、文档 upsert/delete 与对话。所有远端响应转换为内部 DTO，禁止控制器返回 FastGPT 原始 JSON。

- [ ] **步骤 4：验证与 Commit**

运行：`mvn -f backend/pom.xml -Dtest=HttpFastGptClientTest test`

提交：`feat(fastgpt): add versioned backend client`

### 任务 8：实现可靠文档同步 Outbox

**文件：**

- 创建：`backend/src/main/resources/db/migration/V7__fastgpt_bindings_and_sync_outbox.sql`
- 创建：`backend/src/main/java/com/argus/rag/sync/entity/RagSyncOutboxEvent.java`
- 创建：`backend/src/main/java/com/argus/rag/sync/service/RagSyncOutboxService.java`
- 创建：`backend/src/main/java/com/argus/rag/sync/service/FastGptSyncWorker.java`
- 修改：`backend/src/main/java/com/argus/rag/document/service/DocumentUploadService.java`
- 修改：`backend/src/main/java/com/argus/rag/document/service/DocumentDeleteService.java`
- 新建：`backend/src/test/java/com/argus/rag/sync/FastGptSyncWorkerTest.java`
- 新建：`backend/src/test/java/com/argus/rag/sync/RagSyncOutboxServiceTest.java`

- [ ] **步骤 1：定义同步载荷和状态机**

`UPSERT` 载荷包含 `resourceUuid`、内容版本、文件名、纯文本或对象存储受控下载地址、敏感等级、工作空间标识。状态固定为 `PENDING`、`PROCESSING`、`SUCCEEDED`、`RETRYING`、`DEAD`。

- [ ] **步骤 2：在领域事务中写入 Outbox**

文档 READY、文档删除、权限收回、资源敏感等级变化时，同事务插入事件。相同 `resourceId + contentHash + action` 的未完成事件做幂等合并。

- [ ] **步骤 3：实现工作器**

每次批量领取 20 条到期事件，使用 `FOR UPDATE SKIP LOCKED` 或等价锁避免多实例重复消费。退避间隔为 1、5、30、120、600 分钟；失败 5 次进入 `DEAD` 并触发管理员告警。

- [ ] **步骤 4：实现删除和权限收回**

文档删除、资源 ACL 取消、成员禁用时，立即创建 `REVOKE` 或 `DELETE` 事件，并在 Argus 本地网关即时拒绝访问；远端最终一致性不能成为越权窗口。

- [ ] **步骤 5：验证与 Commit**

测试重复事件、网络超时、远端 429、进程重启、死信重放、删除后查询。运行：`mvn -f backend/pom.xml -Dtest=RagSyncOutboxServiceTest,FastGptSyncWorkerTest test`

提交：`feat(rag-sync): add reliable FastGPT synchronization`

### 任务 9：增加 RAG 查询网关和可验证引用

**文件：**

- 创建：`backend/src/main/java/com/argus/rag/gateway/RagChatController.java`
- 创建：`backend/src/main/java/com/argus/rag/gateway/RagChatService.java`
- 创建：`backend/src/main/java/com/argus/rag/gateway/CitationAuthorizationService.java`
- 创建：`backend/src/main/java/com/argus/rag/gateway/model/`
- 创建：`frontend/src/api/rag.ts`
- 修改：`frontend/src/views/assistant/AssistantView.vue`
- 修改：`frontend/src/views/assistant/components/AssistantCitationBar.vue`
- 新建：`backend/src/test/java/com/argus/rag/gateway/RagChatAuthorizationIT.java`

- [ ] **步骤 1：定义受控聊天请求**

前端只发送 `workspaceUuid`、`applicationCode`、`conversationId`、`question`。禁止发送 FastGPT datasetId、documentId、API Key、检索过滤器或系统提示词。

- [ ] **步骤 2：实现问答授权链路**

`RagChatService` 先要求工作空间成员资格，再解析本地可见资源列表，构造受控 FastGPT 查询。任何返回引用先映射到 `business_resources`，逐条调用 `ResourceAccessService.requireView`；无权引用从回答中移除。

- [ ] **步骤 3：实现引用规范**

每条引用返回 `citationId`、`resourceUuid`、`displayName`、`excerpt`、`version`、`sourceType`。前端点击引用时调用 Argus Loom 的引用接口，而不是跳转 FastGPT 或对象存储 URL。

- [ ] **步骤 4：实现拒答规则**

未检索到可见引用、引用均被权限过滤、模型服务失败时，统一返回结构化拒答原因。回答页面显示“没有足够的已授权来源”，不得伪造来源。

- [ ] **步骤 5：验证与 Commit**

运行：`mvn -f backend/pom.xml -Dtest=RagChatAuthorizationIT test`，`npm --prefix frontend run test`，`npm --prefix frontend run type-check`

提交：`feat(rag): proxy FastGPT with local authorization`

## 发布包 D：财务经营闭环

### 任务 10：导入模板、字段映射和错误行管理

**文件：**

- 创建：`backend/src/main/resources/db/migration/V8__finance_import_jobs.sql`
- 创建：`backend/src/main/java/com/argus/rag/finance/importjob/`
- 修改：`backend/src/main/java/com/argus/rag/excel/service/FinanceImportService.java`
- 创建：`frontend/src/views/finance/imports/ImportJobsView.vue`
- 创建：`frontend/src/views/finance/imports/ImportMappingView.vue`
- 创建：`frontend/src/api/finance-import.ts`
- 新建：`backend/src/test/java/com/argus/rag/finance/importjob/FinanceImportJobServiceTest.java`

- [ ] **步骤 1：定义表与状态**

新增 `import_templates`、`import_jobs`、`import_job_errors`。导入任务状态固定为 `DRAFT`、`VALIDATING`、`FAILED`、`READY`、`POSTED`、`REVOKED`。

- [ ] **步骤 2：实现字段映射**

总账标准字段至少包括：凭证号、业务日期、会计期间、科目编码、科目名称、借方金额、贷方金额、币种、组织、成本中心、来源记录 ID。模板保存列名、转换器和必填规则。

- [ ] **步骤 3：实现校验器**

校验日期、金额、币种、科目、必填字段、借贷平衡、同源记录重复。错误必须按行、列、规则代码、用户可读消息持久化，前端可筛选并下载错误行。

- [ ] **步骤 4：实现幂等入账**

唯一键为 `(workspace_id, source_system, source_record_id, accounting_period)`；同一来源重复导入不重复创建流水。

- [ ] **步骤 5：验证与 Commit**

运行：`mvn -f backend/pom.xml -Dtest=FinanceImportJobServiceTest test`

提交：`feat(finance): add validated import jobs`

### 任务 11：异常、任务、审批与关账

**文件：**

- 创建：`backend/src/main/resources/db/migration/V9__finance_periods_alerts_approvals.sql`
- 创建：`backend/src/main/java/com/argus/rag/finance/period/`
- 创建：`backend/src/main/java/com/argus/rag/finance/alert/`
- 创建：`backend/src/main/java/com/argus/rag/approval/`
- 创建：`frontend/src/views/finance/periods/`
- 新建：`backend/src/test/java/com/argus/rag/finance/period/FinanceClosePeriodIT.java`

- [ ] **步骤 1：定义期间与审批状态机**

期间状态：`OPEN`、`CLOSING`、`CLOSED`、`REOPENED`。审批状态：`DRAFT`、`PENDING`、`APPROVED`、`REJECTED`、`CANCELED`。已关账期间禁止修改原始流水，只允许创建可审计的调整记录。

- [ ] **步骤 2：实现第一批异常规则**

规则代码固定为：`UNBALANCED_VOUCHER`、`DUPLICATE_SOURCE_RECORD`、`BUDGET_OVERSPEND`、`OVERDUE_RECEIVABLE`、`UNUSUAL_EXPENSE_GROWTH`。每个异常可指派负责人、关联资源和提交审批。

- [ ] **步骤 3：实现关账事务**

关账前检查未解决的严重异常和未完成审批。通过后生成不可变期间快照、审计事件和用于 RAG 的受控汇总资源。

- [ ] **步骤 4：验证与 Commit**

运行：`mvn -f backend/pom.xml -Dtest=FinanceClosePeriodIT test`

提交：`feat(finance): add alerts approvals and period close`

## 发布包 E：运营、评测与生产部署

### 任务 12：RAG 评测、监控与上线控制

**文件：**

- 修改：`scripts/rag_eval/`
- 创建：`scripts/rag_eval/fastgpt_eval_cases.jsonl`
- 创建：`backend/src/main/java/com/argus/rag/metrics/RagGatewayMetrics.java`
- 修改：`backend/src/main/java/com/argus/rag/auth/security/SecurityConfig.java`
- 创建：`deploy/fastgpt/docker-compose.production.yml`
- 创建：`deploy/fastgpt/.env.production.example`
- 创建：`deploy/fastgpt/README.md`
- 新建：`backend/src/test/java/com/argus/rag/gateway/RagGatewayObservabilityTest.java`

- [ ] **步骤 1：建立评测集**

从 `scripts/rag_eval/corpus` 选取至少 100 个问题，按制度问答、版本冲突、财务权限、无答案、引用准确性五类标注期望答案和允许来源。敏感问题必须覆盖无权用户拒答。

- [ ] **步骤 2：定义上线阈值**

首个试点版本要求：有引用回答准确率至少 85%，无权资源泄露率为 0，错误引用率低于 3%，P95 首字响应低于 8 秒，FastGPT 同步成功率至少 99%。不满足任一条件不得扩大试点。

- [ ] **步骤 3：增加可观测性**

指标至少包括：RAG 请求数、拒答数、权限过滤引用数、FastGPT 延迟、同步队列深度、重试数、死信数、模型 Token、导入校验失败率。指标标签禁止包含用户问题、文档名称或个人信息。

- [ ] **步骤 4：收紧生产暴露面**

生产环境不公开 Prometheus、Swagger 和 FastGPT 管理端。通过 VPN、内网、反向代理白名单或管理员 SSO 访问。FastGPT、数据库、对象存储和 Redis 只暴露私有网络。

- [ ] **步骤 5：编写部署和回滚文档**

部署文档必须包含环境变量清单、密钥轮换、数据库备份恢复、对象存储备份、版本升级、健康检查、告警和回滚步骤。禁止把真实密码、Token、Endpoint 或客户文档放入仓库。

- [ ] **步骤 6：验证与 Commit**

运行：`mvn -f backend/pom.xml test`，`npm --prefix frontend run test`，`npm --prefix frontend run type-check`，`npm --prefix frontend run build`

提交：`chore(ops): add RAG evaluation and production controls`

## 接口与前端验收清单

### 用户可见页面

1. 工作空间选择器：显示当前工作空间、角色和切换入口。
2. 知识资源中心：显示资源类型、敏感等级、同步状态、最近同步错误和重试入口。
3. 可信助手：每个答案显示数据范围、引用、版本和拒答原因；引用跳转回本地资源。
4. 财务导入中心：上传、映射、校验、错误行、历史批次和状态流转。
5. 财务期间中心：异常、负责人、审批、关账进度和期间快照。
6. 运营台：同步死信、RAG 质量、延迟、成本和审计导出。

### 权限验收矩阵

| 动作 | OWNER | FINANCE_ADMIN | ANALYST | EDITOR | VIEWER | AUDITOR |
| --- | --- | --- | --- | --- | --- | --- |
| 管理成员 | 是 | 否 | 否 | 否 | 否 | 否 |
| 同步普通知识资源 | 是 | 是 | 否 | 否 | 否 | 否 |
| 查看财务资源 | 需 FINANCE | 是 | 需 ACL + FINANCE | 否 | 否 | 审计摘要 |
| 编辑工作簿 | 是 | 是 | 可授权 | 可授权 | 否 | 否 |
| 提交财务导入 | 是 | 是 | 否 | 否 | 否 | 否 |
| 关账 | 是且 FINANCE | 是 | 否 | 否 | 否 | 查看记录 |
| 查看审计 | 是 | 是 | 否 | 否 | 否 | 是 |

## 最终验证顺序

1. 在全新 PostgreSQL 数据库执行 Flyway，确认 V1 至 V9 全部成功。
2. 运行所有后端单元测试和集成测试。
3. 运行前端 Vitest、类型检查与生产构建。
4. 用三个用户执行手工权限回归：普通用户、FINANCE 用户、仅 ADMIN 用户。
5. 用网络不可达、FastGPT 429、FastGPT 5xx、对象存储失败四类故障演练 Outbox 重试与用户提示。
6. 执行 100 题 RAG 评测并保存结果，人工复核所有错误引用和拒答。
7. 在隔离试点工作空间运行一个完整月度流程：导入、校验、异常、审批、关账、RAG 问答、审计导出。

## 执行顺序

按发布包 A、B、C、D、E 依次执行。发布包 A 完成前，不得接入生产 FastGPT 账号或向外同步敏感文档。发布包 C 完成前，前端不得直接显示 FastGPT 的原始引用或管理链接。发布包 D 完成前，财务数据不得被通用 Agent 自动访问。

## 计划自检

- 权限越权、财务隔离、XSS、SSRF、结构化数据 ID 混用分别由任务 1 至 4 覆盖。
- 工作空间、统一资源和 ACL 由任务 5 至 6 覆盖。
- FastGPT 客户端、可靠同步、受控问答和引用由任务 7 至 9 覆盖。
- 财务导入到关账业务闭环由任务 10 至 11 覆盖。
- 评测、监控、部署、备份和发布门槛由任务 12 覆盖。
- 后续任务使用的 `workspaceUuid`、`resourceUuid`、`ResourceAccessService`、`FastGptClient` 与 Outbox 状态机均在前置任务中定义。
