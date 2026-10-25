package com.argus.rag.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Spring AI 实现的 {@link ModelClient}。
 * <p>基于 Spring AI ChatClient 封装，内置超时（通过 Spring AI 的 RestClient 配置）、
 * 重试（@Retryable 指数退避）和降级处理。</p>
 */
@Component
public class SpringAiModelClient implements ModelClient {

    private static final Logger log = LoggerFactory.getLogger(SpringAiModelClient.class);

    private static final String MODEL_UNAVAILABLE_MESSAGE = "模型服务暂时不可用，请稍后重试。";

    private final ChatClient.Builder chatClientBuilder;

    public SpringAiModelClient(@Qualifier("qaChatClientBuilder") ChatClient.Builder chatClientBuilder) {
        this.chatClientBuilder = chatClientBuilder;
    }

    @Override
    @Retryable(
            maxAttempts = 3,
            backoff = @Backoff(delay = 1000, multiplier = 2, maxDelay = 8000),
            retryFor = {RuntimeException.class},
            noRetryFor = {IllegalArgumentException.class}
    )
    public ChatResult chat(List<ChatMessage> messages, String instructions) {
        String traceId = UUID.randomUUID().toString().substring(0, 8);
        log.info("LLM sync chat start: traceId={}, messageCount={}", traceId, messages.size());

        List<Message> springMessages = convertMessages(messages);
        try {
            var response = chatClientBuilder.build().prompt()
                    .system(instructions)
                    .messages(springMessages)
                    .call()
                    .chatResponse();

            String content = response.getResult() != null && response.getResult().getOutput() != null
                    ? response.getResult().getOutput().getText()
                    : null;
            Usage usage = response.getMetadata() != null ? response.getMetadata().getUsage() : null;

            log.info("LLM sync chat done: traceId={}, contentLength={}",
                    traceId, content != null ? content.length() : 0);
            return new ChatResult(
                    content,
                    response.getMetadata() != null ? response.getMetadata().getId() : null,
                    usage != null && usage.getPromptTokens() != null ? usage.getPromptTokens() : 0,
                    usage != null && usage.getCompletionTokens() != null ? usage.getCompletionTokens() : 0,
                    response.getMetadata() != null ? response.getMetadata().getModel() : null
            );
        } catch (RuntimeException e) {
            log.error("LLM sync chat failed: traceId={}", traceId, e);
            throw e;
        }
    }

    @Override
    @Retryable(
            maxAttempts = 3,
            backoff = @Backoff(delay = 1000, multiplier = 2, maxDelay = 8000),
            retryFor = {RuntimeException.class},
            noRetryFor = {IllegalArgumentException.class}
    )
    public ChatResult streamChat(List<ChatMessage> messages, String instructions, Consumer<String> deltaConsumer) {
        String traceId = UUID.randomUUID().toString().substring(0, 8);
        log.info("LLM stream chat start: traceId={}, messageCount={}", traceId, messages.size());

        List<Message> springMessages = convertMessages(messages);
        StringBuilder fullReply = new StringBuilder();
        AtomicReference<Usage> usageRef = new AtomicReference<>();

        try {
            chatClientBuilder.build().prompt()
                    .system(instructions)
                    .messages(springMessages)
                    .stream()
                    .chatResponse()
                    .doOnNext(response -> {
                        Usage u = response.getMetadata() != null ? response.getMetadata().getUsage() : null;
                        if (u != null && u.getTotalTokens() != null && u.getTotalTokens() > 0) {
                            usageRef.set(u);
                        }
                        String delta = response.getResult() != null && response.getResult().getOutput() != null
                                ? response.getResult().getOutput().getText()
                                : null;
                        if (delta != null && !delta.isBlank()) {
                            fullReply.append(delta);
                            deltaConsumer.accept(delta);
                        }
                    })
                    .blockLast();

            Usage usage = usageRef.get();
            log.info("LLM stream chat done: traceId={}, replyLength={}", traceId, fullReply.length());
            return new ChatResult(
                    fullReply.toString(),
                    null,
                    usage != null && usage.getPromptTokens() != null ? usage.getPromptTokens() : 0,
                    usage != null && usage.getCompletionTokens() != null ? usage.getCompletionTokens() : 0,
                    null
            );
        } catch (RuntimeException e) {
            log.error("LLM stream chat failed: traceId={}", traceId, e);
            throw e;
        }
    }

    private List<Message> convertMessages(List<ChatMessage> messages) {
        List<Message> result = new ArrayList<>();
        for (ChatMessage msg : messages) {
            result.add(new UserMessage(msg.content()));
        }
        return result;
    }
}