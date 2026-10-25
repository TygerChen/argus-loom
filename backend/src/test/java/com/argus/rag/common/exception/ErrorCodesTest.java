package com.argus.rag.common.exception;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.HashSet;
import java.util.Set;

import org.springframework.http.HttpStatus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ErrorCodes} 稳定业务错误码总线契约（纯 JVM / 反射）。
 * 不可实例化（private ctor）；所有 String 常量非空白、无重复、统一 UPPER_SNAKE（文案常量除外）；
 * 抽查关键码与文案字面量，防前端结构化识别漂移。
 */
@DisplayName("业务错误码总线（ErrorCodes）")
class ErrorCodesTest {

    @Test
    void cannotBeInstantiated() {
        assertThrows(IllegalAccessException.class, () -> {
            Constructor<ErrorCodes> c = ErrorCodes.class.getDeclaredConstructor();
            assertTrue(Modifier.isPrivate(c.getModifiers()));
            c.newInstance();
        });
    }

    @Test
    void allConstantsNonBlankAndUnique() throws Exception {
        Set<String> seen = new HashSet<>();
        for (Field f : ErrorCodes.class.getDeclaredFields()) {
            if (!Modifier.isStatic(f.getModifiers()) || !Modifier.isFinal(f.getModifiers())) {
                continue;
            }
            String val = (String) f.get(null);
            assertFalse(val == null || val.isBlank(), "ErrorCodes 不该含空字符串常量: " + f.getName());
            String clean = val.trim();
            assertEquals(val, clean, "常量前后不应有空白: " + f.getName());
            assertTrue(seen.add(val), "错误码重复: " + val);
        }
    }

    @Test
    void codeConstantsFollowUpperSnake() throws Exception {
        for (Field f : ErrorCodes.class.getDeclaredFields()) {
            if (f.getName().endsWith("_MESSAGE")) {
                continue; // 用户可读文案，非码
            }
            String val = (String) f.get(null);
            boolean upperSnake = val.matches("[A-Z][A-Z0-9_]*(_[A-Z0-9_]+)*");
            assertTrue(upperSnake, "错误码应 UPPER_SNAKE: " + val);
        }
    }

    @Test
    void modelUnavailableCodeAndMessageExact() {
        assertEquals("MODEL_UNAVAILABLE", ErrorCodes.MODEL_UNAVAILABLE);
        assertEquals("模型服务暂时不可用，请稍后重试。", ErrorCodes.MODEL_UNAVAILABLE_MESSAGE);
    }

    @Test
    void financeIsolationCodeExact() {
        assertEquals("EXCEL_FINANCE_FORBIDDEN", ErrorCodes.EXCEL_FINANCE_FORBIDDEN);
        assertEquals("FINANCE_DATA_EMPTY", ErrorCodes.FINANCE_DATA_EMPTY);
        assertEquals("FINANCE_MONTH_FORMAT_INVALID", ErrorCodes.FINANCE_MONTH_FORMAT_INVALID);
    }

    @Test
    void authIdentityCodesExact() {
        assertEquals("AUTH_TOKEN_REPLAYED", ErrorCodes.AUTH_TOKEN_REPLAYED);
        assertEquals("AUTH_CREDENTIALS_INVALID", ErrorCodes.AUTH_CREDENTIALS_INVALID);
        assertEquals("AUTH_IDENTITY_EXISTS", ErrorCodes.AUTH_IDENTITY_EXISTS);
    }

    @Test
    void concurrencyAndGroupPins() {
        assertEquals("CONCURRENT_MODIFIED", ErrorCodes.CONCURRENT_MODIFIED);
        assertEquals("GROUP_INVALID_TARGET", ErrorCodes.GROUP_INVALID_TARGET);
        assertEquals("GROUP_REQUEST_PENDING", ErrorCodes.GROUP_REQUEST_PENDING);
    }

    // ── P2-09: httpStatus 映射 ──

