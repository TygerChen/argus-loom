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
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link CreateJoinRequestRequest} 创建入组申请请求校验契约（独立 jakarta Validator/纯 JVM）。
 * groupCode @NotBlank+@Size(max=80)：null/空白→「组织 ID 不能为空」；超长→「组织 ID 不能超过 80」。
 */
@DisplayName("创建加入群组申请请求校验（CreateJoinRequestRequest）")
class CreateJoinRequestRequestTest {

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

    private static String messages(CreateJoinRequestRequest r) {
        Set<ConstraintViolation<CreateJoinRequestRequest>> vs = validator.validate(r);
        return vs.stream().map(ConstraintViolation::getMessage).collect(Collectors.joining("|"));
    }

    @Test
    void validGroupCodeHasNoViolations() {
        assertTrue(validator.validate(new CreateJoinRequestRequest("RD-TEAM-01")).isEmpty());
    }

    @Test
    void blankGroupCodeIsViolated() {
        assertEquals("组织 ID 不能为空", messages(new CreateJoinRequestRequest("")));
        assertEquals("组织 ID 不能为空", messages(new CreateJoinRequestRequest(null)));
    }

    @Test
    void oversizeGroupCodeIsViolated() {
        String over = IntStream.range(0, 81).mapToObj(i -> "a").collect(Collectors.joining());
        assertTrue(messages(new CreateJoinRequestRequest(over)).contains("组织 ID 不能超过 80"));
    }

    @Test
    void maxLengthGroupCodeNoViolations() {
        String max = IntStream.range(0, 80).mapToObj(i -> "a").collect(Collectors.joining());
        assertTrue(validator.validate(new CreateJoinRequestRequest(max)).isEmpty());
    }
}