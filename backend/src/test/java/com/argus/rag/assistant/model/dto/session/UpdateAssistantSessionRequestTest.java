package com.argus.rag.assistant.model.dto.session;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link UpdateAssistantSessionRequest} 更新助手会话标题请求校验契约（独立 jakarta Validator/纯 JVM）。
 * title @NotBlank：null/空白→「title 不能为空」；@Size(max=255)：256 字符→「title 长度不能超过 255」。
 */
@DisplayName("更新助手会话请求校验（UpdateAssistantSessionRequest）")
class UpdateAssistantSessionRequestTest {

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

    private static String messages(String title) {
        Set<ConstraintViolation<UpdateAssistantSessionRequest>> violations =
                validator.validate(new UpdateAssistantSessionRequest(title));
        return violations.stream().map(ConstraintViolation::getMessage).collect(Collectors.joining("|"));
    }

    @Test
    void validTitleHasNoViolations() {
        assertTrue(validator.validate(new UpdateAssistantSessionRequest("我的新会话")).isEmpty());
    }

    @Test
    void blankTitleIsViolated() {
        assertEquals("title 不能为空", messages(""));
        assertEquals("title 不能为空", messages(null));
        assertEquals("title 不能为空", messages("   "));
    }

    @Test
    void oversizeTitleIsViolated() {
        String longTitle = IntStream.range(0, 256).mapToObj(i -> "a").collect(Collectors.joining());
        assertTrue(messages(longTitle).contains("title 长度不能超过 255"));
    }

    @Test
    void maxLengthTitleHasNoViolations() {
        String maxTitle = IntStream.range(0, 255).mapToObj(i -> "a").collect(Collectors.joining());
        assertTrue(validator.validate(new UpdateAssistantSessionRequest(maxTitle)).isEmpty());
    }
}