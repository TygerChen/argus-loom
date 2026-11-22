package com.argus.rag.settings;

import com.argus.rag.common.enums.SystemRole;
import com.argus.rag.common.security.AuthenticatedUser;
import com.argus.rag.common.security.UserContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 模型配置服务：用户级 BYOK（自带密钥）+ 平台默认（ADMIN 维护），
 * 配置方式参考 Smart Draw 的"连接"分区并按企业形态服务端化。
 *
 * <p><b>解析优先级</b>：用户级(启用) → 平台默认(启用) → 回落全局 {@code spring.ai.openai.*}
 * （返回 null，调用方继续用全局 Bean）。</p>
 *
 * <p><b>为什么存服务端而不是浏览器</b>：Argus 的 LLM 调用发生在服务端
 * （RAG 检索与提示词组装都在后端），浏览器直连不成立；密钥 AES-256-GCM 加密落库
 * （{@link ModelConfigCrypto}），对外只吐掩码。</p>
 *
 * <p><b>客户端缓存</b>：按 baseUrl+key+model 的签名缓存
 * {@link CachedModels}（ChatModel 供 Assistant 的 ReactAgent 用，
 * ChatClient 带 QA 系统提示词与 RAG 顾问供问答链路用）。
 * 用户改配置 → 新签名自然失效；超上限整体清空。</p>
 */
@Service
public class UserChatModelService {

    private static final Logger log = LoggerFactory.getLogger(UserChatModelService.class);
    private static final int CACHE_MAX = 64;

    private final UserModelConfigMapper mapper;
    private final ModelConfigCrypto crypto;
    private final PromptTemplate qaSystemPromptTemplate;
    private final RetrievalAugmentationAdvisor qaRetrievalAdvisor;

    /** 签名 → 模型 + 问答客户端 */
    private final Map<String, CachedModels> cache = new LinkedHashMap<>(32, 0.75f, true);

    private record CachedModels(ChatModel model, ChatClient qaClient) {
    }

    public UserChatModelService(
            UserModelConfigMapper mapper,
            ModelConfigCrypto crypto,
            @Qualifier("qaSystemPromptTemplate") PromptTemplate qaSystemPromptTemplate,
            @Qualifier("qaRetrievalAdvisor") RetrievalAugmentationAdvisor qaRetrievalAdvisor
    ) {
        this.mapper = mapper;
        this.crypto = crypto;
        this.qaSystemPromptTemplate = qaSystemPromptTemplate;
        this.qaRetrievalAdvisor = qaRetrievalAdvisor;
    }

    // ─────────────────────────── 视图 / 保存 / 清除 ───────────────────────────

    /** 配置视图（密钥只给掩码）。 */
    public record ModelConfigView(String baseUrl, String modelId, boolean enabled, String maskedKey,
                                  String updatedAt) {
        public static final ModelConfigView EMPTY =
                new ModelConfigView("", "", false, null, null);
    }

    /** 保存请求：apiKey 为空 = 沿用已存密钥（前端回显的是掩码，不能回传）。 */
    public record SaveModelConfigRequest(String baseUrl, String apiKey, String modelId, boolean enabled) {
    }

    /** 测试连通性结果。 */
    public record TestConnectionResult(boolean ok, String message, String reply, Long latencyMs) {
    }

    /** 当前身份对应作用域的配置视图：ADMIN = 平台行，USER/FINANCE = 本人行。 */
    public ModelConfigView viewForCurrentUser() {
        UserModelConfigEntity entity = currentScopeEntity();
        if (entity == null) {
            return ModelConfigView.EMPTY;
        }
        String masked = null;
        try {
            masked = ModelConfigCrypto.mask(crypto.decrypt(entity.getApiKeyEnc()));
        } catch (Exception e) {
            log.warn("模型密钥解密失败（可能轮换过 JWT 密钥），按未配置处理: {}", e.getMessage());
            return ModelConfigView.EMPTY;
        }
        return new ModelConfigView(entity.getBaseUrl(), entity.getModelId(),
                Boolean.TRUE.equals(entity.getEnabled()), masked,
                entity.getUpdatedAt() == null ? null : entity.getUpdatedAt().toString());
    }

