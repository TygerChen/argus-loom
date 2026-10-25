package com.argus.rag.finance.service;

import com.argus.rag.finance.entity.FinanceReport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@link FinanceReportPdfService} 纯逻辑方法行为锁
 * （pad / nvl / fileNameFor，纯 JVM、零文件 IO、零 PDFBox）。
 */
@DisplayName("财务简报 PDF 纯逻辑方法（FinanceReportPdfService）")
class FinanceReportPdfServiceTest {

    private static final FinanceReportPdfService SVC = new FinanceReportPdfService(null);

    // ── fileNameFor ──

    @Test
    void fileNameForIncludesReportId() {
        FinanceReport report = new FinanceReport();
        report.setId(42L);
        assertEquals("finance-report-42.pdf", SVC.fileNameFor(report));
    }

    @Test
    void fileNameForHandlesNullId() {
        FinanceReport report = new FinanceReport();
        // id 为 null 时拼接 "null"
        assertEquals("finance-report-null.pdf", SVC.fileNameFor(report));
    }

    @Test
    void fileNameForLargeId() {
        FinanceReport report = new FinanceReport();
        report.setId(999999999L);
        assertEquals("finance-report-999999999.pdf", SVC.fileNameFor(report));
    }

    // ── pad (via reflection since private) ──

    @Test
    void padAddsSpacesUpToWidth() throws Exception {
        var method = FinanceReportPdfService.class.getDeclaredMethod("pad", String.class, int.class);
        method.setAccessible(true);

        assertEquals("abc  ", method.invoke(null, "abc", 5));
        assertEquals("hello", method.invoke(null, "hello", 5));
        assertEquals("ab", method.invoke(null, "ab", 2));
    }

    @Test
    void padHandlesNull() throws Exception {
        var method = FinanceReportPdfService.class.getDeclaredMethod("pad", String.class, int.class);
        method.setAccessible(true);

        assertEquals("     ", method.invoke(null, null, 5));
    }

    @Test
    void padHandlesEmptyString() throws Exception {
        var method = FinanceReportPdfService.class.getDeclaredMethod("pad", String.class, int.class);
        method.setAccessible(true);

        assertEquals("   ", method.invoke(null, "", 3));
    }

    @Test
    void padZeroWidthReturnsOriginal() throws Exception {
        var method = FinanceReportPdfService.class.getDeclaredMethod("pad", String.class, int.class);
        method.setAccessible(true);

        assertEquals("abc", method.invoke(null, "abc", 0));
    }

    // ── nvl (via reflection since private) ──

    @Test
    void nvlReturnsEmptyForNull() throws Exception {
        var method = FinanceReportPdfService.class.getDeclaredMethod("nvl", Object.class);
        method.setAccessible(true);

        assertEquals("", method.invoke(SVC, (Object) null));
    }

    @Test
    void nvlReturnsStringValue() throws Exception {
        var method = FinanceReportPdfService.class.getDeclaredMethod("nvl", Object.class);
        method.setAccessible(true);

        assertEquals("hello", method.invoke(SVC, "hello"));
        assertEquals("42", method.invoke(SVC, 42));
        assertEquals("3.14", method.invoke(SVC, 3.14));
    }
}
