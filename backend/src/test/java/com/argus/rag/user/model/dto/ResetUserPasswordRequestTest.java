package com.argus.rag.user.model.dto;

import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ResetUserPasswordRequest} 管理员重置密码请求校验契约（独立 jakarta Validator/纯 JVM）。
 * newPassword @NotBlank：null/空白→「新密码不能为空」；合法值零违规。
 */
@DisplayName("重置用户密码请求校验（ResetUserPasswordRequest）")
class ResetUserPasswordRequestTest {

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

    @Test
    void blankNewPasswordIsViolated() {
        assertEquals("新密码不能为空",
                validator.validate(new ResetUserPasswordRequest("")).iterator().next().getMessage());
        assertEquals("新密码不能为空",
                validator.validate(new ResetUserPasswordRequest("   ")).iterator().next().getMessage());
        assertEquals("新密码不能为空",
                validator.validate(new ResetUserPasswordRequest(null)).iterator().next().getMessage());
    }

    @Test
    void validNewPasswordHasNoViolations() {
        assertTrue(validator.validate(new ResetUserPasswordRequest("strongpass")).isEmpty());
    }
}