    @Test
    void httpStatusNullCodeReturnsBadRequest() {
        assertEquals(HttpStatus.BAD_REQUEST, ErrorCodes.httpStatus(null));
    }

    @Test
    void httpStatusUnknownCodeReturnsBadRequest() {
        assertEquals(HttpStatus.BAD_REQUEST, ErrorCodes.httpStatus("UNKNOWN_CODE"));
    }

    @Test
    void httpStatusAuthTokensReturn401() {
        assertEquals(HttpStatus.UNAUTHORIZED, ErrorCodes.httpStatus(ErrorCodes.AUTH_TOKEN_INVALID));
        assertEquals(HttpStatus.UNAUTHORIZED, ErrorCodes.httpStatus(ErrorCodes.AUTH_TOKEN_REPLAYED));
        assertEquals(HttpStatus.UNAUTHORIZED, ErrorCodes.httpStatus(ErrorCodes.AUTH_CREDENTIALS_INVALID));
    }

    @Test
    void httpStatusForbiddenReturns403() {
        assertEquals(HttpStatus.FORBIDDEN, ErrorCodes.httpStatus(ErrorCodes.PERMISSION_DENIED));
        assertEquals(HttpStatus.FORBIDDEN, ErrorCodes.httpStatus(ErrorCodes.EXCEL_FINANCE_FORBIDDEN));
        assertEquals(HttpStatus.FORBIDDEN, ErrorCodes.httpStatus(ErrorCodes.GROUP_ACTION_FORBIDDEN));
    }

    @Test
    void httpStatusNotFoundReturns404() {
        assertEquals(HttpStatus.NOT_FOUND, ErrorCodes.httpStatus(ErrorCodes.EXCEL_NOT_FOUND));
        assertEquals(HttpStatus.NOT_FOUND, ErrorCodes.httpStatus(ErrorCodes.DOCUMENT_NOT_FOUND));
        assertEquals(HttpStatus.NOT_FOUND, ErrorCodes.httpStatus(ErrorCodes.USER_NOT_FOUND));
        assertEquals(HttpStatus.NOT_FOUND, ErrorCodes.httpStatus(ErrorCodes.GROUP_NOT_FOUND));
    }

    @Test
    void httpStatusConflictReturns409() {
        assertEquals(HttpStatus.CONFLICT, ErrorCodes.httpStatus(ErrorCodes.CONCURRENT_MODIFIED));
        assertEquals(HttpStatus.CONFLICT, ErrorCodes.httpStatus(ErrorCodes.EXCEL_TRASH_STATE));
        assertEquals(HttpStatus.CONFLICT, ErrorCodes.httpStatus(ErrorCodes.GROUP_ALREADY_HANDLED));
        assertEquals(HttpStatus.CONFLICT, ErrorCodes.httpStatus(ErrorCodes.AUTH_IDENTITY_EXISTS));
    }

    @Test
    void httpStatusServiceUnavailableReturns503() {
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, ErrorCodes.httpStatus(ErrorCodes.MODEL_UNAVAILABLE));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, ErrorCodes.httpStatus(ErrorCodes.FINANCE_AI_REPORT_FAILED));
    }

    @Test
    void httpStatusInternalErrorReturns500() {
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, ErrorCodes.httpStatus(ErrorCodes.FINANCE_PDF_FILE_FAILED));
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, ErrorCodes.httpStatus(ErrorCodes.DOCUMENT_PROCESSING_FAILED));
    }

    @Test
    void httpStatusValidationCodesReturn400() {
        assertEquals(HttpStatus.BAD_REQUEST, ErrorCodes.httpStatus(ErrorCodes.EXCEL_PARSE_ERROR));
        assertEquals(HttpStatus.BAD_REQUEST, ErrorCodes.httpStatus(ErrorCodes.FINANCE_MONTH_FORMAT_INVALID));
        assertEquals(HttpStatus.BAD_REQUEST, ErrorCodes.httpStatus(ErrorCodes.UPLOAD_INCOMPLETE));
    }
}