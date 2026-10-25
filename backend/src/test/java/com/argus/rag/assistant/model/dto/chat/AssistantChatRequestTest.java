package com.argus.rag.assistant.model.dto.chat;

import com.argus.rag.assistant.model.enums.AssistantToolMode;
import com.argus.rag.assistant.model.enums.KbRouteStrategy;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AssistantChatRequest} 助手聊天请求：Bean Validation 契约 + 纯 JVM 生效逻辑（独立 Validator）。
 * sessionId/message/toolMode 必填（@NotNull/@NotBlank + message @Size(max=4000)；groupId @Positive）；
 * effectiveGroupIds() 去重并过滤非正 id、effectiveRouteStrategy() 缺省策略推断。
 */
@DisplayName("助手聊天请求（AssistantChatRequest）")
class AssistantChatRequestTest {

    private static ValidatorFactory factory;
    private static jakarta.validation.Validator validator;

    @BeforeAll
    static void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void tearDown() {
        factory.close();
    }

    private static String messages(AssistantChatRequest r) {
        Set<ConstraintViolation<AssistantChatRequest>> vs = validator.validate(r);
        return vs.stream().map(ConstraintViolation::getMessage).collect(Collectors.joining("|"));
    }

    private static AssistantChatRequest valid() {
        return new AssistantChatRequest(1L, "你好", AssistantToolMode.CHAT, null, null, null);
    }

    @Test
    void validRequestHasNoViolations() {
        assertTrue(validator.validate(valid()).isEmpty());
    }

    @Test
    void nullSessionIdIsViolated() {
        assertEquals("sessionId 不能为空",
                messages(new AssistantChatRequest(null, "hi", AssistantToolMode.CHAT, null, null, null)));
    }

    @Test
    void nonPositiveSessionIdIsViolated() {
        assertTrue(messages(new AssistantChatRequest(0L, "hi", AssistantToolMode.CHAT, null, null, null))
                .contains("sessionId 非法"));
    }

    @Test
    void blankOrOversizeMessageIsViolated() {
        assertTrue(messages(new AssistantChatRequest(1L, "", AssistantToolMode.CHAT, null, null, null))
                .contains("message 不能为空"));
        String over = IntStream.range(0, 4001).mapToObj(i -> "a").collect(Collectors.joining());
        assertTrue(messages(new AssistantChatRequest(1L, over, AssistantToolMode.CHAT, null, null, null))
                .contains("message 长度不能超过 4000"));
    }

    @Test
    void nullToolModeIsViolated() {
        assertEquals("toolMode 不能为空",
                messages(new AssistantChatRequest(1L, "hi", null, null, null, null)));
    }

    @Test
    void nonPositiveGroupIdIsViolated() {
        assertTrue(messages(new AssistantChatRequest(1L, "hi", AssistantToolMode.KB_SEARCH, -1L, null, null))
                .contains("groupId 非法"));
        assertTrue(messages(new AssistantChatRequest(1L, "hi", AssistantToolMode.KB_SEARCH, 0L, null, null))
                .contains("groupId 非法"));
    }

    // ── effectiveGroupIds ──
    @Test
    void effectiveGroupIdsDedupAndFilterNahPositive() {
        // 源码对 null/非正 id 显式跳过；List.of 不允许 null，故用 Arrays.asList 模拟反序列化出的 null 元素。
        AssistantChatRequest r = new AssistantChatRequest(1L, "hi", AssistantToolMode.KB_SEARCH,
                3L, java.util.Arrays.asList(5L, 3L, 0L, -2L, null, 9L), null);
        assertEquals(List.of(3L, 5L, 9L), r.effectiveGroupIds());
    }

    @Test
    void effectiveGroupIdsEmptyWhenNone() {
        assertEquals(List.of(), valid().effectiveGroupIds());
    }

    // ── effectiveRouteStrategy ──
    @Test
    void explicitStrategyWins() {
        AssistantChatRequest r = new AssistantChatRequest(1L, "hi", AssistantToolMode.CHAT,
                null, null, KbRouteStrategy.SELECTED);
        assertEquals(KbRouteStrategy.SELECTED, r.effectiveRouteStrategy());
    }

    @Test
    void autoWhenNoGroupsAndNoStrategy() {
        assertEquals(KbRouteStrategy.AUTO, valid().effectiveRouteStrategy());
    }

    @Test
    void selectedWhenGroupsAndNoStrategy() {
        AssistantChatRequest r = new AssistantChatRequest(1L, "hi", AssistantToolMode.KB_SEARCH,
                5L, List.of(2L), null);
        assertEquals(KbRouteStrategy.SELECTED, r.effectiveRouteStrategy());
    }
}