    /** 保存当前身份作用域的配置。apiKey 非空 = 更新密钥；空 = 沿用旧密钥。 */
    public void saveForCurrentUser(SaveModelConfigRequest request) {
        validate(request);
        boolean platform = isPlatformScope();
        UserModelConfigEntity entity = currentScopeEntity();
        if (entity == null) {
            entity = new UserModelConfigEntity();
            entity.setUserId(platform ? null : requireUserId());
            entity.setCreatedAt(LocalDateTime.now());
        }
        entity.setBaseUrl(normalizeBaseUrl(request.baseUrl()));
        entity.setModelId(request.modelId().trim());
        entity.setEnabled(request.enabled());
        if (request.apiKey() != null && !request.apiKey().isBlank()) {
            entity.setApiKeyEnc(crypto.encrypt(request.apiKey().trim()));
        }
        if (entity.getApiKeyEnc() == null || entity.getApiKeyEnc().isBlank()) {
            throw new IllegalArgumentException("API Key 不能为空");
        }
        entity.setUpdatedAt(LocalDateTime.now());
        if (entity.getId() == null) {
            mapper.insert(entity);
        } else {
            mapper.updateById(entity);
        }
        cache.clear();
        log.info("模型配置已保存: scope={}, model={}", platform ? "platform" : "user", entity.getModelId());
    }

    /** 清除当前身份作用域的配置（恢复回落链）。 */
    public void clearForCurrentUser() {
        UserModelConfigEntity entity = currentScopeEntity();
        if (entity != null) {
            mapper.deleteById(entity.getId());
            cache.clear();
            log.info("模型配置已清除: scope={}", isPlatformScope() ? "platform" : "user");
        }
    }

    // ─────────────────────────── 聊天链路解析 ───────────────────────────

    /**
     * 为指定用户解析生效的 ChatClient（QA 链路）；无可用配置返回 null
     * （调用方继续用全局 qaChatClient）。客户端带与全局相同的系统提示词与
     * RAG 顾问——替换的是"模型"，不是"问答管线"。
     */
    public ChatClient chatClientFor(Long userId, ChatClient globalFallback) {
        UserModelConfigEntity entity = effectiveEntity(userId);
        if (entity == null) {
            return globalFallback;
        }
        CachedModels cached = cachedFor(entity);
        return cached == null ? globalFallback : cached.qaClient();
    }

    /** QA 链路便捷入口：按当前登录身份解析（未登录 → 全局客户端）。 */
    public ChatClient chatClientForCurrentUser(ChatClient globalFallback) {
        AuthenticatedUser user = UserContext.get();
        Long userId = user == null ? null : user.userId();
        if (userId == null) {
            return globalFallback;
        }
        return chatClientFor(userId, globalFallback);
    }

    /**
     * 为指定用户解析生效的 ChatModel（Assistant 的 ReactAgent 用）；
     * 无可用配置返回 null（调用方继续用全局 chatModel Bean）。
     */
    public ChatModel resolveChatModel(Long userId) {
        UserModelConfigEntity entity = effectiveEntity(userId);
        if (entity == null) {
            return null;
        }
        CachedModels cached = cachedFor(entity);
        return cached == null ? null : cached.model();
    }

    // ─────────────────────────── 连通性测试 ───────────────────────────

    /**
     * 测试连通性：真实调用一次 chat（maxTokens 限 16），结果不进缓存。
     * apiKey 为空 = 使用当前作用域已存密钥。
     */
    public TestConnectionResult test(SaveModelConfigRequest request) {
        validate(request);
        String apiKey = request.apiKey() != null && !request.apiKey().isBlank()
                ? request.apiKey().trim()
                : storedApiKeyForCurrentScope();
        if (apiKey == null || apiKey.isBlank()) {
            return new TestConnectionResult(false, "请填写 API Key（或先保存过配置）", null, null);
        }
        long start = System.currentTimeMillis();
        try {
            OpenAiApi api = OpenAiApi.builder()
                    .baseUrl(normalizeBaseUrl(request.baseUrl()))
                    .apiKey(apiKey)
                    .build();
            OpenAiChatModel model = OpenAiChatModel.builder()
                    .openAiApi(api)
                    .defaultOptions(OpenAiChatOptions.builder()
                            .model(request.modelId().trim())
                            .temperature(0.0)
                            .maxTokens(16)
                            .build())
                    .build();
            String reply = model.call(new Prompt("回复两个字：成功"))
                    .getResult().getOutput().getText();
            long latency = System.currentTimeMillis() - start;
            return new TestConnectionResult(true, "连接成功", reply, latency);
        } catch (Exception e) {
            long latency = System.currentTimeMillis() - start;
            String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            return new TestConnectionResult(false, cut(msg, 300), null, latency);
        }
    }

