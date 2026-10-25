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
 * {@link CreateGroupRequest} 创建群组请求校验契约（独立 jakarta Validator/纯 JVM）。
 * name @NotBlank+@Size(max=128)：空白→「组名称不能为空」，超长→「组名称不能超过 128」；
 * description @Size(max=512)：超长→「组描述不能超过 512」，null/短值放行。
 */
@DisplayName("创建群组请求校验（CreateGroupRequest）")
class CreateGroupRequestTest {

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

    private static String messages(CreateGroupRequest r) {
        Set<ConstraintViolation<CreateGroupRequest>> vs = validator.validate(r);
        return vs.stream().map(ConstraintViolation::getMessage).collect(Collectors.joining("|"));
    }

    @Test
    void validGroupHasNoViolations() {
        assertTrue(validator.validate(new CreateGroupRequest("研发组", "核心研发")).isEmpty());
    }

    @Test
    void blankNameIsViolated() {
        assertEquals("组名称不能为空", messages(new CreateGroupRequest("  ", null)));
        assertEquals("组名称不能为空", messages(new CreateGroupRequest(null, null)));
    }

    @Test
    void oversizeNameIsViolated() {
        String over = IntStream.range(0, 129).mapToObj(i -> "a").collect(Collectors.joining());
        assertTrue(messages(new CreateGroupRequest(over, null)).contains("组名称不能超过 128"));
    }

    @Test
    void oversizeDescriptionIsViolated() {
        String over = IntStream.range(0, 513).mapToObj(i -> "a").collect(Collectors.joining());
        assertTrue(messages(new CreateGroupRequest("研发组", over)).contains("组描述不能超过 512"));
    }

    @Test
    void maxLengthsNoViolations() {
        String name = IntStream.range(0, 128).mapToObj(i -> "a").collect(Collectors.joining());
        String desc = IntStream.range(0, 512).mapToObj(i -> "a").collect(Collectors.joining());
        assertTrue(validator.validate(new CreateGroupRequest(name, desc)).isEmpty());
    }
}