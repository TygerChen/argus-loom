package com.argus.rag.excel.service;

import com.argus.rag.common.util.JsonCompat;
import com.argus.rag.auth.CurrentUserService;
import com.argus.rag.common.config.ArgusRagProperties;
import com.argus.rag.common.exception.BusinessException;
import com.argus.rag.common.exception.ErrorCodes;
import com.argus.rag.common.exception.ForbiddenException;
import com.argus.rag.common.exception.UnauthorizedException;
import com.argus.rag.excel.entity.ExcelDocument;
import com.argus.rag.excel.entity.ExcelSheet;
import com.argus.rag.excel.entity.ExcelSheetChunk;
import com.argus.rag.excel.mapper.ExcelSheetChunkMapper;
import com.argus.rag.excel.mapper.ExcelSheetMapper;
import com.argus.rag.excel.model.vo.SheetMetaVO;
import com.argus.rag.excel.model.vo.UploadResultVO;
import com.argus.rag.excel.service.StreamingParserService.ParsedSheet;
import org.apache.commons.io.FilenameUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Excel 上传编排服务（阶段 C：P1-01 真实流式 / P1-02 校验 / P2-12 资源关闭）。
 * <p>
 * 统一承接 {@code POST /api/excel/upload}：校验 → 存盘 → 按大小选路由
 * （≤50MB 走 {@link ExcelParserService} 普通解析；&gt;50MB 走 {@link StreamingParserService} SAX 流式）
 * → 落库 sheet/chunk → 收尾元信息 → 失败清理。Controller 只保留「收参→调 service」薄壳。
 */
@Service
public class ExcelUploadService {

    private static final Logger log = LoggerFactory.getLogger(ExcelUploadService.class);

    /** 与旧 Controller 一致：超过此大小自动切换流式解析防 OOM。 */
    public static final long LARGE_FILE_THRESHOLD = 50L * 1024 * 1024; // 50MB

    /** 上传硬上限：超过直接拒绝，防止解压后内存/磁盘放大（100MB 原始文件已远超业务需要）。 */
    public static final long MAX_UPLOAD_SIZE = 100L * 1024 * 1024; // 100MB

    /** 允许上传并能被解析的扩展名白名单（大小写不敏感）。 */
    private static final List<String> ALLOWED_EXTENSIONS = List.of("xlsx", "xls");

    /** 流式 SAX 无行高数据，rowlen 恒为空对象（config 骨架与普通路径对齐）。 */
    private static final String EMPTY_ROW_LEN = "{}";

    /**
     * 上传失败时给用户的固定文案（P1-03）。
     * <p>不拼接内部异常 message，避免暴露临时文件路径、POI 解析细节等内部信息；
     * 原始异常仅经日志输出，便于排障。</p>
     */
    public static final String UPLOAD_FAILURE_MESSAGE = "上传失败: 文件解析失败，请检查文件格式后重试";

    private final ExcelParserService parserService;
    private final StreamingParserService streamingService;
    private final ExcelDocumentService documentService;
    private final ExcelSheetMapper sheetMapper;
    private final ExcelSheetChunkMapper chunkMapper;
    private final CurrentUserService currentUserService;

    /**
     * 上传根目录：生产由共享配置 {@code argus.rag.excel.upload-path} 绑定（构造时快照），
     * 单元测试可反射重定向到临时目录（字段非 final，供既有测试沿用）。
     */
    private String uploadPath;

    public ExcelUploadService(ExcelParserService parserService,
                              StreamingParserService streamingService,
                              ExcelDocumentService documentService,
                              ExcelSheetMapper sheetMapper,
                              ExcelSheetChunkMapper chunkMapper,
                              CurrentUserService currentUserService) {
        this(parserService, streamingService, documentService, sheetMapper, chunkMapper,
                currentUserService, new ArgusRagProperties());
    }

    /** 生产构造：注入共享配置 bean（上传路径收口于 argus.rag.excel.upload-path）。 */
    @org.springframework.beans.factory.annotation.Autowired
    public ExcelUploadService(ExcelParserService parserService,
                              StreamingParserService streamingService,
                              ExcelDocumentService documentService,
                              ExcelSheetMapper sheetMapper,
                              ExcelSheetChunkMapper chunkMapper,
                              CurrentUserService currentUserService,
                              ArgusRagProperties argusProperties) {
        this.parserService = parserService;
        this.streamingService = streamingService;
        this.documentService = documentService;
        this.sheetMapper = sheetMapper;
        this.chunkMapper = chunkMapper;
        this.currentUserService = currentUserService;
        this.uploadPath = argusProperties.getUploadPath();
    }

    // ── 纯静态判定（供直接单测） ──

    /** 白名单扩展名校验（大小写不敏感），拒绝 null / 空 / 无扩展名。 */
    public static boolean isAllowedExtension(String originalName) {
        if (originalName == null || originalName.isBlank()) return false;
        String ext = FilenameUtils.getExtension(originalName);
        if (ext == null || ext.isBlank()) return false;
        return ALLOWED_EXTENSIONS.contains(ext.toLowerCase());
    }

