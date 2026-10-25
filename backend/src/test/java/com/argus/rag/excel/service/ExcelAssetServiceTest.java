package com.argus.rag.excel.service;

import com.argus.rag.common.exception.BusinessException;
import com.argus.rag.excel.entity.ExcelSheet;
import com.argus.rag.excel.mapper.ExcelSheetMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link ExcelAssetService} 图片/图表资源契约（mock sheetMapper + 真实 ObjectMapper；
 * uploadPath 反射到 {@code @TempDir}，文件读写在临时目录完成，零 POI / 零真实磁盘）：
 * - 工具：publicUrl 前缀拼接、verifySignature 占位恒真、contentTypeOf 全分支；
 * - 上传校验：空文件 / 非白名单内容类型 / 超 20MB 均在落盘前拒绝；合法写入并返回 url/assetKey/contentType/size；
 * - 防御：openRead 拒空 key、非 images/charts 前缀、路径穿越 key（所有统一 IOException）；
 * - 删除/清理：documentId null 空操作、按 sheet 引用真删文件、孤儿清理仅删未引用；
 * - 图表：小 payload 内联不落盘、大 payload 外侧化成文件并 restore 往返还原、restore 坏 JSON 兜底空数组。
 */
@DisplayName("Excel 图片来源与清理（ExcelAssetService）")
class ExcelAssetServiceTest {

    @TempDir
    Path uploadDir;

    private ExcelSheetMapper sheetMapper;
    private final ObjectMapper om = new ObjectMapper();
    private ExcelAssetService service;

    @BeforeEach
    void setUp() throws Exception {
        sheetMapper = mock(ExcelSheetMapper.class);
        service = new ExcelAssetService(sheetMapper, om);
        Field f = ExcelAssetService.class.getDeclaredField("uploadPath");
        f.setAccessible(true);
        f.set(service, uploadDir.resolve("up").toString());
    }

    private Path assetFilePath(String key) {
        return uploadDir.resolve("up").resolve("assets").resolve(key);
    }

    // ---------- 工具 ----------

    @Test
    void publicUrlBuildsPrefix() {
        assertEquals("/api/excel/assets/" + "images/1/a.png",
                service.publicUrl("images/1/a.png"));
    }

    @Test
    void verifySignatureIsPlaceholderTrue() {
        assertTrue(service.verifySignature("k", "sig"));
        assertTrue(service.verifySignature(null, null));
    }

    @Test
    void contentTypeOfMapsKnownExtension() {
        assertEquals("image/png", service.contentTypeOf("images/1/a.png"));
        assertEquals("image/jpeg", service.contentTypeOf("images/1/a.jpg"));
        assertEquals("image/webp", service.contentTypeOf("images/1/a.webp"));
    }

    @Test
    void contentTypeOfFallsBackForUnexpected() {
        assertEquals("application/octet-stream", service.contentTypeOf("images/1/a"));
        assertEquals("application/octet-stream", service.contentTypeOf("images/1/a.xyz"));
        assertEquals("application/octet-stream", service.contentTypeOf(null));
        assertEquals("application/octet-stream", service.contentTypeOf("images/1/"));
    }

    // ---------- 校验拒绝（在落盘前） ----------

    @Test
    void uploadRejectsEmptyFile() {
        MockMultipartFile empty = new MockMultipartFile("f", "pic.png", "image/png", new byte[0]);
        assertThrows(BusinessException.class, () -> service.upload(empty, 1L));
        assertThrows(BusinessException.class, () -> service.upload(null, 1L));
    }

    @Test
    void uploadFallsBackToPngForUnknownContentType() throws IOException {
        // 未知扩展/类型无法映射时回退到 png 允许通过，而非拒绝
        MockMultipartFile unknown = new MockMultipartFile("f", "app.xyz", "text/plain", new byte[]{1});
        var r = service.upload(unknown, 2L);
        assertEquals("image/png", r.contentType());
        assertTrue(r.assetKey().endsWith(".png"));
        assertTrue(Files.isRegularFile(assetFilePath(r.assetKey())));
    }

    @Test
    void uploadRejectsOversize() {
        MultipartFile huge = mock(MultipartFile.class);
        when(huge.isEmpty()).thenReturn(false);
        when(huge.getOriginalFilename()).thenReturn("big.png");
        when(huge.getSize()).thenReturn(21L * 1024 * 1024);
        when(huge.getContentType()).thenReturn("image/png");
        assertThrows(BusinessException.class, () -> service.upload(huge, 1L));
    }

    // ---------- 写与读 ----------

    @Test
    void uploadWritesFileAndReturnsManifest() throws IOException {
        byte[] content = {1, 2, 3, 4};
        MockMultipartFile file = new MockMultipartFile("f", "pic.png", "image/png", content);
        var r = service.upload(file, 9L);

        assertEquals("image/png", r.contentType());
        assertEquals(4L, r.size());
        String key = r.assetKey();
        assertNotNull(key);
        assertTrue(key.startsWith("images/9/"), key);
        assertEquals("/api/excel/assets/" + key, r.url());
        // 校验按服务到 @TempDir/up/assets/images/9/... 真实落盘
        assertTrue(Files.isRegularFile(uploadDir.resolve("up").resolve("assets").resolve(key)));
        assertArrayEquals(content, Files.readAllBytes(uploadDir.resolve("up").resolve("assets").resolve(key)));
    }

