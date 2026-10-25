package com.argus.rag.assistant.memory.longterm;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link MemoryExtractionListener} 记忆抽取监听器契约（mock MemoryLifecycleService，纯 JVM/零 DB/零 LLM）。
 * 只为用户显式请求记忆的内容建候选记忆：null 消息/回复短路、无显式标记不触发、
 * 有显式标记但无偏好句式（always/prefer/by default/use/set/choose）不落候选；
 * 触发时构造 UserMemoryEntity（type=PREFERENCE/topic=response.style/source=MODEL_INFERENCE/
 * confidence=0.35/importance=0.50 + 会话与租户透传），createCandidate 抛异常被吞不阻断。
 */
@DisplayName("记忆抽取监听器（MemoryExtractionListener）")
class MemoryExtractionListenerTest {

    private final MemoryLifecycleService memoryLifecycleService = mock(MemoryLifecycleService.class);
    private final MemoryExtractionListener listener = new MemoryExtractionListener(memoryLifecycleService);

    private static MemoryExtractionEvent event(String userMessage, String reply) {
        return new MemoryExtractionEvent(7L, 9L, 13L, userMessage, reply);
    }

    @Test
    void nullUserMessageSkipsCandidate() {
        listener.handleMemoryExtraction(event(null, "anything"));
        verify(memoryLifecycleService, never()).createCandidate(any(UserMemoryEntity.class));
    }

    @Test
    void nullReplySkipsCandidateCreation() {
        listener.handleMemoryExtraction(event("please remember that", null));
        verify(memoryLifecycleService, never()).createCandidate(any(UserMemoryEntity.class));
    }

    @Test
    void withoutExplicitMarkerSkipsCandidateEvenIfPreferenceShaped() {
        listener.handleMemoryExtraction(event("could you always use short forms", "of course"));
        verify(memoryLifecycleService, never()).createCandidate(any(UserMemoryEntity.class));
    }

    @Test
    void explicitMarkerWithoutPreferencePhraseSkipsCandidate() {
        // 有 "remember" 但无 (always|prefer|by default|use|set|choose) 偏好句式
        listener.handleMemoryExtraction(event("please remember that", "the file was uploaded at noon"));
        verify(memoryLifecycleService, never()).createCandidate(any(UserMemoryEntity.class));
    }

    @Test
    void explicitMarkerWithPreferenceCreatesTypedCandidate() {
        listener.handleMemoryExtraction(
                event("please remember that", "always use our company name in responses"));
        ArgumentCaptor<UserMemoryEntity> captor = ArgumentCaptor.forClass(UserMemoryEntity.class);
        verify(memoryLifecycleService).createCandidate(captor.capture());
        UserMemoryEntity entity = captor.getValue();
        assertEquals(7L, entity.getUserId());
        assertEquals(9L, entity.getTenantId());
        assertEquals(13L, entity.getSourceSessionId());
        assertEquals("PREFERENCE", entity.getMemoryType());
        assertEquals("response.style", entity.getTopic());
        assertEquals("MODEL_INFERENCE", entity.getSource());
        assertEquals(0, new BigDecimal("0.35").compareTo(entity.getConfidence()));
        assertEquals(0, new BigDecimal("0.50").compareTo(entity.getImportance()));
        assertEquals("User preference: always use our company name in responses", entity.getContent());
    }

    @Test
    void preferenceContentPrefixedAndSourcedFromReply() {
        listener.handleMemoryExtraction(
                event("please remember that", "always use a short answer style"));
        ArgumentCaptor<UserMemoryEntity> captor = ArgumentCaptor.forClass(UserMemoryEntity.class);
        verify(memoryLifecycleService).createCandidate(captor.capture());
        UserMemoryEntity entity = captor.getValue();
        assertEquals("User preference: always use a short answer style", entity.getContent());
    }

    @Test
    void createCandidateFailureIsSwallowedAsNonBlocking() {
        when(memoryLifecycleService.createCandidate(any(UserMemoryEntity.class)))
                .thenThrow(new RuntimeException("boom"));
        // 不应把抽取异常冒泡成事件处理失败
        listener.handleMemoryExtraction(event("please remember that", "always use my style"));
    }
}