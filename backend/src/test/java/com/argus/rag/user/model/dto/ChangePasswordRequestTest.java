package com.argus.rag.user.model.dto;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ChangePasswordRequest} 修改密码请求校验契约（独立 jakarta Validator/纯 JVM）。
 * currentPassword/newPassword 均 @NotBlank：null 或空白触发对应中文违规消息；两字段同时空白→两条违规。
 */
@DisplayName("修改密码请求校验（ChangePasswordRequest）")
class ChangePasswordRequestTest {

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

    private static String messages(ChangePasswordRequest r) {
        return validator.validate(r).stream()
                .map(ConstraintViolation::getMessage)
                .collect(Collectors.joining("|"));
    }

    @Test
    void validChangeHasNoViolations() {
        assertTrue(validator.validate(new ChangePasswordRequest("old", "new")).isEmpty());
    }

    @Test
    void blankCurrentPasswordIsViolated() {
        assertTrue(messages(new ChangePasswordRequest(" ", "new")).contains("当前密码不能为空"));
        assertTrue(messages(new ChangePasswordRequest(null, "new")).contains("当前密码不能为空"));
    }

    @Test
    void blankNewPasswordIsViolated() {
        assertTrue(messages(new ChangePasswordRequest("old", "")).contains("新密码不能为空"));
        assertTrue(messages(new ChangePasswordRequest("old", null)).contains("新密码不能为空"));
    }

    @Test
    void bothBlankYieldsTwoViolations() {
        assertEquals(2, validator.validate(new ChangePasswordRequest("", "")).size());
    }
}