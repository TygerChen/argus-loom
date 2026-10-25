package com.argus.rag.excel.controller;

import com.argus.rag.common.api.ApiResponse;
import com.argus.rag.excel.service.ExcelAssetService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Duration;

/**
 * 图片资源接口：在线表格里插入的图片先传到这里，库中只保存公开 URL。
 */
@Tag(name = "图片资源")
@RestController
@RequestMapping("/api/excel/assets")
public class ExcelAssetController {

    private static final Logger log = LoggerFactory.getLogger(ExcelAssetController.class);
    private final ExcelAssetService assetService;

    public ExcelAssetController(ExcelAssetService assetService) {
        this.assetService = assetService;
    }

    @PostMapping
    @Operation(summary = "上传工作簿图片")
    public ApiResponse<?> upload(@RequestParam("file") MultipartFile file,
                                 @RequestParam(required = false) Long documentId) {
        try {
            return ApiResponse.success(assetService.upload(file, documentId));
        } catch (IllegalArgumentException e) {
            return new ApiResponse<>(false, null, e.getMessage());
        } catch (Exception e) {
            log.error("图片上传失败", e);
            return new ApiResponse<>(false, null, "图片上传失败: " + e.getMessage());
        }
    }

    @PostMapping("/migrate-legacy")
    @Operation(summary = "迁移存量 Base64 图片")
    public ApiResponse<?> migrateLegacy(@RequestParam(defaultValue = "200") int limit) {
        return ApiResponse.success(assetService.migrateLegacyImages(limit));
    }

    @GetMapping("/{*assetKey}")
    public ResponseEntity<InputStreamResource> get(@PathVariable String assetKey,
                                                   @RequestParam(required = false) String sig) {
        try {
            if (assetKey != null && assetKey.startsWith("/")) {
                assetKey = assetKey.substring(1);
            }
            if (!assetService.verifySignature(assetKey, sig)) {
                return ResponseEntity.status(403).build();
            }
            InputStreamResource resource = new InputStreamResource(assetService.openRead(assetKey));
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(assetService.contentTypeOf(assetKey)))
                    .cacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePublic())
                    .header("X-Content-Type-Options", "nosniff")
                    .body(resource);
        } catch (IOException e) {
            log.warn("图片资源读取失败: {} - {}", assetKey, e.getMessage());
            return ResponseEntity.notFound().build();
        }
    }
}
