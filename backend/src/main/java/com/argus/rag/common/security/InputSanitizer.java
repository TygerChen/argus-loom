package com.argus.rag.common.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 用户输入安全清洗器。
 * <p>在 LLM 调用前对用户输入做基础安全检查。实际能力分两层（P2-06 修正「假安全」文档）：</p>
 * <ul>
 *   <li>{@link #sanitize(String)}：仅做<b>长度截断 + trim</b>，<b>不会</b>清除或改写注入模式内容
 *       （命中只记审计日志）——因为用户可能只是在正常语境里引用这些词，直接改写会破坏正常提问；</li>
 *   <li>{@link #isSuspicious(String)}：检测输入是否命中注入模式，供调用方审计/风控决策；</li>
 *   <li>{@link #neutralize(String)}：确需无害化时使用，把命中的注入模式替换为中性占位符，
 *       防止内容被拼接进系统提示（需要改写语义的调用方显式选择）。</li>
 * </ul>
 * 注意：这只是第一道防线，不替代专业的内容审核服务。
 */
@Component
public class InputSanitizer {

    private static final Logger log = LoggerFactory.getLogger(InputSanitizer.class);

    /** 单次输入最大字符数 */
    private static final int MAX_INPUT_LENGTH = 8000;

    /** 命中注入模式时的中性占位符（neutralize 使用）。 */
    public static final String NEUTRAL_PLACEHOLDER = "[filtered]";

    /** Prompt Injection 敏感模式 */
    private static final String[] PROMPT_INJECTION_PATTERNS = {
            "ignore previous instructions",
            "ignore all previous",
            "disregard previous",
            "disregard all",
            "forget your instructions",
            "forget your previous",
            "new instructions:",
            "your new task is",
            "system prompt:",
            "system message:",
            "<|im_start|>",
            "<|im_end|>",
            "[INST]",
            "[/INST]",
            "忽略之前的指令",
            "忽略所有之前的",
            "无视之前的",
            "忘记你的",
            "新指令：",
            "你现在的任务是",
    };

    /**
     * 清洗用户输入（截断 + trim）。
     *
     * @param input 原始用户输入
     * @return 截断/trim 后的文本；空白输入返回空字符串。
     *         <b>注意</b>：不会清除或改写注入模式内容（命中仅记审计日志）；
     *         需要无害化改写的调用方请使用 {@link #neutralize(String)}。
     */
    public String sanitize(String input) {
        if (input == null || input.isBlank()) {
            return "";
        }

        // 长度截断
        if (input.length() > MAX_INPUT_LENGTH) {
            log.warn("输入过长被截断: originalLength={}, maxLength={}", input.length(), MAX_INPUT_LENGTH);
            input = input.substring(0, MAX_INPUT_LENGTH);
        }

        // Prompt Injection 检测（仅审计：不改写内容，避免误伤正常语境中的引用）
        String lowerInput = input.toLowerCase();
        for (String pattern : PROMPT_INJECTION_PATTERNS) {
            if (lowerInput.contains(pattern.toLowerCase())) {
                log.warn("检测到可能的 Prompt Injection 模式: pattern={}", pattern);
            }
        }

        return input.trim();
    }

    /**
     * 无害化改写（P2-06 补实现）：把命中的注入模式替换为 {@link #NEUTRAL_PLACEHOLDER}。
     *
     * <p>语义上与 {@link #sanitize(String)} 的差异：sanitize 保守不改写内容；
     * 本方法适用于「内容将被拼接进提示词/系统上下文」的调用方，需要消除注入指令时显式选择。
     * 同时保留长度截断与 trim。</p>
     *
     * @param input 原始用户输入
     * @return 替换注入模式后的文本；空白输入返回空字符串
     */
    public String neutralize(String input) {
        String cleaned = sanitize(input);
        if (cleaned.isEmpty()) {
            return cleaned;
        }
        String result = cleaned;
        for (String pattern : PROMPT_INJECTION_PATTERNS) {
            // 大小写不敏感替换：用正则 quote 防止模式里的元字符（如 <|im_start|>）被当作正则语法
            result = result.replaceAll("(?i)" + java.util.regex.Pattern.quote(pattern),
                    java.util.regex.Matcher.quoteReplacement(NEUTRAL_PLACEHOLDER));
        }
        return result;
    }

    /**
     * 检查输入是否可疑（用于日志审计）。
     */
    public boolean isSuspicious(String input) {
        if (input == null || input.isBlank()) {
            return false;
        }
        String lowerInput = input.toLowerCase();
        for (String pattern : PROMPT_INJECTION_PATTERNS) {
            if (lowerInput.contains(pattern.toLowerCase())) {
                return true;
            }
        }
        return false;
    }
}