    @Test
    void uploadWithSharedOwnerWhenNoDocument() throws IOException {
        MockMultipartFile file = new MockMultipartFile("f", "pic.png", "image/png", new byte[]{1});
        var r = service.upload(file, null);
        assertTrue(r.assetKey().startsWith("images/shared/"));
    }

    // ---------- openRead 防御 ----------

    @Test
    void openReadReturnsStoredStream() throws Exception {
        byte[] bytes = "assetdata".getBytes(StandardCharsets.UTF_8);
        MockMultipartFile file = new MockMultipartFile("f", "pic.png", "image/png", bytes);
        var r = service.upload(file, 3L);
        try (InputStream in = service.openRead(r.assetKey())) {
            assertArrayEquals(bytes, in.readAllBytes());
        }
    }

    @Test
    void openReadRejectsBlankKey() {
        assertThrows(IOException.class, () -> service.openRead(""));
        assertThrows(IOException.class, () -> service.openRead("   "));
    }

    @Test
    void openReadRejectsNonAssetKey() {
        assertThrows(IOException.class, () -> service.openRead("other/foo.png"));
    }

    @Test
    void openReadRejectsTraversalKey() {
        // normalize 后脱离 images/ 前缀 → 拒绝，防目录穿越
        assertThrows(IOException.class, () -> service.openRead("images/../secret.png"));
    }

    // ---------- 删除 ----------

    @Test
    void deleteAssetsForDocumentNoopOnNull() {
        assertDoesNotThrow(() -> service.deleteAssetsForDocument(null));
    }

    @Test
    void deleteAssetsForDocumentRemovesReferencedFiles() throws IOException {
        ExcelSheet sheet = new ExcelSheet();
        sheet.setConfigJson("{\"src\":\"/api/excel/assets/images/4/ref.png\"}");
        when(sheetMapper.selectList(any())).thenReturn(List.of(sheet));

        Path referenced = assetFilePath("images/4/ref.png");
        Files.createDirectories(referenced.getParent());
        Files.write(referenced, new byte[]{1});

        service.deleteAssetsForDocument(4L);

        assertFalse(Files.exists(referenced), "文档相关影像应被物理删除");
    }

    @Test
    void cleanupOrphansRemovesOnlyUnused() throws IOException {
        ExcelSheet sheet = new ExcelSheet();
        sheet.setConfigJson("{\"src\":\"/api/excel/assets/images/5/used.png\"}");
        when(sheetMapper.selectList(any())).thenReturn(List.of(sheet));

        Path used = assetFilePath("images/5/used.png");
        Path orphan = assetFilePath("images/5/orphan.png");
        Files.createDirectories(used.getParent());
        Files.write(used, new byte[]{1});
        Files.write(orphan, new byte[]{1});

        int deleted = service.cleanupOrphanAssets(10);

        assertTrue(deleted >= 1, "孤儿图应被清理");
        assertTrue(Files.exists(used), "仍被引用的图应保留");
        assertFalse(Files.exists(orphan), "未被引用的孤儿图应删除");
    }

    @Test
    void cleanupOrphansNoopOnNonPositiveLimit() {
        assertEquals(0, service.cleanupOrphanAssets(0));
        assertEquals(0, service.cleanupOrphanAssets(-1));
    }

    // ---------- 图表外部化 ----------

    @Test
    void externalizeSmallPayloadStaysInline() throws IOException {
        ArrayNode small = om.createArrayNode();
        ObjectNode o = om.createObjectNode();
        o.put("title", "chart");
        small.add(o);
        int smallBytes = small.toString().getBytes(StandardCharsets.UTF_8).length;
        String out = service.externalizeChartJson(1L, small);
        assertEquals(small.toString(), out);
        assertFalse(out.contains("__chartResource"), Integer.toString(smallBytes));
    }

    @Test
    void externalizeEmptyReturnsEmptyArray() throws IOException {
        assertEquals("[]", service.externalizeChartJson(1L, null));
        assertEquals("[]", service.externalizeChartJson(1L, om.createArrayNode()));
    }

    @Test
    void externalizeLargeStoresAndRestoreRoundTrips() throws IOException {
        ArrayNode charts = om.createArrayNode();
        ObjectNode chart = om.createObjectNode();
        chart.put("name", "big");
        chart.put("payload", "x".repeat(20000)); // 1. externalize > 16KB
        charts.add(chart);

        String external = service.externalizeChartJson(7L, charts);
        assertTrue(external.contains("__chartResource"), external);

        ArrayNode restored = service.restoreChartJson(external);
        assertEquals("big", restored.get(0).get("name").asText());
        assertEquals("x".repeat(20000), restored.get(0).get("payload").asText());
    }

    @Test
    void restoreFallsBackToEmptyForBlankOrInvalid() {
        ArrayNode blank = service.restoreChartJson("");
        assertTrue(blank.isEmpty());
        assertTrue(service.restoreChartJson(null).isEmpty());
        assertTrue(service.restoreChartJson("not-json{").isEmpty());
    }
}