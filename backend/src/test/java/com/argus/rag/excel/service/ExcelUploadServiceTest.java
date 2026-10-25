package com.argus.rag.excel.service;

import com.argus.rag.auth.CurrentUserService;
import com.argus.rag.common.enums.SystemRole;
import com.argus.rag.common.exception.BusinessException;
import com.argus.rag.common.exception.ErrorCodes;
import com.argus.rag.excel.entity.ExcelDocument;
import com.argus.rag.excel.entity.ExcelSheet;
import com.argus.rag.excel.entity.ExcelSheetChunk;
import com.argus.rag.excel.mapper.ExcelSheetChunkMapper;
import com.argus.rag.excel.mapper.ExcelSheetMapper;
import com.argus.rag.excel.model.vo.UploadResultVO;
import com.argus.rag.excel.service.StreamingParserService.ParsedSheet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ExcelUploadService} 行为锁（纯 mock，无 DB/POI 真实解析）：
 * 校验白名单 / 空文件 / 路径净化 / 大小路由 / 失败清理 / chunk 切块。
 * 每次用 {@link #newService()} 获得带 tempDir 与 id 回填的实例。
 */
@DisplayName("Excel 上传编排（ExcelUploadService）")
class ExcelUploadServiceTest {

    private static final long NEW_DOC_ID = 88L;

    private final ExcelParserService parser = mock(ExcelParserService.class);
    private final StreamingParserService streaming = mock(StreamingParserService.class);
    private final ExcelDocumentService docService = mock(ExcelDocumentService.class);
    private final ExcelSheetMapper sheetMapper = mock(ExcelSheetMapper.class);
    private final ExcelSheetChunkMapper chunkMapper = mock(ExcelSheetChunkMapper.class);
    private final CurrentUserService currentUser = mock(CurrentUserService.class);

    @TempDir
    Path tempDir;

    /** 构造 service，注入 tempDir 作为 uploadPath，并 stub 当前用户 + create 回填 id。 */
    private ExcelUploadService newService() {
        when(currentUser.getRequiredCurrentUser())
                .thenReturn(new CurrentUserService.CurrentUser(7L, "T700001", "测试员", SystemRole.USER, false));
        doAnswer(inv -> {
            ExcelDocument d = inv.getArgument(0);
            d.setId(NEW_DOC_ID);
            return d;
        }).when(docService).create(any(ExcelDocument.class));

        ExcelUploadService s = new ExcelUploadService(parser, streaming, docService, sheetMapper, chunkMapper, currentUser);
        ReflectionTestUtils.setField(s, "uploadPath", tempDir.toString());
        return s;
    }

    // ── 扩展名白名单 ──

    @Test
    void allowsXlsxAndXlsCaseInsensitive() {
        assertTrue(ExcelUploadService.isAllowedExtension("report.xlsx"));
        assertTrue(ExcelUploadService.isAllowedExtension("REPORT.XLS"));
        assertTrue(ExcelUploadService.isAllowedExtension("夏季报表.xlsx"));
    }

    @Test
    void rejectsUnsupportedExtension() {
        assertFalse(ExcelUploadService.isAllowedExtension("a.csv"));
        assertFalse(ExcelUploadService.isAllowedExtension("a.txt"));
        assertFalse(ExcelUploadService.isAllowedExtension("a.doc"));
        assertFalse(ExcelUploadService.isAllowedExtension("e.e.xlsx.txt"));
    }

    @Test
    void rejectsNullOrBlankOrMissingExtension() {
        assertFalse(ExcelUploadService.isAllowedExtension(null));
        assertFalse(ExcelUploadService.isAllowedExtension("   "));
        assertFalse(ExcelUploadService.isAllowedExtension("noext"));
        assertFalse(ExcelUploadService.isAllowedExtension("noext."));
    }

    // ── 空文件 ──

    @Test
    @DisplayName("空文件被拒绝且不触碰主记录")
    void emptyFileIsRejected() {
        ExcelUploadService s = newService();
        MockMultipartFile file = new MockMultipartFile("file", "a.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", new byte[0]);
        BusinessException ex = assertThrows(BusinessException.class, () -> s.upload(file));
        assertEquals(ErrorCodes.EXCEL_PARSE_ERROR, ex.getCode());
        assertTrue(ex.getMessage().contains("不能为空"));
        verify(docService, never()).create(any());
    }

    // ── 文件名净化 ──

    @Test
    void sanitizeDisplayNameStripsPath() {
        assertEquals("a.xlsx", ExcelUploadService.sanitizeDisplayName("a.xlsx"));
        assertEquals("passwd.xlsx", ExcelUploadService.sanitizeDisplayName("../../etc/passwd.xlsx"));
        assertEquals("report.xlsx", ExcelUploadService.sanitizeDisplayName("/some/dir/report.xlsx"));
        assertEquals("报表.xlsx", ExcelUploadService.sanitizeDisplayName("报表.xlsx"));
    }

    @Test
    void storedNameIsUuidPlusExtension() {
        String name = ExcelUploadService.buildStoredName("xlsx");
        assertTrue(name.matches("[0-9a-fA-F-]{36}\\.xlsx"));
        assertFalse(name.contains("/"));
        assertFalse(name.contains(".."));
        assertTrue(ExcelUploadService.buildStoredName("XLS").endsWith(".xls"));
    }

    // ── 大小路由 ──

    @Test
    @DisplayName("不超过 50MB 走普通解析")
    void normalFileRoutesToParser() throws Exception {
        ExcelUploadService s = newService();
        MockMultipartFile file = new MockMultipartFile("file", "normal.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", new byte[4096]);

        ExcelSheet sheet = new ExcelSheet();
        sheet.setId(11L);
        sheet.setSheetName("S1");
        sheet.setTotalRows(2);
        sheet.setTotalCols(2);
        sheet.setActive(1);
        sheet.setChunkCount(1);
        when(parser.parseAndSave(any(), any())).thenReturn(List.of(sheet));

        UploadResultVO result = s.upload(file);

        verify(parser, times(1)).parseAndSave(any(), any());
        verify(streaming, never()).parseStreaming(any());
        verify(docService).updateSheetMeta(eq(NEW_DOC_ID), eq(1), any());
        assertEquals("normal.xlsx", result.name());
        assertEquals(1, result.sheetCount());
    }

    @Test
    @DisplayName("大于 50MB 走流式解析并落库")
    void largeFileRoutesToStreaming() throws Exception {
        ExcelUploadService s = newService();
        MultipartFile file = multipartWithSize("big.xlsx", ExcelUploadService.LARGE_FILE_THRESHOLD + 1);

        ParsedSheet ps0 = new ParsedSheet();
        ps0.sheetName = "S1";
        ps0.rows = rowsOf(Map.of(
                0, Map.of(0, "a"),
                1, Map.of(1, "b"),
                1500, Map.of(0, "c")));
        ps0.colCount = 3;
        ps0.mergeConfig = null;
        ps0.columnWidths = null;
        when(streaming.parseStreaming(any())).thenReturn(Map.of(0, ps0));

        UploadResultVO result = s.upload(file);

        verify(streaming, times(1)).parseStreaming(any());
        verify(parser, never()).parseAndSave(any(), any());
        verify(docService).updateSheetMeta(eq(NEW_DOC_ID), eq(1), any());
        // chunk 切：0..999 与 1000..1999 两桶
        ArgumentCaptor<ExcelSheetChunk> chunkCaptor = ArgumentCaptor.forClass(ExcelSheetChunk.class);
        verify(chunkMapper, times(2)).insert(chunkCaptor.capture());
        List<ExcelSheetChunk> chunks = chunkCaptor.getAllValues();
        assertEquals(0, chunks.get(0).getRowStart());
        assertEquals(999, chunks.get(0).getRowEnd());
        assertEquals(1000, chunks.get(1).getRowStart());
        assertEquals(1500, chunks.get(1).getRowEnd());
        verify(sheetMapper, times(1)).updateById(any(ExcelSheet.class));
        assertEquals(1, result.sheetCount());
        assertEquals("big.xlsx", result.name());
    }

    // ── 失败清理 ──

    @Test
    @DisplayName("解析失败回滚并 purge 已建主记录（用户文案不透传内部异常）")
    void parseFailureCleansUpAndThrows() throws Exception {
        ExcelUploadService s = newService();
        MockMultipartFile file = new MockMultipartFile("file", "broken.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", new byte[2]);
        doThrow(new RuntimeException("boom")).when(parser).parseAndSave(any(), any());

        BusinessException ex = assertThrows(BusinessException.class, () -> s.upload(file));
        assertEquals(ErrorCodes.EXCEL_PARSE_ERROR, ex.getCode());
        assertTrue(ex.getMessage().startsWith("上传失败: "));
        // P1-03：用户文案固定，不透传内部异常 message（防泄露临时路径/POI 细节）；根因仍挂 cause 供日志排查。
        assertEquals(ExcelUploadService.UPLOAD_FAILURE_MESSAGE, ex.getMessage());
        assertFalse(ex.getMessage().contains("boom"));
        assertEquals("boom", ex.getCause().getMessage());
        verify(docService).purge(NEW_DOC_ID);
    }

    @Test
    @DisplayName("业务异常（含错误码）不被降级成解析失败，仍做清理")
    void businessExceptionFromParserKeepsCodeAndStillCleansUp() throws Exception {
        ExcelUploadService s = newService();
        MockMultipartFile file = new MockMultipartFile("file", "empty.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", new byte[2]);
        doThrow(new BusinessException(ErrorCodes.EXCEL_PARSE_ERROR, "工作簿中没有可解析的 Sheet"))
                .when(parser).parseAndSave(any(), any());

        BusinessException ex = assertThrows(BusinessException.class, () -> s.upload(file));
        assertEquals("工作簿中没有可解析的 Sheet", ex.getMessage());
        assertEquals(ErrorCodes.EXCEL_PARSE_ERROR, ex.getCode());
        verify(docService).purge(NEW_DOC_ID);
    }

    // ── buildChunks 纯逻辑 ──

    @Test
    @DisplayName("按连续行范围切块，空桶跳过")
    void buildChunksSlicesByRowRange() {
        Map<Integer, Map<Integer, String>> rows = rowsOf(Map.of(
                0, Map.of(0, "a"),
                1, Map.of(1, "b"),
                1500, Map.of(0, "c")));
        List<ExcelUploadService.ChunkSlice> chunks =
                ExcelUploadService.buildChunks(rows, ExcelParserService.CHUNK_SIZE);
        assertEquals(2, chunks.size());
        assertEquals(0, chunks.get(0).rowStart());
        assertEquals(999, chunks.get(0).rowEnd());
        assertEquals(1000, chunks.get(1).rowStart());
        assertEquals(1500, chunks.get(1).rowEnd());
        assertTrue(chunks.get(0).celldataJson().contains("\"r\":1"));
        assertTrue(chunks.get(1).celldataJson().contains("\"r\":1500"));
    }

    @Test
    @DisplayName("空行表不产生块")
    void buildChunksEmptyRowsYieldsNothing() {
        assertEquals(0, ExcelUploadService.buildChunks(null, 100).size());
        assertEquals(0, ExcelUploadService.buildChunks(Map.of(), 100).size());
    }

    // ── helpers ──

    private static Map<Integer, Map<Integer, String>> rowsOf(
            Map<Integer, Map<Integer, String>> src) {
        Map<Integer, Map<Integer, String>> rows = new LinkedHashMap<>(src);
        return rows;
    }

    private MultipartFile multipartWithSize(String name, long size) throws IOException {
        MultipartFile mf = mock(MultipartFile.class);
        when(mf.getOriginalFilename()).thenReturn(name);
        when(mf.getSize()).thenReturn(size);
        when(mf.isEmpty()).thenReturn(false);
        doAnswer(inv -> {
            java.io.File f = inv.getArgument(0);
            Files.write(f.toPath(), new byte[]{(byte) 0x50, 0x4b});
            return null;
        }).when(mf).transferTo(any(java.io.File.class));
        return mf;
    }
}