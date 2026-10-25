package com.argus.rag.ingestion.service.pipeline.parser;

import com.argus.rag.common.exception.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link TextDecodingSupport} 自动编码检测与解码契约（纯 JVM/零 IO 依赖/字节流直测）。
 * BOM（UTF-8 / UTF-16LE / UTF-16BE）判别解码、无 BOM 按 UTF-8 → GB18030 回退、
 * 全部失败或读流异常统一包装 {@link BusinessException}(failureMessage)。
 */
@DisplayName("文本编码检测解码（TextDecodingSupport）")
class TextDecodingSupportTest {

    private static final byte[] UTF8_BOM = new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
    private static final byte[] UTF16LE_BOM = new byte[]{(byte) 0xFF, (byte) 0xFE};
    private static final byte[] UTF16BE_BOM = new byte[]{(byte) 0xFE, (byte) 0xFF};

    private String decode(byte[] bytes) {
        return TextDecodingSupport.decode(new ByteArrayInputStream(bytes), "解码失败");
    }

    @Test
    void utf8BomSkipsBomAndDecodes() {
        byte[] data = join(UTF8_BOM, "Hello".getBytes(StandardCharsets.UTF_8));
        assertEquals("Hello", decode(data));
    }

    @Test
    void utf16LeBomSkipsBomAndDecodes() {
        byte[] data = join(UTF16LE_BOM, "Hi".getBytes(StandardCharsets.UTF_16LE));
        assertEquals("Hi", decode(data));
    }

    @Test
    void utf16BeBomSkipsBomAndDecodes() {
        byte[] data = join(UTF16BE_BOM, "Hi".getBytes(StandardCharsets.UTF_16BE));
        assertEquals("Hi", decode(data));
    }

    @Test
    void noBomUtf8BytesDecode() {
        assertEquals("中文", decode("中文".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void noBomFallsBackToGb18030ForChineseBytes() {
        String gb = "中文";
        byte[] gbkBytes = gb.getBytes(Charset.forName("GB18030"));
        // GBK/GB18030 双字节不是合法 UTF-8 序列，应回退到 GB18030 成功解码
        assertEquals(gb, decode(gbkBytes));
    }

    @Test
    void undecodableBytesFailJsWithBusinessException() {
        byte[] garbage = new byte[]{(byte) 0xC3, (byte) 0x28, (byte) 0xFF};
        BusinessException ex = assertThrows(BusinessException.class, () -> decode(garbage));
        assertEquals("解码失败", ex.getMessage());
    }

    @Test
    void readFailureIsWrappedInBusinessException() throws Exception {
        InputStream broken = new InputStream() {
            @Override
            public int read() throws IOException {
                throw new IOException("stream closed");
            }
        };
        BusinessException ex = assertThrows(BusinessException.class,
                () -> TextDecodingSupport.decode(broken, "读取失败"));
        assertEquals("读取失败", ex.getMessage());
    }

    private static byte[] join(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }
}