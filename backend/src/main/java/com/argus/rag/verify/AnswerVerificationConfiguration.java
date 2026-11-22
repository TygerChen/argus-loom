package com.argus.rag.verify;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;

/**
 * 独立校验模块装配：裁判提示词原文（纯文本 {{var}} 占位，理由同 understanding/planning
 * ——JSON 示例花括号与 StringTemplate 语法冲突）+ 裁判 LLM 端口适配。
 */
@Configuration
public class AnswerVerificationConfiguration {

    @Bean("verifyPromptText")
    public String verifyPromptText() {
        try (var in = new ClassPathResource("prompts/verify/user.st").getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("校验提示词加载失败", e);
        }
    }

    @Bean
    public VerifyClient verifyClient(ChatClient.Builder chatClientBuilder) {
        ChatClient chatClient = chatClientBuilder.build();
        return userPrompt -> chatClient.prompt()
                .user(userPrompt)
                .call()
                .content();
    }
}