    // ─────────────────────────── 内部工具 ───────────────────────────

    private boolean isPlatformScope() {
        AuthenticatedUser user = UserContext.get();
        return user != null && user.systemRole() == SystemRole.ADMIN;
    }

    private Long requireUserId() {
        AuthenticatedUser user = UserContext.get();
        if (user == null || user.userId() == null) {
            throw new IllegalArgumentException("登录上下文缺失");
        }
        return user.userId();
    }

    /** 当前作用域的实体：ADMIN=平台行，否则本人行。 */
    private UserModelConfigEntity currentScopeEntity() {
        return isPlatformScope() ? mapper.findPlatform() : mapper.findByUserId(requireUserId());
    }

    /** 聊天解析用生效实体：用户级启用行 → 平台启用行 → null。 */
    private UserModelConfigEntity effectiveEntity(Long userId) {
        if (userId != null) {
            UserModelConfigEntity own = mapper.findByUserId(userId);
            if (own != null && Boolean.TRUE.equals(own.getEnabled())) {
                return own;
            }
        }
        UserModelConfigEntity platform = mapper.findPlatform();
        return platform != null && Boolean.TRUE.equals(platform.getEnabled()) ? platform : null;
    }

    /** 取签名对应的缓存项；未命中则构建并缓存。构建失败返回 null 并告警（不缓存失败）。 */
    private synchronized CachedModels cachedFor(UserModelConfigEntity entity) {
        String signature = signatureOf(entity);
        if (signature.isEmpty()) {
            return null;
        }
        CachedModels cached = cache.get(signature);
        if (cached != null) {
            return cached;
        }
        try {
            String apiKey = crypto.decrypt(entity.getApiKeyEnc());
            OpenAiApi api = OpenAiApi.builder()
                    .baseUrl(entity.getBaseUrl())
                    .apiKey(apiKey)
                    .build();
            OpenAiChatModel model = OpenAiChatModel.builder()
                    .openAiApi(api)
                    .defaultOptions(OpenAiChatOptions.builder()
                            .model(entity.getModelId())
                            .temperature(0.2)
                            .build())
                    .build();
            ChatClient qaClient = ChatClient.builder(model)
                    .defaultSystem(qaSystemPromptTemplate.getTemplate())
                    .defaultAdvisors(qaRetrievalAdvisor)
                    .build();
            CachedModels entry = new CachedModels(model, qaClient);
            cache.put(signature, entry);
            if (cache.size() > CACHE_MAX) {
                cache.clear();
            }
            return entry;
        } catch (Exception e) {
            log.warn("用户级模型构建失败，回落全局配置: model={}, err={}", entity.getModelId(), e.getMessage());
            return null;
        }
    }

    private String signatureOf(UserModelConfigEntity entity) {
        String plain;
        try {
            plain = crypto.decrypt(entity.getApiKeyEnc());
        } catch (Exception e) {
            return "";
        }
        return Integer.toHexString((entity.getBaseUrl() + "|" + plain + "|" + entity.getModelId()).hashCode());
    }

    private String storedApiKeyForCurrentScope() {
        UserModelConfigEntity entity = currentScopeEntity();
        if (entity == null) {
            return null;
        }
        try {
            return crypto.decrypt(entity.getApiKeyEnc());
        } catch (Exception e) {
            return null;
        }
    }

    private void validate(SaveModelConfigRequest request) {
        if (request == null || request.baseUrl() == null || request.baseUrl().isBlank()) {
            throw new IllegalArgumentException("API 地址不能为空");
        }
        String url = normalizeBaseUrl(request.baseUrl());
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            throw new IllegalArgumentException("API 地址必须以 http(s):// 开头");
        }
        if (request.modelId() == null || request.modelId().isBlank()) {
            throw new IllegalArgumentException("模型 ID 不能为空");
        }
        if (request.modelId().length() > 128 || url.length() > 512) {
            throw new IllegalArgumentException("字段长度超限");
        }
    }

    private String normalizeBaseUrl(String url) {
        String u = url.trim();
        while (u.endsWith("/")) {
            u = u.substring(0, u.length() - 1);
        }
        return u;
    }

    private static String cut(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
