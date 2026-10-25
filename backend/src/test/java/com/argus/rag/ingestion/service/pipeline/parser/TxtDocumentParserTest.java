package com.argus.rag.ingestion.service.pipeline.parser;

import com.argus.rag.common.exception.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link TxtDocumentParser} TXT 纯文本文档解析契约（纯 JVM/字节流直测）。
 * supports 大小写不敏感；parse 经 {@link TextDecodingSupport} 解码后统一换行符并去首尾空白；
 * 解码失败抛 BusinessException("TXT 文档解析失败")。
 */
@DisplayName("TXT 解析器（TxtDocumentParser）")
class TxtDocumentParserTest {

    private final TxtDocumentParser parser = new TxtDocumentParser();

    @Test
    void supportsTxtCaseInsensitively() {
        assertTrue(parser.supports("txt"));
        assertTrue(parser.supports("TXT"));
        assertTrue(parser.supports("Txt"));
        assertFalse(parser.supports("md"));
        assertFalse(parser.supports("pdf"));
        assertFalse(parser.supports(null));
    }

    @Test
    void parseNormalizesNewlinesAndTrims() {
        byte[] bytes = "a\r\nb\rc\n   ".getBytes(StandardCharsets.UTF_8);
        assertEquals("a\nb\nc", parser.parse(new ByteArrayInputStream(bytes)));
    }

    @Test
    void parseDecodesUtf8BomAndTrims() {
        byte[] bom = new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
        assertEquals("abc", parser.parse(new ByteArrayInputStream(join(bom, "abc".getBytes(StandardCharsets.UTF_8)))));
    }

    @Test
    void parseUndecodableBytesThrowsBusinessException() {
        byte[] garbage = new byte[]{(byte) 0xFF, (byte) 0xFF, (byte) 0xC3, (byte) 0x28};
        BusinessException ex = assertThrows(BusinessException.class,
                () -> parser.parse(new ByteArrayInputStream(garbage)));
        assertEquals("TXT 文档解析失败", ex.getMessage());
    }

    private static byte[] join(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }
}