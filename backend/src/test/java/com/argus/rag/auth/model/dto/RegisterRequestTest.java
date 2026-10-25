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
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link RegisterRequest} 注册请求校验契约（独立 jakarta Validator/纯 JVM/零 Spring）。
 * username/email/displayName/password 各自带 @NotBlank + @Size，email 另带 @Email：
 * 逐字段覆盖空值/超长/格式非法，并以多违规聚合验证跨字段约束并存。
 */
@DisplayName("注册请求校验（RegisterRequest）")
class RegisterRequestTest {

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

    private static String messages(RegisterRequest r) {
        return validator.validate(r).stream()
                .map(ConstraintViolation::getMessage)
                .collect(Collectors.joining("|"));
    }

    private static String s(int len) {
        return "x".repeat(len);
    }

    @Test
    void validRegistrationHasNoViolations() {
        RegisterRequest r = new RegisterRequest("alice", "alice@example.com", "Alice", "pwd123");
        assertTrue(validator.validate(r).isEmpty());
    }

    @Test
    void blankUsernameIsViolated() {
        assertEquals("用户名不能为空", messages(new RegisterRequest(null, "a@b.c", "A", "p")));
        assertEquals("用户名不能为空", messages(new RegisterRequest("   ", "a@b.c", "A", "p")));
    }

    @Test
    void oversizeUsernameIsViolated() {
        assertTrue(messages(new RegisterRequest(s(65), "a@b.c", "A", "p")).contains("用户名长度不能超过 64"));
    }

    @Test
    void invalidEmailFormatIsViolated() {
        assertEquals("邮箱格式非法", messages(new RegisterRequest("a", "not-an-email", "A", "p")));
    }

    @Test
    void blankEmailIsViolated() {
        assertEquals("邮箱不能为空", messages(new RegisterRequest("a", "", "A", "p")));
    }

    @Test
    void oversizeEmailIsViolated() {
        assertTrue(messages(new RegisterRequest("a", s(129) + "@x.com", "A", "p")).contains("邮箱长度不能超过 128"));
    }

    @Test
    void blankAndOversizeDisplayNameAreViolated() {
        assertEquals("显示名称不能为空", messages(new RegisterRequest("a", "a@b.c", " ", "p")));
        assertTrue(messages(new RegisterRequest("a", "a@b.c", s(129), "p")).contains("显示名称长度不能超过 128"));
    }

    @Test
    void blankAndOversizePasswordAreViolated() {
        assertEquals("密码不能为空", messages(new RegisterRequest("a", "a@b.c", "A", "")));
        assertTrue(messages(new RegisterRequest("a", "a@b.c", "A", s(257))).contains("密码长度非法"));
    }

    @Test
    void multipleBadFieldsAggregateViolations() {
        Set<ConstraintViolation<RegisterRequest>> vs = validator.validate(
                new RegisterRequest(null, "bad", "", ""));
        assertTrue(vs.size() >= 3, "多字段违规应同时报告: " + vs);
    }
}