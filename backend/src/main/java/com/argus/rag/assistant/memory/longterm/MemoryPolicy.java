package com.argus.rag.assistant.memory.longterm;

import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * 记忆「写入策略」评估器。
 * 根据记忆来源（source）、内容敏感度（sensitivity）与类型（type），
 * 决定一条候选记忆**是否应该被写进**长期记忆，并给出置信度。
 */
@Component
public class MemoryPolicy {

    private static final Set<String> SENSITIVE_TERMS = Set.of(
            "password", "token", "api_key", "api-key", "secret",
            "bank card", "credit card", "passport", "private key"
    );

    private static final Set<String> ALLOWED_TYPES = Set.of(
            "PREFERENCE", "FACT", "TASK"
    );

    /**
     * 评估一条记忆是否允许写入长期记忆，并给出处置与置信度。
     * <p>按顺序判定的决策优先级（任一命中即返回）：
     * <ul>
     *   <li>类型不在白名单 / 内容为空 / 含敏感词 → 直接<b>拒绝</b>；</li>
     *   <li>来源为「用户显式」或本次请求被标记为「显式要求」→ 高置信度<b>接受</b>；</li>
     *   <li>来源「模型推断」→ 只进<b>候选</b>（需用户确认后才落库）；</li>
     *   <li>来源「用户陈述 / 实时工具数据」→ 拒绝（陈述不默认存长期；工具数据时效性强不存）；</li>
     *   <li>来源「管理配置」→ <b>接受</b>；其余未知来源 → 拒绝。</li>
     * </ul>
     * 新手最容易困惑的是各种 {@code source} 的分派，区别就在于「是否够稳定、是否需用户二次确认」。
     * </p>
     */
    public Decision evaluate(String memoryType, String content, String source, boolean explicitRequest) {
        if (memoryType == null || !ALLOWED_TYPES.contains(memoryType.toUpperCase())) {
            return Decision.reject("unsupported memory type: " + memoryType);
        }
        if (content == null || content.isBlank()) {
            return Decision.reject("content must not be empty");
        }
        if (containsSensitiveTerms(content)) {
            return Decision.reject("content may contain sensitive information");
        }
        if ("USER_EXPLICIT".equalsIgnoreCase(source)) {
            return Decision.accept(0.98);
        }
        if (explicitRequest) {
            return Decision.accept(0.98);
        }
        if ("MODEL_INFERENCE".equalsIgnoreCase(source)) {
            return Decision.candidate(0.35, "model inference, requires user confirmation");
        }
        if ("USER_STATEMENT".equalsIgnoreCase(source)) {
            return Decision.reject("user statement, not persisted as long-term memory by default");
        }
        if ("TOOL_FACT".equalsIgnoreCase(source)) {
            return Decision.reject("real-time tool data should not be saved as long-term memory");
        }
        if ("ADMIN_CONFIG".equalsIgnoreCase(source)) {
            return Decision.accept(0.95);
        }
        return Decision.reject("unknown source: " + source);
    }

    boolean containsSensitiveTerms(String content) {
        if (content == null || content.isBlank()) {
            return false;
        }
        String lower = content.toLowerCase();
        for (String term : SENSITIVE_TERMS) {
            if (lower.contains(term)) {
                return true;
            }
        }
        return false;
    }

    public record Decision(boolean accepted, double confidence, String reason, boolean candidate) {
        public static Decision accept(double confidence) {
            return new Decision(true, confidence, "ok", false);
        }
        public static Decision reject(String reason) {
            return new Decision(false, 0.0, reason, false);
        }
        public static Decision candidate(double confidence, String reason) {
            return new Decision(false, confidence, reason, true);
        }
    }
}