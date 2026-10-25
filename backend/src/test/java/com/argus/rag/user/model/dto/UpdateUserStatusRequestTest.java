package com.argus.rag.user.model.dto;

import com.argus.rag.common.enums.UserStatus;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link UpdateUserStatusRequest} 修改用户状态请求校验契约（独立 jakarta Validator/纯 JVM）。
 * status @NotNull：null→「用户状态不能为空」；合法状态零违规。
 */
@DisplayName("更新用户状态请求（UpdateUserStatusRequest）")
class UpdateUserStatusRequestTest {

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
    void nullStatusIsViolated() {
        assertEquals("用户状态不能为空",
                validator.validate(new UpdateUserStatusRequest(null)).iterator().next().getMessage());
    }

    @Test
    void validStatusHasNoViolations() {
        assertTrue(validator.validate(new UpdateUserStatusRequest(UserStatus.DISABLED)).isEmpty());
    }
}