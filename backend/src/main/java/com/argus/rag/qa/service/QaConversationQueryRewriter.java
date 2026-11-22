package com.argus.rag.qa.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * QA 多轮检索改写器：把"多轮上下文中的最新追问"改写为独立完整的检索问题。
 * <p>
 * 这是三层记忆中"改写层"的实现——历史的作用是<b>补全问题</b>而不是塞进生成上下文：
 * 检索 query 必须术语精准、独立完整，才能命中正确证据
 * （例：历史确认了"凭证审核快捷键"，新问题"那反过审呢"应改写为
 * "总账凭证反过审的操作路径"）。
 * </p>
 * <p>
 * <b>失败语义：全链路 fail-soft</b>——功能关闭、无历史、LLM 超时/异常，
 * 一律回退使用原始问题，检索照常进行，绝不因改写失败而失败。
 * </p>
 */
@Slf4j
@Service
public class QaConversationQueryRewriter {

    private final ChatClient chatClient;
    private final PromptTemplate qaConversationRewritePromptTemplate;

    @Value("${argus.rag.qa.memory.rewrite-enabled:true}")
    private boolean rewriteEnabled;
    /** 改写上下文中历史文本的截断长度（摘要 + 近期窗口合计），防止改写输入膨胀。 */
    @Value("${argus.rag.qa.memory.rewrite-context-max-chars:1200}")
    private int rewriteContextMaxChars;

    public QaConversationQueryRewriter(
            org.springframework.ai.chat.client.ChatClient.Builder chatClientBuilder,
            @Qualifier("qaConversationRewritePromptTemplate") PromptTemplate qaConversationRewritePromptTemplate
    ) {
        this.chatClient = chatClientBuilder.build();
        this.qaConversationRewritePromptTemplate = qaConversationRewritePromptTemplate;
    }

    /**
     * 改写检索问题。
     *
     * @param question        用户最新原话（不改写时不做任何变更地返回）
     * @param conversationSummary 滚动摘要正文（可为 null）
     * @param recentTurnsText 近期原文窗口文本（可为 null）
     * @return 改写后的独立检索问题；任何失败路径都返回原问题
     */
    public String rewrite(String question, String conversationSummary, String recentTurnsText) {
        if (!rewriteEnabled) {
            return question;
        }
        boolean hasHistory = (conversationSummary != null && !conversationSummary.isBlank())
                || (recentTurnsText != null && !recentTurnsText.isBlank());
        if (!hasHistory) {
            // 首轮提问没有历史可补全，跳过改写（省一次 LLM 调用）。
            return question;
        }
        long startMs = System.currentTimeMillis();
        try {
            String summary = truncate(conversationSummary);
            String turns = truncate(recentTurnsText);
            Prompt prompt = qaConversationRewritePromptTemplate.create(Map.of(
                    "conversationSummary", summary == null ? "无" : summary,
                    "recentTurns", turns == null ? "无" : turns,
                    "question", question));
            String rewritten = chatClient.prompt(prompt)
                    .call()
                    .content();
            if (rewritten == null || rewritten.isBlank()) {
                log.warn("检索改写返回空，回退原问题");
                return question;
            }
            String cleaned = rewritten.strip();
            // 保守护栏：改写结果过长说明模型跑偏（输出了解释等），回退原问题。
            if (cleaned.length() > Math.max(question.length() * 3, 200)) {
                log.warn("检索改写结果异常过长（{} 字符），回退原问题", cleaned.length());
                return question;
            }
            log.info("检索改写完成: {} 字 → {} 字, 耗时 {}ms",
                    question.length(), cleaned.length(), System.currentTimeMillis() - startMs);
            return cleaned;
        } catch (Exception exception) {
            log.warn("检索改写失败，回退原问题: {}", exception.getMessage());
            return question;
        }
    }

    private String truncate(String text) {
        if (text == null) {
            return null;
        }
        return text.length() <= rewriteContextMaxChars ? text : text.substring(0, rewriteContextMaxChars);
    }
}
