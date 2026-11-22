package com.argus.rag.planning;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;

/**
 * 任务规划模块装配：提示词原文（纯文本 {{var}} 占位，理由同 understanding 包——
 * JSON schema 花括号与 StringTemplate 语法冲突）+ LLM 端口适配。
 */
@Configuration
public class TaskPlanningConfiguration {

    @Bean("planningPromptText")
    public String planningPromptText() {
        try (var in = new ClassPathResource("prompts/planning/user.st").getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("规划提示词加载失败", e);
        }
    }

    @Bean
    public PlanClient planClient(ChatClient.Builder chatClientBuilder) {
        ChatClient chatClient = chatClientBuilder.build();
        return userPrompt -> chatClient.prompt()
                .user(userPrompt)
                .call()
                .content();
    }
}
