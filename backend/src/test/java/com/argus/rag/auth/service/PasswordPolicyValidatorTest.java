package com.argus.rag.auth.service;

import com.argus.rag.common.exception.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link PasswordPolicyValidator} 策略契约：长度下限、字母+数字组合、BCrypt 字节上限。
 * 纯 JVM，无框架依赖。改动校验语义（阈值/文案/边界）即红。
 */
@DisplayName("密码策略校验（PasswordPolicyValidator）")
class PasswordPolicyValidatorTest {

    private final PasswordPolicyValidator validator = new PasswordPolicyValidator();

    private BusinessException expectInvalid(String password) {
        return assertThrows(BusinessException.class, () -> validator.validate(password));
    }

    @Test
    void rejectsNull() {
        assertEquals(PasswordPolicyValidator.INVALID_MESSAGE, expectInvalid(null).getMessage());
    }

    @Test
    void rejectsTooShortEvenWithLetterAndDigit() {
        assertEquals(PasswordPolicyValidator.INVALID_MESSAGE, expectInvalid("a1b2c3d").getMessage());
    }

    @Test
    void rejectsAllLettersNoDigit() {
        assertEquals(PasswordPolicyValidator.INVALID_MESSAGE, expectInvalid("abcdefghij").getMessage());
    }

    @Test
    void rejectsAllDigitsNoLetter() {
        assertEquals(PasswordPolicyValidator.INVALID_MESSAGE, expectInvalid("1234567890").getMessage());
    }

    @Test
    void rejectsLengthAboveLimit() {
        assertEquals("密码长度非法",
                expectInvalid("a1" + "x".repeat(300)).getMessage());
    }

    @Test
    void rejectsExceedingBcryptByteLimit() {
        assertEquals("密码长度超过安全上限，请控制在 72 字节以内",
                expectInvalid("ab12cd34ef".repeat(10)).getMessage()); // 100 字符 > 72 字节
    }

    @Test
    void acceptsValidPassword() {
        assertDoesNotThrow(() -> validator.validate("Abcdef12345!"));
    }
}