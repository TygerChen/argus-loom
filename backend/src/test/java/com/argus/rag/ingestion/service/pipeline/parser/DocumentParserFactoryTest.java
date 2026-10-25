package com.argus.rag.ingestion.service.pipeline.parser;

import com.argus.rag.common.exception.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link DocumentParserFactory} 文档解析器工厂契约（mock {@link DocumentParser} + 真实映射表，纯 JVM/零 IO）。
 * 注册循环对 txt/md/pdf/docx 四种扩展名调用 parser.supports()，仅把支持的扩展名纳入映射（同扩展名后注册覆盖）；
 * getParser 把带点/大写的扩展名归一化（去前导点 + 小写）后查表；未知/空白扩展名抛 {@link BusinessException}。
 */
@DisplayName("文档解析器工厂（DocumentParserFactory）")
class DocumentParserFactoryTest {

    private static DocumentParser mockParser(boolean txt, boolean md, boolean pdf, boolean docx) {
        DocumentParser p = mock(DocumentParser.class);
        when(p.supports(eq("txt"))).thenReturn(txt);
        when(p.supports(eq("md"))).thenReturn(md);
        when(p.supports(eq("pdf"))).thenReturn(pdf);
        when(p.supports(eq("docx"))).thenReturn(docx);
        return p;
    }

    @Test
    void parserIsRegisteredOnlyForSupportedExtension() {
        DocumentParser txtOnly = mockParser(true, false, false, false);
        DocumentParserFactory factory = new DocumentParserFactory(List.of(txtOnly));

        assertSame(txtOnly, factory.getParser("txt"));
        assertThrows(BusinessException.class, () -> factory.getParser("md"), "md 不支持，应查表缺失");
    }

    @Test
    void lastRegisteredParserWinsForSameExtension() {
        DocumentParser first = mockParser(true, false, false, false);
        DocumentParser second = mockParser(true, false, false, false);
        DocumentParserFactory factory = new DocumentParserFactory(List.of(first, second));
        assertSame(second, factory.getParser("txt"), "同扩展名后注册覆盖先注册");
    }

    @Test
    void getParserNormalizesDotAndCase() {
        DocumentParser pdf = mockParser(false, false, true, false);
        DocumentParserFactory factory = new DocumentParserFactory(List.of(pdf));
        assertSame(pdf, factory.getParser("pdf"));
        assertSame(pdf, factory.getParser(".PDF"));
        assertSame(pdf, factory.getParser("Pdf"));
    }

    @Test
    void unsupportedExtensionThrowsWithNormalizedName() {
        DocumentParserFactory factory = new DocumentParserFactory(List.of(mockParser(true, false, false, false)));
        BusinessException ex = assertThrows(BusinessException.class, () -> factory.getParser(".XLSX"));
        assertTrue(ex.getMessage().contains("xlsx"), "应抛出含规范化小写扩展名的未支持异常");
    }

    @Test
    void blankOrNullExtensionThrows() {
        DocumentParserFactory factory = new DocumentParserFactory(List.of(mockParser(true, false, false, false)));
        assertThrows(BusinessException.class, () -> factory.getParser(" "));
        assertThrows(BusinessException.class, () -> factory.getParser(null));
    }

    @Test
    void defaultConstructorRegistersCoreHandlers() {
        DocumentParserFactory factory = new DocumentParserFactory();
        assertNotNull(factory.getParser("txt"));
        assertNotNull(factory.getParser("md"));
        assertNotNull(factory.getParser("pdf"));
        assertNotNull(factory.getParser("docx"));
        assertThrows(BusinessException.class, () -> factory.getParser("exe"));
    }

    @Test
    void unsupportedParsersNotRegistered() {
        DocumentParser none = mockParser(false, false, false, false);
        DocumentParserFactory factory = new DocumentParserFactory(List.of(none));
        for (String ext : new String[]{"txt", "md", "pdf", "docx"}) {
            assertThrows(BusinessException.class, () -> factory.getParser(ext), ext + " 不应注册");
        }
    }
}