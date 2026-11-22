package com.argus.rag.settings;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 模型配置实体（user_id NULL = 平台默认，仅 ADMIN 可写；见 V11 迁移注释）。
 */
@TableName("user_model_config")
public class UserModelConfigEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** NULL = 平台默认行；非空 = 用户级覆盖 */
    private Long userId;

    private String baseUrl;

    /** AES-256-GCM 密文（ModelConfigCrypto） */
    private String apiKeyEnc;

    private String modelId;

    private Boolean enabled;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getApiKeyEnc() { return apiKeyEnc; }
    public void setApiKeyEnc(String apiKeyEnc) { this.apiKeyEnc = apiKeyEnc; }
    public String getModelId() { return modelId; }
    public void setModelId(String modelId) { this.modelId = modelId; }
    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
