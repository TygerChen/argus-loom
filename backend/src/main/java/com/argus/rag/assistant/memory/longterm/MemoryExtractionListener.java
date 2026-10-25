package com.argus.rag.assistant.memory.longterm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 记忆抽取事件监听器。
 * <p>异步监听助手回复事件，抽出可能成为长期记忆的候选内容。
 * 仅在用户明确要求“记住”时才抽取（例如说 “remember that…” 这类请求）。</p>
 */
@Component
public class MemoryExtractionListener {

    private static final Logger log = LoggerFactory.getLogger(MemoryExtractionListener.class);

    /** 能识别「用户明确要求保存」的关键词模式（大小写不敏感）。 */
    private static final Pattern EXPLICIT_MEMORY_PATTERN = Pattern.compile(
            "remember|save this|keep this|note this|remember that|save preference|set preference",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern PREFERENCE_PATTERN = Pattern.compile(
            "(always|prefer|by default|from now on).{0,20}(use|set|choose).{0,30}",
            Pattern.CASE_INSENSITIVE
    );

    private final MemoryLifecycleService memoryLifecycleService;

    public MemoryExtractionListener(MemoryLifecycleService memoryLifecycleService) {
        this.memoryLifecycleService = memoryLifecycleService;
    }

    @Async
    @EventListener
    public void handleMemoryExtraction(MemoryExtractionEvent event) {
        try {
            if (event.userMessage() == null || event.reply() == null) {
                return;
            }
            String combined = event.userMessage() + " " + event.reply();
            if (!EXPLICIT_MEMORY_PATTERN.matcher(combined).find()) {
                return;
            }
            Matcher preferenceMatcher = PREFERENCE_PATTERN.matcher(combined);
            if (preferenceMatcher.find()) {
                String content = extractPreferenceContent(combined);
                if (content != null && !content.isBlank()) {
                    createCandidateMemory(event, "PREFERENCE", "response.style", content);
                }
            }
        } catch (Exception e) {
            log.warn("Memory extraction failed (non-blocking): userId={}, error={}",
                    event.userId(), e.getMessage());
        }
    }

    private void createCandidateMemory(
            MemoryExtractionEvent event,
            String memoryType,
            String topic,
            String content
    ) {
        UserMemoryEntity entity = new UserMemoryEntity();
        entity.setUserId(event.userId());
        entity.setTenantId(event.tenantId());
        entity.setMemoryType(memoryType);
        entity.setTopic(topic);
        entity.setContent(content);
        entity.setSource("MODEL_INFERENCE");
        entity.setConfidence(new BigDecimal("0.35"));
        entity.setImportance(new BigDecimal("0.50"));
        entity.setSourceSessionId(event.sessionId());
        memoryLifecycleService.createCandidate(entity);
        log.debug("Candidate memory created: userId={}, type={}, content={}",
                event.userId(), memoryType, content);
    }

    private static String extractPreferenceContent(String text) {
        Matcher m = PREFERENCE_PATTERN.matcher(text);
        if (m.find()) {
            String match = m.group();
            if (match.length() > 0 && match.length() < 200) {
                return "User preference: " + match;
            }
        }
        return text.length() > 200 ? text.substring(0, 200) + "..." : text;
    }
}