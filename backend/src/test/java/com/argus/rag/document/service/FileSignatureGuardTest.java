package com.argus.rag.document.service;

import com.argus.rag.common.exception.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 文件签名守卫契约：PDF/docx 签名一致放行、伪装扩展名拒绝、
 * 文本格式靠 NUL 字节启发式、空文件入口即拒。
 */
@DisplayName("文件签名守卫（FileSignatureGuard）")
class FileSignatureGuardTest {

    @Test
    void acceptsRealPdfAndDocx() {
        byte[] pdfHead = "%PDF-1.7\n...".getBytes(StandardCharsets.US_ASCII);
        assertDoesNotThrow(() -> FileSignatureGuard.verify(pdfHead, "pdf"));
        byte[] docxHead = {'P', 'K', 0x03, 0x04, 0x14, 0x00, 0x06, 0x00};
        assertDoesNotThrow(() -> FileSignatureGuard.verify(docxHead, "docx"));
    }

    @Test
    void rejectsFakeExtension() {
        byte[] exeHead = {'M', 'Z', (byte) 0x90, 0x00, 0x03, 0x00, 0x00, 0x00};
        BusinessException ex = assertThrows(BusinessException.class,
                () -> FileSignatureGuard.verify(exeHead, "pdf"), ".exe 伪装 .pdf 必须被拒");
        assertTrue(ex.getMessage().contains("不符"));
        byte[] zipHead = {'P', 'K', 0x03, 0x04, 0x00, 0x00, 0x00, 0x00};
        assertThrows(BusinessException.class, () -> FileSignatureGuard.verify(zipHead, "pdf"),
                "ZIP 容器伪装 .pdf 同样被拒（docx 才允许 PK 头）");
    }

    @Test
    void textFormatsRejectBinaryMasquerade() {
        assertDoesNotThrow(() -> FileSignatureGuard.verify("# 金蝶手册\n".getBytes(StandardCharsets.UTF_8), "md"));
        byte[] binaryHead = {0x00, 0x01, 0x02, 0x03, 0x00, 0x00, 0x00, 0x00};
        assertThrows(BusinessException.class,
                () -> FileSignatureGuard.verify(binaryHead, "txt"), "头部含 NUL 的二进制伪装 txt 必须被拒");
    }

    @Test
    void emptyContentRejectedAtEntry() {
        assertThrows(BusinessException.class, () -> FileSignatureGuard.verify(new byte[0], "pdf"));
        assertThrows(BusinessException.class, () -> FileSignatureGuard.verify(null, "md"));
    }

    @Test
    void readHeadReadsAtMostEightBytes() {
        byte[] content = "123456789ABCDEF".getBytes(StandardCharsets.US_ASCII);
        byte[] head = FileSignatureGuard.readHead(new ByteArrayInputStream(content));
        assertEqualsLength(head);
        byte[] shortContent = "123".getBytes(StandardCharsets.US_ASCII);
        assertArrayEquals(shortContent, FileSignatureGuard.readHead(new ByteArrayInputStream(shortContent)));
        byte[] empty = FileSignatureGuard.readHead(new ByteArrayInputStream(new byte[0]));
        assertTrue(empty.length == 0);
    }

    private void assertEqualsLength(byte[] head) {
        org.junit.jupiter.api.Assertions.assertEquals(8, head.length);
    }
}
