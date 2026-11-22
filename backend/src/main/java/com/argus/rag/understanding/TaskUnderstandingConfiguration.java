package com.argus.rag.understanding;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;

/**
 * 任务理解模块装配：提示词文本 + LLM 端口适配。
 *
 * <p><b>为什么不用 Spring AI PromptTemplate</b>：其底层是 StringTemplate（ST4），
 * 模板中的 JSON 输出示例自带 {@code { }}，会被当成表达式语法导致渲染失败。
 * 本模块的提示词必须内嵌 JSON schema，故改用纯文本装载 + {@code {{var}}} 双花括号
 * 占位替换（编排层 TaskUnderstandingService 完成）——诚实简单，不跟模板引擎较劲。</p>
 */
@Configuration
public class TaskUnderstandingConfiguration {

    /**
     * 任务理解提示词原文（prompts/understanding/user.st，UTF-8）。
     * <p>占位符：{@code {{taskContext}}}（代码地面事实）、{@code {{question}}}（用户问题）。</p>
     */
    @Bean("understandingPromptText")
    public String understandingPromptText() {
        try (var in = new ClassPathResource("prompts/understanding/user.st").getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("任务理解提示词加载失败", e);
        }
    }

    /**
     * {@link UnderstandingClient} 端口的生产适配：ChatClient 单轮调用。
     * <p>超时/重试由全局 LLM 配置兜底；编排层对任何失败 fail-soft。</p>
     */
    @Bean
    public UnderstandingClient understandingClient(ChatClient.Builder chatClientBuilder) {
        ChatClient chatClient = chatClientBuilder.build();
        return userPrompt -> chatClient.prompt()
                .user(userPrompt)
                .call()
                .content();
    }
}
