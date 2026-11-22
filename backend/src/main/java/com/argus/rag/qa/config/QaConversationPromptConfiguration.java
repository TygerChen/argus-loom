package com.argus.rag.qa.config;

import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;

/**
 * QA 多轮会话记忆模块 Prompt 模板配置。
 * <p>注册两个模板：检索改写（把多轮追问改写为独立完整问题）
 * 与滚动摘要合并（旧摘要 + 新一轮问答 → 新摘要，结构化三段式）。</p>
 */
@Configuration
public class QaConversationPromptConfiguration {

    /**
     * 检索改写 Prompt 模板。
     * <p>结合对话摘要与近期对话，把最新追问改写为独立完整的检索问题，
     * 供检索链路使用——历史只用于"补全问题"，不进入生成上下文。</p>
     *
     * @return 检索改写 PromptTemplate Bean
     */
    @Bean
    @Qualifier("qaConversationRewritePromptTemplate")
    public PromptTemplate qaConversationRewritePromptTemplate() {
        return PromptTemplate.builder()
                .resource(new ClassPathResource("prompts/qa/conversation-rewrite.st"))
                .build();
    }

    /**
     * 滚动摘要合并 Prompt 模板。
     * <p>回答完成后异步调用：旧摘要 + 新一轮问答 → 新摘要（结构化三段式，
     * 软上限 400 字），作为下一轮的记忆背景。</p>
     *
     * @return 滚动摘要合并 PromptTemplate Bean
     */
    @Bean
    @Qualifier("qaConversationSummaryMergePromptTemplate")
    public PromptTemplate qaConversationSummaryMergePromptTemplate() {
        return PromptTemplate.builder()
                .resource(new ClassPathResource("prompts/qa/conversation-summary-merge.st"))
                .build();
    }
}
