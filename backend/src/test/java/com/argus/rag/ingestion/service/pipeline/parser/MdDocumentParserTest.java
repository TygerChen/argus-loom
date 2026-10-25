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
 * {@link MdDocumentParser} Markdown 文档解析契约（纯 JVM/字节流直测）。
 * supports 大小写不敏感；parse 剥 Markdown 语法：标题/强调/行内代码/链接/图片/引述/两类列表标记移除，
 * 保留链接文字与行内代码内容，压缩空白；解码失败抛 BusinessException("Markdown 文档解析失败").
 */
@DisplayName("Markdown 解析器（MdDocumentParser）")
class MdDocumentParserTest {

    private final MdDocumentParser parser = new MdDocumentParser();

    private String parseMarkdown(String markdown) {
        return parser.parse(new ByteArrayInputStream(markdown.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void supportsMdCaseInsensitively() {
        assertTrue(parser.supports("md"));
        assertTrue(parser.supports("MD"));
        assertFalse(parser.supports("txt"));
        assertFalse(parser.supports(null));
    }

    @Test
    void stripsHeadingAndEmphasis() {
        assertEquals("标题 加粗", parseMarkdown("# 标题 **加粗**"));
    }

    @Test
    void keepsLinkTextAndInlineCode() {
        assertEquals("home 教程", parseMarkdown("[home](https://x.com) `教程`"));
    }

    @Test
    void removesImageAndKeepsEmpty() {
        assertEquals("", parseMarkdown("![alt](img.png)"));
    }

    @Test
    void stripsQuoteAndLists() {
        // 引述/两类列表标记移除；单行 \n 保留（仅 \n{3,} 折叠）
        assertEquals("引文\n甲\n乙", parseMarkdown("> 引文\n- 甲\n1. 乙"));
    }

    @Test
    void stripsStrikethroughAndCollapsesBlankLines() {
        assertEquals("勘误", parseMarkdown("~~勘误~~"));
        assertEquals("a\n\nb", parseMarkdown("a\n\n\n\n\nb"));
    }

    @Test
    void parseUndecodableBytesThrowsBusinessException() {
        byte[] garbage = new byte[]{(byte) 0xFF, (byte) 0xFF, (byte) 0xC3, (byte) 0x28};
        BusinessException ex = assertThrows(BusinessException.class,
                () -> parser.parse(new ByteArrayInputStream(garbage)));
        assertEquals("Markdown 文档解析失败", ex.getMessage());
    }
}