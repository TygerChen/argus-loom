package com.argus.rag.auth.model.dto;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link LoginRequest} 登录请求校验契约（Beans Validation，独立 Validator/纯 JVM、零 Spring 容器）。
 * loginId/password 均 @NotBlank：空白（含 null、空串、纯空白）必触发违规，合法输入零违规。
 */
@DisplayName("登录请求校验（LoginRequest）")
class LoginRequestTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void tearDown() {
        factory.close();
    }

    private Set<ConstraintViolation<LoginRequest>> violations(LoginRequest r) {
        return validator.validate(r);
    }

    @Test
    void validLoginHasNoViolations() {
        assertTrue(violations(new LoginRequest("user", "pass")).isEmpty());
    }

    @Test
    void blankLoginIdIsViolated() {
        assertOne("登录标识不能为空", new LoginRequest("  ", "pass"));
        assertOne("登录标识不能为空", new LoginRequest(null, "pass"));
    }

    @Test
    void blankPasswordIsViolated() {
        assertOne("密码不能为空", new LoginRequest("user", " "));
        assertOne("密码不能为空", new LoginRequest("user", null));
    }

    @Test
    void bothBlankYieldsTwoViolations() {
        assertEquals(2, violations(new LoginRequest("", "")).size());
    }

    private void assertOne(String expectedMessage, LoginRequest r) {
        Set<ConstraintViolation<LoginRequest>> vs = violations(r);
        assertEquals(1, vs.size());
        assertEquals(expectedMessage, vs.iterator().next().getMessage());
    }
}