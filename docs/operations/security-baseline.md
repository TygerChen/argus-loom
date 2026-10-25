# 安全基线

## 账号与密钥

- 生产所有密钥来自环境变量；代码里不允许出现默认密钥。
- 数据库账号只授 SELECT/INSERT/UPDATE/DELETE，不给 DDL（迁移用独立账号跑一次）。
- JWT 密钥 ≥ 32 字符；换密钥等于全员重新登录，纳入变更流程。

## 网络

- Nginx 强制 HTTPS（TLS 1.2+），HTTP 301 跳转。
- ARGUS_WS_ALLOWED_ORIGINS 只填真实站点域名。
- 数据库/ES/MinIO 只在内网监听，不开公网。

## 应用层

- 财务接口全部走 requireFinance()（ROLE_FINANCE），系统管理员无隐式豁免。
- 期间关闭后该月财务数据 fail-closed（FINANCE_PERIOD_CLOSED）。
- 上传：类型白名单 + 大小上限 + 路径规范化（已有 UploadInputGuard）。
- 导出产物 24 小时过期自动清扫；下载重鉴权。

## 审计

- 关键操作留痕：协同编辑（excel_collab_log）、财务编辑（操作日志）、
  期间关闭/重开（操作人 + 原因）。
- 日志中禁止打印密钥、Token、连接串；异常消息进健康检查前先脱敏。
