-- V11: 用户级/平台级模型配置（BYOK）
-- 参考 Smart Draw 的模型配置方式：用户可自带 API 地址/密钥/模型 ID；
-- 企业内形态为服务端存储（浏览器直连 LLM 不成立——RAG 检索与提示词组装都在服务端）。
-- user_id 为 NULL 的行是"平台默认配置"，仅 ADMIN 可写；用户级行存在且启用时优先生效，
-- 否则回落平台行，再回落 .env 全局配置（spring.ai.openai.*）。

CREATE TABLE user_model_config (
    id          BIGSERIAL PRIMARY KEY,
    user_id     BIGINT       NULL REFERENCES users (id) ON DELETE CASCADE,
    base_url    VARCHAR(512) NOT NULL,
    api_key_enc TEXT         NOT NULL,
    model_id    VARCHAR(128) NOT NULL,
    enabled     BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMP    NOT NULL DEFAULT NOW()
);

-- 平台默认最多一行（user_id IS NULL）：恒真表达式的部分唯一索引
CREATE UNIQUE INDEX uq_model_config_platform ON user_model_config ((1)) WHERE user_id IS NULL;

-- 用户级每人最多一行
CREATE UNIQUE INDEX uq_model_config_user ON user_model_config (user_id) WHERE user_id IS NOT NULL;

COMMENT ON TABLE user_model_config IS '模型配置：user_id NULL=平台默认(仅ADMIN可写)，非空=用户级覆盖；api_key_enc 为 AES-256-GCM 密文';
