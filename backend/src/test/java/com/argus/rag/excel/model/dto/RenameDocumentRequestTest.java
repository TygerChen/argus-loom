package com.argus.rag.excel.model.dto;

import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link RenameDocumentRequest} 重命名文档请求校验契约（独立 jakarta Validator/纯 JVM）。
 * name @NotBlank：null/空白→「名称不能为空」；合法值零违规。
 */
@DisplayName("重命名文档请求校验（RenameDocumentRequest）")
class RenameDocumentRequestTest {

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
    void blankNameIsViolated() {
        assertEquals("名称不能为空",
                validator.validate(new RenameDocumentRequest("")).iterator().next().getMessage());
        assertEquals("名称不能为空",
                validator.validate(new RenameDocumentRequest("  ")).iterator().next().getMessage());
        assertEquals("名称不能为空",
                validator.validate(new RenameDocumentRequest(null)).iterator().next().getMessage());
    }

    @Test
    void validNameHasNoViolations() {
        assertTrue(validator.validate(new RenameDocumentRequest("2024 年报")).isEmpty());
    }
}