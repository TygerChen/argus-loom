package com.argus.rag.qa.model.dto;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AskQuestionRequest} 问答请求校验契约（独立 jakarta Validator/纯 JVM/零 Spring）。
 * groupId @NotNull+@Positive（null→「groupId 不能为空」、≤0→「groupId 非法」）；
 * question @NotBlank+@Size(2000)（空白→「问题不能为空」、超长→「问题长度不能超过 2000」）。
 */
@DisplayName("知识问答请求校验（AskQuestionRequest）")
class AskQuestionRequestTest {

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

    private static String messages(AskQuestionRequest r) {
        return validator.validate(r).stream()
                .map(ConstraintViolation::getMessage)
                .collect(Collectors.joining("|"));
    }

    private static AskQuestionRequest req(Long groupId, String question) {
        AskQuestionRequest r = new AskQuestionRequest();
        r.setGroupId(groupId);
        r.setQuestion(question);
        return r;
    }

    @Test
    void validRequestHasNoViolations() {
        assertTrue(validator.validate(req(3L, "什么是销售毛利率？")).isEmpty());
    }

    @Test
    void nullGroupIdIsViolated() {
        assertTrue(messages(req(null, "问题")).contains("groupId 不能为空"));
    }

    @Test
    void nonPositiveGroupIdIsViolated() {
        assertEquals("groupId 非法", messages(req(0L, "问题")));
        assertEquals("groupId 非法", messages(req(-5L, "问题")));
    }

    @Test
    void blankQuestionIsViolated() {
        assertTrue(messages(req(1L, "  ")).contains("问题不能为空"));
        assertTrue(messages(req(1L, null)).contains("问题不能为空"));
    }

    @Test
    void oversizeQuestionIsViolated() {
        String longQ = "x".repeat(2001);
        assertTrue(messages(req(1L, longQ)).contains("问题长度不能超过 2000"));
    }

    @Test
    void bothInvalidAggregateViolations() {
        AskQuestionRequest r = req(-1L, "");
        assertTrue(validator.validate(r).size() >= 2, "groupId 非法 + 问题不能为空应同时报告");
    }

    @Test
    void maxLengthQuestionPassesSizeBound() {
        String q = "x".repeat(2000);
        assertTrue(!messages(req(1L, q)).contains("问题长度不能超过 2000"),
                "恰为 2000 字符不应超长");
    }
}