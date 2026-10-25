package com.argus.rag.document.service;

import com.argus.rag.common.exception.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link UploadInputGuard} 契约测试。
 *
 * <p>这些纯函数从 {@code DocumentUploadService} 下沉而来，本测试锁住它们的
 * 现有行为（正常路径 + 越界 + 非法输入的异常），确保重构不改变对外语义。</p>
 */
@DisplayName("文档上传入参校验/规范化（UploadInputGuard）")
class UploadInputGuardTest {

    // ── sanitizeFileName ──────────────────────────────────────────────
    @Test
    void sanitizeShouldDropPathAndParentTraversal() {
        assertEquals("report.pdf", UploadInputGuard.sanitizeFileName("/app/uploads/report.pdf"));
        assertEquals("report.pdf", UploadInputGuard.sanitizeFileName("../report.pdf"));
        assertEquals("report.pdf", UploadInputGuard.sanitizeFileName("data/../report.pdf"));
    }

    @Test
    void sanitizeShouldTrimWhitespace() {
        assertEquals("report.pdf", UploadInputGuard.sanitizeFileName("  report.pdf  "));
    }

    @Test
    void sanitizeShouldRejectBlankOrOverlong() {
        assertThrows(BusinessException.class, () -> UploadInputGuard.sanitizeFileName("  "));
        assertThrows(BusinessException.class, () -> UploadInputGuard.sanitizeFileName(null));
        String longName = "a".repeat(256) + ".pdf";
        assertThrows(BusinessException.class, () -> UploadInputGuard.sanitizeFileName(longName));
    }

    // ── extractFileExt ───────────────────────────────────────────────
    @Test
    void extractShouldLowercaseAndAcquireSupportedExt() {
        assertEquals("pdf", UploadInputGuard.extractFileExt("report.PDF"));
        assertEquals("docx", UploadInputGuard.extractFileExt("doc.docx"));
        assertEquals("md", UploadInputGuard.extractFileExt("README.md"));
        assertEquals("txt", UploadInputGuard.extractFileExt("notes.txt"));
    }

    @Test
    void extractShouldRejectUnsupportedOrMalformed() {
        assertThrows(BusinessException.class, () -> UploadInputGuard.extractFileExt("exec.exe"));
        assertThrows(BusinessException.class, () -> UploadInputGuard.extractFileExt("noext"));
        assertThrows(BusinessException.class, () -> UploadInputGuard.extractFileExt("dot."));
    }

    // ── normalizeContentType ──────────────────────────────────────────
    @Test
    void contentTypeShouldFallbackToOctetStreamWhenBlank() {
        assertEquals("application/octet-stream", UploadInputGuard.normalizeContentType("  "));
        assertEquals("application/octet-stream", UploadInputGuard.normalizeContentType(null));
    }

    @Test
    void contentTypeShouldTrimAndRejectOverlong() {
        assertEquals("text/csv", UploadInputGuard.normalizeContentType("  text/csv  "));
        assertThrows(BusinessException.class,
                () -> UploadInputGuard.normalizeContentType("a".repeat(129)));
    }

    // ── normalizeFileHash ─────────────────────────────────────────────
    @Test
    void fileHashShouldTrimAndRejectBlank() {
        assertEquals("abc123", UploadInputGuard.normalizeFileHash("  abc123  "));
        assertThrows(BusinessException.class, () -> UploadInputGuard.normalizeFileHash(""));
        assertThrows(BusinessException.class, () -> UploadInputGuard.normalizeFileHash(null));
    }

    // ── requirePositive ──────────────────────────────────────────────
    @Test
    void requirePositiveShouldAcceptPositiveAndRejectNonPositive() {
        assertEquals(1L, UploadInputGuard.requirePositive(1L, "x 非法"));
        assertEquals(1, UploadInputGuard.requirePositive(1, "x 非法"));
        assertThrows(BusinessException.class, () -> UploadInputGuard.requirePositive(0L, "x 非法"));
        assertThrows(BusinessException.class, () -> UploadInputGuard.requirePositive(-5, "x 非法"));
        assertThrows(BusinessException.class, () -> UploadInputGuard.requirePositive((Long) null, "x 非法"));
    }

    @Test
    void requirePositiveUserIdAndFileSizeAdhereToSameRule() {
        assertEquals(42L, UploadInputGuard.requirePositiveUserId(42L));
        assertEquals(10L, UploadInputGuard.requirePositiveFileSize(10L));
        assertThrows(BusinessException.class, () -> UploadInputGuard.requirePositiveUserId(0L));
        assertThrows(BusinessException.class, () -> UploadInputGuard.requirePositiveFileSize(null));
    }

    // ── requireText ───────────────────────────────────────────────────
    @Test
    void requireTextShouldTrimAndRejectBlank() {
        assertEquals("bucket-a", UploadInputGuard.requireText(" bucket-a ", "桶非法"));
        assertThrows(BusinessException.class, () -> UploadInputGuard.requireText("  ", "桶非法"));
        assertThrows(BusinessException.class, () -> UploadInputGuard.requireText(null, "桶非法"));
    }

    @Test
    void validateReusableFileNameDelegatesToSanitize() {
        assertEquals("reuse.pdf", UploadInputGuard.validateReusableFileName("../reuse.pdf"));
    }
}