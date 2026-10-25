package com.argus.rag.user.model.dto;

import com.argus.rag.common.enums.SystemRole;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link UpdateUserRoleRequest} 管理员改角色请求校验契约（独立 jakarta Validator/纯 JVM）。
 * systemRole @NotNull：null→「系统角色不能为空」；合法角色零违规。
 */
@DisplayName("更新用户角色请求（UpdateUserRoleRequest）")
class UpdateUserRoleRequestTest {

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
    void nullRoleIsViolated() {
        assertEquals("系统角色不能为空",
                validator.validate(new UpdateUserRoleRequest(null)).iterator().next().getMessage());
    }

    @Test
    void validRoleHasNoViolations() {
        assertTrue(validator.validate(new UpdateUserRoleRequest(SystemRole.ADMIN)).isEmpty());
    }
}