    /** 展示名净化：仅保留末段文件名，杜绝路径组件泄漏到展示字段。 */
    public static String sanitizeDisplayName(String originalName) {
        return originalName == null ? null : FilenameUtils.getName(originalName);
    }

    /** 落盘安全文件名：UUID + 白名单扩展名，永不代入用户原始文件名。 */
    public static String buildStoredName(String ext) {
        return UUID.randomUUID() + "." + ext.toLowerCase();
    }

    /** 大文件判定：超过阈值走流式。 */
    public static boolean isLargeFile(long size) {
        return size > LARGE_FILE_THRESHOLD;
    }

    /** 上传大小硬上限判定（供单测与编排共用）。 */
    public static boolean isOverLimit(long size) {
        return size > MAX_UPLOAD_SIZE;
    }

    /** 一处 sheet 按连续行范围切成的小块。 */
    public record ChunkSlice(int rowStart, int rowEnd, String celldataJson) {}

    /**
     * 将行稀疏表按连续行号窗口切块：每块覆盖 {@code chunkSize} 行范围，空窗口跳过，
     * 复用于流式大文件 chunk 落库；每块经 {@link StreamingParserService#toCelldata} 成 celldata JSON。
     */
    public static List<ChunkSlice> buildChunks(Map<Integer, Map<Integer, String>> rows, int chunkSize) {
        List<ChunkSlice> out = new ArrayList<>();
        if (rows == null || rows.isEmpty()) return out;
        int maxRow = rows.keySet().stream().mapToInt(Integer::intValue).max().orElse(0);
        for (int start = 0; start <= maxRow; start += chunkSize) {
            int end = Math.min(start + chunkSize - 1, maxRow);
            Map<Integer, Map<Integer, String>> slice = new LinkedHashMap<>();
            for (int r = start; r <= end; r++) {
                Map<Integer, String> row = rows.get(r);
                if (row != null) slice.put(r, row);
            }
            if (slice.isEmpty()) continue; // 空窗口不落空 chunk
            out.add(new ChunkSlice(start, end, JsonCompat.toJSONString(StreamingParserService.toCelldata(slice))));
        }
        return out;
    }

    // ── 上传主编排 ──

    /**
     * 上传一个 Excel 文件：校验 → 存盘 → 按大小路由解析 → 落库 → 返回切片结果。
     * 事务包裹 DB 落库，失败由 catch 兜底清理（同旧 Controller 语义）。
     */
    @Transactional(rollbackFor = Exception.class)
    public UploadResultVO upload(MultipartFile file) {
        ExcelDocument doc = null;
        try {
            String originalName = file.getOriginalFilename();
            if (!isAllowedExtension(originalName)) {
                throw new BusinessException(ErrorCodes.EXCEL_PARSE_ERROR, "仅支持 .xlsx/.xls 文件");
            }
            if (file.isEmpty() || file.getSize() <= 0) {
                throw new BusinessException(ErrorCodes.EXCEL_PARSE_ERROR, "上传文件不能为空");
            }
            if (isOverLimit(file.getSize())) {
                throw new BusinessException(ErrorCodes.FILE_LIMIT_EXCEEDED,
                        "文件超过 100MB 上限，请拆分后上传");
            }

            String ext = FilenameUtils.getExtension(originalName).toLowerCase();
            String displayName = sanitizeDisplayName(originalName);

            Path dir = Paths.get(uploadPath).toAbsolutePath().normalize();
            if (!Files.exists(dir)) Files.createDirectories(dir);
            Path filePath = dir.resolve(buildStoredName(ext));
            file.transferTo(filePath.toFile());

            doc = new ExcelDocument();
            doc.setName(displayName);
            doc.setFilePath(filePath.toString());
            doc.setFileSize(file.getSize());
            doc.setCreatorId(String.valueOf(currentUserId()));

            boolean isLarge = isLargeFile(file.getSize());
            log.info("上传文件: name={}, size={}, streaming={}", displayName, file.getSize(), isLarge);

            // 先建主记录拿到 documentId，sheet/chunk 才能正确关联；解析失败由 catch 兜底清理。
            documentService.create(doc);

            List<ExcelSheet> sheets;
            try (InputStream is = new FileInputStream(filePath.toFile())) {
                if (isLarge) {
                    sheets = parseStreamingSheets(doc, is);
                } else {
                    sheets = parserService.parseAndSave(is, doc);
                }
            }

            List<String> sheetNames = new ArrayList<>();
            for (ExcelSheet s : sheets) sheetNames.add(s.getSheetName());
            documentService.updateSheetMeta(doc.getId(), sheets.size(), JsonCompat.toJSONString(sheetNames));

            List<SheetMetaVO> sheetMetaList = sheets.stream()
                    .map(s -> new SheetMetaVO(s.getId(), s.getSheetIndex(), s.getSheetName(),
                            s.getTotalRows(), s.getTotalCols(), s.getChunkCount(), s.getActive()))
                    .toList();

            return new UploadResultVO(doc.getId(), doc.getName(), sheets.size(), sheetMetaList);
        } catch (UnauthorizedException | ForbiddenException e) {
            throw e;
        } catch (BusinessException e) {
            // 已是业务语义异常（含错误码/用户可读文案）：清理后原样上抛，不降级成「解析失败」。
            cleanupAfterFailure(doc);
            throw e;
        } catch (Exception e) {
            log.error("上传失败", e);
            cleanupAfterFailure(doc);
            // P1-03：用户文案固定，不回抛内部异常 message（可能含临时路径/POI 细节）；
            // 原始异常只进日志（上面已 log.error），便于排障。
            throw new BusinessException(ErrorCodes.EXCEL_PARSE_ERROR, UPLOAD_FAILURE_MESSAGE, e);
        }
    }

