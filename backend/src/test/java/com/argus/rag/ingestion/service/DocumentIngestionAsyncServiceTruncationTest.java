package com.argus.rag.ingestion.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link DocumentIngestionAsyncService#truncateFailureReason(String)} 失败原因截断行为锁
 * （纯 JVM、静态同包直调、零构造、零 IO）。
 * <p>锁契约：null/空白 → 默认「文档处理失败」；长度 ≤512 → 原样；&gt;512 → 取前 512 字。</p>
 */
@DisplayName("失败原因截断（DocumentIngestionAsyncService.truncateFailureReason）")
class DocumentIngestionAsyncServiceTruncationTest {

    @Test
    void nullOrBlankFallsBackToDefault() {
        assertEquals("文档处理失败", DocumentIngestionAsyncService.truncateFailureReason(null));
        assertEquals("文档处理失败", DocumentIngestionAsyncService.truncateFailureReason(""));
        assertEquals("文档处理失败", DocumentIngestionAsyncService.truncateFailureReason("   "));
    }

    @Test
    void preservesShortText() {
        assertEquals("ok", DocumentIngestionAsyncService.truncateFailureReason("ok"));
    }

    @Test
    void preservesExactlyMaxLength() {
        String s = "a".repeat(512);
        assertEquals(s, DocumentIngestionAsyncService.truncateFailureReason(s));
    }

    @Test
    void truncatesOverMaxToFirst512() {
        String s = "a".repeat(513);
        assertEquals("a".repeat(512), DocumentIngestionAsyncService.truncateFailureReason(s));
    }

    @Test
    void truncatesLongErrorMessage() {
        String s = "错误详情".repeat(200); // 600 chars
        String out = DocumentIngestionAsyncService.truncateFailureReason(s);
        assertEquals(512, out.length());
        // 截断切在多字节中文字符中间不抛（substring 字节安全；此处每字符 3 字节但按 char 计数）
    }
}