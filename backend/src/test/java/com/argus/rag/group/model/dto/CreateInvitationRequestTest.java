package com.argus.rag.group.model.dto;

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
 * {@link CreateInvitationRequest} 创建邀请请求校验契约（独立 jakarta Validator/纯 JVM）。
 * inviteeUserId @NotNull+@Positive：null→「被邀请用户不能为空」；非正数→「被邀请用户非法」；正数零违规。
 */
@DisplayName("创建群组邀请请求校验（CreateInvitationRequest）")
class CreateInvitationRequestTest {

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

    private static Set<String> messages(CreateInvitationRequest r) {
        return validator.validate(r).stream().map(ConstraintViolation::getMessage).collect(Collectors.toSet());
    }

    @Test
    void nullInviteeIsViolated() {
        assertEquals(Set.of("被邀请用户不能为空"), messages(new CreateInvitationRequest(null)));
    }

    @Test
    void nonPositiveInviteeIsViolated() {
        assertEquals(Set.of("被邀请用户非法"), messages(new CreateInvitationRequest(0L)));
        assertEquals(Set.of("被邀请用户非法"), messages(new CreateInvitationRequest(-5L)));
    }

    @Test
    void validInviteeHasNoViolations() {
        assertTrue(validator.validate(new CreateInvitationRequest(42L)).isEmpty());
    }
}