    /** 上传中途失败的收尾：已建主记录走 purge，否则删掉刚落盘的临时文件。失败只记日志，不掩盖原始异常。 */
    private void cleanupAfterFailure(ExcelDocument doc) {
        if (doc != null && doc.getId() != null) {
            try { documentService.purge(doc.getId()); } catch (Exception cleanupEx) {
                log.warn("上传失败后清理文档失败 docId={}", doc.getId(), cleanupEx);
            }
        } else if (doc != null && doc.getFilePath() != null) {
            try { Files.deleteIfExists(Paths.get(doc.getFilePath())); } catch (Exception cleanupEx) {
                log.warn("上传失败后删除临时文件失败 path={}", doc.getFilePath(), cleanupEx);
            }
        }
    }

    private String currentUserId() {
        return String.valueOf(currentUserService.getRequiredCurrentUser().userId());
    }

    /** 大文件流式落库：SAX 产内存 ParsedSheet → 铺实体落库 sheet + chunk，chunk 数回填。 */
    private List<ExcelSheet> parseStreamingSheets(ExcelDocument doc, InputStream is) throws Exception {
        Map<Integer, ParsedSheet> parsed = streamingService.parseStreaming(is);
        List<ExcelSheet> sheets = new ArrayList<>();
        for (Map.Entry<Integer, ParsedSheet> en : parsed.entrySet()) {
            int index = en.getKey();
            ParsedSheet ps = en.getValue();
            ExcelSheet sheet = new ExcelSheet();
            sheet.setDocumentId(doc.getId());
            sheet.setSheetIndex(index);
            sheet.setSheetName(ps.sheetName);
            sheet.setActive(index == 0 ? 1 : 0);
            sheet.setStatus(1);

            int maxRow = ps.rows.isEmpty() ? 0
                    : ps.rows.keySet().stream().mapToInt(Integer::intValue).max().orElse(0);
            sheet.setTotalRows(maxRow + 1);
            sheet.setTotalCols(ps.colCount);
            sheet.setMergeConfigJson(ps.mergeConfig == null ? "{}" : JsonCompat.toJSONString(ps.mergeConfig));
            sheet.setColumnLenJson(ps.columnWidths == null ? "{}" : JsonCompat.toJSONString(ps.columnWidths));
            sheet.setRowLenJson(EMPTY_ROW_LEN);
            sheet.setConfigJson(JsonCompat.toJSONString(buildConfigJson(sheet)));
            sheetMapper.insert(sheet);

            List<ChunkSlice> chunks = buildChunks(ps.rows, ExcelParserService.CHUNK_SIZE);
            int chunkIndex = 0;
            for (ChunkSlice c : chunks) {
                ExcelSheetChunk chunk = new ExcelSheetChunk();
                chunk.setDocumentId(doc.getId());
                chunk.setSheetId(sheet.getId());
                chunk.setChunkIndex(chunkIndex);
                chunk.setRowStart(c.rowStart());
                chunk.setRowEnd(c.rowEnd());
                chunk.setCelldataJson(c.celldataJson());
                chunkMapper.insert(chunk);
                chunkIndex++;
            }
            sheet.setChunkCount(chunkIndex);
            sheetMapper.updateById(sheet);

            sheets.add(sheet);
            log.info("流式 Sheet [{}] 落库: id={}, rows={}, chunks={}",
                    ps.sheetName, sheet.getId(), sheet.getTotalRows(), chunkIndex);
        }
        return sheets;
    }

    /** 组装流式 Sheet 的 config（merge / columnlen / rowlen），与普通解析骨架对齐。 */
    private Map<String, Object> buildConfigJson(ExcelSheet sheet) {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("merge", sheet.getMergeConfigJson() == null ? new LinkedHashMap<>()
                : JsonCompat.parseObject(sheet.getMergeConfigJson()));
        config.put("columnlen", sheet.getColumnLenJson() == null ? new LinkedHashMap<>()
                : JsonCompat.parseObject(sheet.getColumnLenJson()));
        config.put("rowlen", new LinkedHashMap<>());
        return config;
    }
}
