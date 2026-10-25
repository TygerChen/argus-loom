package com.argus.rag.excel.service;

import com.argus.rag.common.config.ArgusRagProperties;
import com.argus.rag.common.exception.BusinessException;
import com.argus.rag.excel.entity.ExcelSheet;
import com.argus.rag.excel.mapper.ExcelSheetMapper;
import com.argus.rag.excel.model.vo.AssetUploadResultVO;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 图片/图表资源服务：在线表格里的图片统一落到本地资产目录，
 * Excel Sheet 只保存公开 URL，避免把 Base64 大图写进数据库。
 */
@Service
public class ExcelAssetService {

    public static final String ASSET_KEY_PREFIX = "images/";
    public static final String ASSET_PUBLIC_PREFIX = "/api/excel/assets/";
    public static final String CHART_ASSET_PREFIX = "charts/";
    private static final String CHART_MANIFEST_MARKER = "__chartResource";

    private static final Logger log = LoggerFactory.getLogger(ExcelAssetService.class);
    private static final List<String> ALLOWED_CONTENT_TYPES =
            List.of("image/png", "image/jpeg", "image/gif", "image/webp", "image/bmp");
    private static final Map<String, String> EXTENSION_TO_CONTENT_TYPE = Map.ofEntries(
            Map.entry("png", "image/png"),
            Map.entry("jpg", "image/jpeg"),
            Map.entry("jpeg", "image/jpeg"),
            Map.entry("gif", "image/gif"),
            Map.entry("webp", "image/webp"),
            Map.entry("bmp", "image/bmp")
    );
    private static final Map<String, String> CONTENT_TYPE_TO_EXTENSION = Map.of(
            "image/png", "png",
            "image/jpeg", "jpg",
            "image/gif", "gif",
            "image/webp", "webp",
            "image/bmp", "bmp"
    );
    private static final Pattern LEGACY_DATA_URI = Pattern.compile(
            "(?i)data:image/(png|jpeg|gif|webp|bmp);base64,([A-Za-z0-9+/=\\r\\n]+)");

    private final ExcelSheetMapper sheetMapper;
    private final ObjectMapper objectMapper;

    /**
     * 上传根目录：生产由共享配置 {@code argus.rag.excel.upload-path} 绑定（构造时快照），
     * 单元测试可反射重定向到临时目录（字段非 final，供既有测试沿用）。
     */
    private String uploadPath;

    public ExcelAssetService(ExcelSheetMapper sheetMapper, ObjectMapper objectMapper) {
        this(sheetMapper, objectMapper, new ArgusRagProperties());
    }

    /** 生产构造：注入共享配置 bean（上传路径收口于 argus.rag.excel.upload-path）。 */
    @org.springframework.beans.factory.annotation.Autowired
    public ExcelAssetService(ExcelSheetMapper sheetMapper, ObjectMapper objectMapper,
                             ArgusRagProperties argusProperties) {
        this.sheetMapper = sheetMapper;
        this.objectMapper = objectMapper;
        this.uploadPath = argusProperties.getUploadPath();
    }

    /** 存储键 → 浏览器可访问的 URL（前端 img 标签里填的就是这个）。 */
    public String publicUrl(String key) {
        return ASSET_PUBLIC_PREFIX + key;
    }

    /**
     * 资源 URL 签名校验。当前恒真（内部系统，资源 key 本身就是不可猜的 UUID）；
     * 如果以后要防外链/防盗链，把"对 key + 过期时间算 HMAC"的实现换进来即可，
     * 调用方不用改——留接口就是留扩展点。
     */
    public boolean verifySignature(String key, String sig) {
        return true;
    }

    /**
     * 上传图片。三道校验 + 一个存储约定：
     * 类型白名单（Content-Type 由文件扩展名和浏览器声明共同决定，防伪造）、
     * 20MB 上限、键名带归属（images/{documentId 或 shared}/{UUID}.{ext}）。
     * UUID 键名保证同名文件不冲突，也让人无法通过文件名猜到别人的图片。
     */
    public AssetUploadResultVO upload(MultipartFile file, Long documentId) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new BusinessException("请选择要插入的图片");
        }
        String contentType = resolveContentType(file.getOriginalFilename(), file.getContentType());
        if (!ALLOWED_CONTENT_TYPES.contains(contentType)) {
            throw new BusinessException("仅支持 PNG / JPEG / GIF / WEBP / BMP 图片");
        }
        if (file.getSize() > 20L * 1024 * 1024) {
            throw new BusinessException("图片不能超过 20 MB");
        }
        String ext = extensionOf(file.getOriginalFilename(), contentType);
        String owner = documentId == null ? "shared" : String.valueOf(documentId);
        String key = ASSET_KEY_PREFIX + owner + "/" + UUID.randomUUID() + "." + ext;
        try (InputStream in = file.getInputStream()) {
            storeAsset(key, in, file.getSize(), contentType);
        }
        return new AssetUploadResultVO(publicUrl(key), key, contentType, file.getSize());
    }

    /** 按 key 读资源。strictKey 负责防路径穿越（见它的注释）。 */
    public InputStream openRead(String rawKey) throws IOException {
        Path file = assetFile(strictKey(rawKey));
        return Files.newInputStream(file);
    }

    /** 由扩展名推 Content-Type；认不出就给通用二进制流类型（浏览器会下载而不是渲染）。 */
    public String contentTypeOf(String rawKey) {
        if (rawKey == null || rawKey.isBlank()) {
            return "application/octet-stream";
        }
        int dot = rawKey.lastIndexOf('.');
        if (dot < 0 || dot == rawKey.length() - 1) {
            return "application/octet-stream";
        }
        return EXTENSION_TO_CONTENT_TYPE.getOrDefault(
                rawKey.substring(dot + 1).toLowerCase(Locale.ROOT), "application/octet-stream");
    }

    /**
     * 存量数据迁移：老版本曾把图片以 Base64 直接塞进 Sheet 的配置 JSON，
     * 一张图能把一行数据撑到几 MB。本方法把 Base64 抽出落盘成资产文件，
     * JSON 里只留 URL。按批（limit）扫，管理员接口反复调用直到迁完——
     * "每次一小批、失败不中断、可重复执行"是存量迁移的三要素。
     */
    public MigrationSummary migrateLegacyImages(int limit) {
        if (limit <= 0) {
            return new MigrationSummary(0, 0, 0, 0);
        }
        QueryWrapper<ExcelSheet> qw = new QueryWrapper<>();
        qw.ne("status", 3).last("LIMIT " + Math.min(limit, 500));
        List<ExcelSheet> sheets = sheetMapper.selectList(qw);
        int scanned = 0;
        int updatedSheets = 0;
        int migrated = 0;
        int skipped = 0;
        for (ExcelSheet sheet : sheets) {
            if (sheet.getDocumentId() == null) {
                continue;
            }
            scanned++;
            try {
                FieldRewrite images = rewriteLegacyImages(sheet.getImagesConfigJson(), sheet.getDocumentId());
                FieldRewrite config = rewriteLegacyImages(sheet.getConfigJson(), sheet.getDocumentId());
                if (images.changed() || config.changed()) {
                    ExcelSheet update = new ExcelSheet();
                    update.setId(sheet.getId());
                    if (images.changed()) update.setImagesConfigJson(images.rewritten());
                    if (config.changed()) update.setConfigJson(config.rewritten());
                    sheetMapper.updateById(update);
                    updatedSheets++;
                }
                migrated += images.migratedCount() + config.migratedCount();
                skipped += images.skippedCount() + config.skippedCount();
            } catch (Exception e) {
                log.warn("存量图片迁移失败: sheet={}, doc={} - {}", sheet.getId(), sheet.getDocumentId(), e.getMessage());
            }
        }
        return new MigrationSummary(scanned, updatedSheets, migrated, skipped);
    }

    /** 删除文档时连带清理它的所有图片/图表资产（从三个配置 JSON 里收集全部 key）。 */
    public void deleteAssetsForDocument(Long documentId) {
        if (documentId == null) return;
        QueryWrapper<ExcelSheet> qw = new QueryWrapper<>();
        qw.eq("document_id", documentId);
        for (ExcelSheet sheet : sheetMapper.selectList(qw)) {
            deleteKeys(collectAssetKeys(sheet.getConfigJson()));
            deleteKeys(collectAssetKeys(sheet.getImagesConfigJson()));
            deleteKeys(collectAssetKeys(sheet.getChartJson()));
        }
    }

    /**
     * 孤儿资产清理：磁盘上有、但没有任何 Sheet 的 JSON 引用的资产 = 孤儿
     * （比如上传后没保存、文档硬删后残留）。先收集全部"在用" key 再对账删除，
     * 每次最多删 limit 个，宁慢勿错。
     */
    public int cleanupOrphanAssets(int limit) {
        if (limit <= 0) return 0;
        Set<String> used = new HashSet<>();
        QueryWrapper<ExcelSheet> qw = new QueryWrapper<>();
        qw.ne("status", 3).select("config_json", "images_config_json", "chart_json");
        for (ExcelSheet sheet : sheetMapper.selectList(qw)) {
            used.addAll(collectAssetKeys(sheet.getConfigJson()));
            used.addAll(collectAssetKeys(sheet.getImagesConfigJson()));
            used.addAll(collectAssetKeys(sheet.getChartJson()));
        }
        int deleted = 0;
        for (String key : listAssetKeys()) {
            if (deleted >= limit) break;
            if (used.contains(key)) continue;
            deleteQuietly(key);
            deleted++;
        }
        return deleted;
    }

    /**
     * 大图表 JSON 外置：图表配置超过 16KB 就落盘成资产文件，
     * Sheet 的 chart_json 列只存一个"清单"（marker + url）。
     * 读取时 restoreChartJson 负责还原——避免个别超大图表把整行数据库记录撑爆。
     */
    public String externalizeChartJson(Long documentId, ArrayNode charts) throws IOException {
        if (charts == null || charts.isEmpty()) return "[]";
        String payload = charts.toString();
        long size = payload.getBytes(StandardCharsets.UTF_8).length;
        if (size <= 16 * 1024L) return payload;
        String owner = documentId == null ? "shared" : String.valueOf(documentId);
        String key = CHART_ASSET_PREFIX + owner + "/" + UUID.randomUUID() + ".json";
        byte[] content = payload.getBytes(StandardCharsets.UTF_8);
        try (InputStream in = new ByteArrayInputStream(content)) {
            storeAsset(key, in, content.length, "application/json");
        }
        ObjectNode manifest = objectMapper.createObjectNode();
        manifest.put(CHART_MANIFEST_MARKER, true);
        manifest.put("url", publicUrl(key));
        ArrayNode wrapper = objectMapper.createArrayNode();
        wrapper.add(manifest);
        return wrapper.toString();
    }

    public ArrayNode restoreChartJson(String stored) {
        try {
            JsonNode node = stored == null || stored.isBlank()
                    ? objectMapper.createArrayNode() : objectMapper.readTree(stored);
            if (node.isArray() && node.size() == 1 && node.get(0).isObject()
                    && node.get(0).has(CHART_MANIFEST_MARKER)) {
                String url = node.get(0).path("url").asText();
                if (url.startsWith(ASSET_PUBLIC_PREFIX)) {
                    String key = url.substring(ASSET_PUBLIC_PREFIX.length()).split("[?]", 2)[0];
                    try (InputStream in = openRead(key)) {
                        JsonNode payload = objectMapper.readTree(in);
                        if (payload != null && payload.isArray()) return (ArrayNode) payload;
                    }
                }
            }
            return node.isArray() ? (ArrayNode) node : objectMapper.createArrayNode();
        } catch (Exception e) {
            log.warn("图表资源恢复失败: {}", e.getMessage());
            return objectMapper.createArrayNode();
        }
    }

    private void storeAsset(String key, InputStream in, long size, String contentType) throws IOException {
        Path target = assetFile(key);
        Path parent = target.getParent();
        if (parent != null) Files.createDirectories(parent);
        Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
    }

    private Path assetRoot() throws IOException {
        Path root = Paths.get(uploadPath).toAbsolutePath().normalize().resolve("assets");
        Files.createDirectories(root);
        return root;
    }

    /**
     * key → 磁盘路径。两道防线都在这：先 normalize 再检查是否以 .. 开头
     * （01 篇讲的"路径穿越"——攻击者用 ../../ 跳出资产目录读任意文件），
     * 最后 resolve 后再 normalize 一次确保最终路径仍在资产根下。
     */
    private Path assetFile(String key) throws IOException {
        String clean = key.replace('\\', '/');
        while (clean.contains("//")) clean = clean.replace("//", "/");
        Path normalized;
        try {
            normalized = Path.of(clean).normalize();
        } catch (RuntimeException e) {
            throw new IOException("资产 key 格式非法");
        }
        String resolved = normalized.toString().replace('\\', '/');
        if (resolved.startsWith("..")) {
            throw new IOException("非法资产 key: " + key);
        }
        return assetRoot().resolve(resolved).normalize();
    }

    /**
     * 对外来的 key 做严格校验：URL 解码（前端可能编码过）→ 规范化路径 →
     * 必须以 images/ 或 charts/ 开头。不满足直接拒绝——安全边界收在最入口处。
     */
    private String strictKey(String rawKey) throws IOException {
        if (rawKey == null || rawKey.isBlank()) throw new IOException("资产 key 为空");
        String key;
        try {
            key = URLDecoder.decode(rawKey, StandardCharsets.UTF_8).replace('\\', '/');
        } catch (IllegalArgumentException e) {
            throw new IOException("资产 key 格式非法");
        }
        Path normalized = Path.of(key).normalize();
        String clean = normalized.toString().replace('\\', '/');
        if (!clean.startsWith(ASSET_KEY_PREFIX) && !clean.startsWith(CHART_ASSET_PREFIX)) {
            throw new IOException("非法资产 key: " + key);
        }
        return clean;
    }

    private List<String> listAssetKeys() {
        List<String> keys = new ArrayList<>();
        for (String prefix : new String[]{ASSET_KEY_PREFIX, CHART_ASSET_PREFIX}) {
            Path root;
            Path start;
            try {
                root = assetRoot();
                start = root.resolve(prefix);
            } catch (Exception e) {
                continue;
            }
            if (!Files.isDirectory(start)) continue;
            try (var stream = Files.walk(start)) {
                stream.filter(Files::isRegularFile)
                        .forEach(p -> keys.add(root.relativize(p).toString().replace('\\', '/')));
            } catch (IOException e) {
                log.warn("资产目录扫描失败: {}", e.getMessage());
            }
        }
        return keys;
    }

    private void deleteQuietly(String key) {
        try {
            Files.deleteIfExists(assetFile(key));
        } catch (Exception e) {
            log.warn("资产删除失败: {} - {}", key, e.getMessage());
        }
    }

    private void deleteKeys(Set<String> keys) {
        for (String key : keys) deleteQuietly(key);
    }

    /** 文件类型判定：扩展名优先于浏览器声明的 Content-Type（前者更难伪造错位）。 */
    static String resolveContentType(String fileName, String fallback) {
        if (fallback != null && !fallback.isBlank() && ALLOWED_CONTENT_TYPES.contains(fallback)) {
            return fallback;
        }
        String ext = extensionOf(fileName, fallback);
        return EXTENSION_TO_CONTENT_TYPE.getOrDefault(ext, "application/octet-stream");
    }

    /** 从文件名或 Content-Type 推出安全的小写扩展名（两者都不可信时给 bin）。 */
    static String extensionOf(String fileName, String contentType) {
        if (fileName != null) {
            String name = fileName.toLowerCase(Locale.ROOT);
            int dot = name.lastIndexOf('.');
            if (dot >= 0 && dot < name.length() - 1) {
                String ext = name.substring(dot + 1);
                if (EXTENSION_TO_CONTENT_TYPE.containsKey(ext)) return ext;
            }
        }
        if (contentType != null) {
            String type = contentType.toLowerCase(Locale.ROOT);
            for (Map.Entry<String, String> entry : EXTENSION_TO_CONTENT_TYPE.entrySet()) {
                if (entry.getValue().equals(type)) return entry.getKey();
            }
        }
        return "png";
    }

    private Set<String> collectAssetKeys(String json) {
        Set<String> keys = new HashSet<>();
        if (json == null || json.isBlank()) return keys;
        try {
            collectTextNodes(objectMapper.readTree(json), keys);
        } catch (Exception e) {
            log.warn("资产配置解析失败，跳过引用收集: {}", e.getMessage());
        }
        return keys;
    }

    private void collectTextNodes(JsonNode node, Set<String> keys) {
        if (node == null) return;
        if (node.isTextual()) {
            String text = node.asText();
            int from = 0;
            while ((from = text.indexOf(ASSET_PUBLIC_PREFIX, from)) >= 0) {
                int start = from + ASSET_PUBLIC_PREFIX.length();
                int end = start;
                while (end < text.length() && isKeyCharacter(text.charAt(end))) end++;
                if (end > start) keys.add(text.substring(start, end));
                from = start;
            }
            return;
        }
        if (node.isArray()) {
            node.forEach(child -> collectTextNodes(child, keys));
            return;
        }
        if (node.isObject()) {
            node.fields().forEachRemaining(entry -> collectTextNodes(entry.getValue(), keys));
        }
    }

    static boolean isKeyCharacter(char c) {
        return c == '/' || c == '.' || c == '-' || c == '_' || Character.isLetterOrDigit(c);
    }

    /**
     * 迁移的核心：遍历 JSON 树找 Base64 图片（data:image/...;base64,xxx），
     * 解码落盘成资产文件、原位替换成 URL。返回"改没改 / 新 JSON / 迁了几张 / 跳过几张"。
     */
    private FieldRewrite rewriteLegacyImages(String json, Long documentId) throws IOException {
        if (json == null || json.isBlank() || !json.contains("data:image/")) {
            return FieldRewrite.unchanged();
        }
        JsonNode root = objectMapper.readTree(json);
        List<DataUri> dataUris = new ArrayList<>();
        collectDataUris(root, dataUris);
        if (dataUris.isEmpty()) return FieldRewrite.unchanged();

        Map<String, String> uriToUrl = new LinkedHashMap<>();
        int skipped = 0;
        for (DataUri uri : dataUris) {
            if (uriToUrl.containsKey(uri.original())) continue;
            byte[] content;
            try {
                content = Base64.getMimeDecoder().decode(uri.base64Data());
            } catch (IllegalArgumentException e) {
                skipped++;
                continue;
            }
            if (content.length == 0 || content.length > 20L * 1024 * 1024) {
                skipped++;
                continue;
            }
            String key = storageKey(documentId, content, uri.contentType());
            try (InputStream in = new ByteArrayInputStream(content)) {
                storeAsset(key, in, content.length, uri.contentType());
            }
            uriToUrl.put(uri.original(), publicUrl(key));
        }
        if (uriToUrl.isEmpty()) return new FieldRewrite(false, null, 0, skipped);

        boolean[] changed = {false};
        JsonNode rewritten = rewriteTextUris(root, uriToUrl, changed);
        if (!changed[0]) return new FieldRewrite(false, null, 0, skipped);
        return new FieldRewrite(true, rewritten.toString(), uriToUrl.size(), skipped);
    }

    private String storageKey(Long documentId, byte[] content, String contentType) {
        String extension = CONTENT_TYPE_TO_EXTENSION.getOrDefault(contentType, "png");
        String owner = documentId == null ? "shared" : String.valueOf(documentId);
        return ASSET_KEY_PREFIX + owner + "/m/" + UUID.randomUUID() + "." + extension;
    }

    private void collectDataUris(JsonNode node, List<DataUri> found) {
        if (node == null) return;
        if (node.isTextual()) {
            Matcher matcher = LEGACY_DATA_URI.matcher(node.asText());
            while (matcher.find()) {
                found.add(new DataUri(matcher.group(0), "image/" + matcher.group(1).toLowerCase(Locale.ROOT), matcher.group(2)));
            }
            return;
        }
        if (node.isArray()) {
            node.forEach(child -> collectDataUris(child, found));
            return;
        }
        if (node.isObject()) {
            node.fields().forEachRemaining(entry -> collectDataUris(entry.getValue(), found));
        }
    }

    private JsonNode rewriteTextUris(JsonNode node, Map<String, String> uriToUrl, boolean[] changed) {
        if (node == null) return null;
        if (node.isTextual()) {
            String original = node.asText();
            String rewritten = original;
            for (Map.Entry<String, String> entry : uriToUrl.entrySet()) {
                if (rewritten.contains(entry.getKey())) rewritten = rewritten.replace(entry.getKey(), entry.getValue());
            }
            if (!rewritten.equals(original)) {
                changed[0] = true;
                return TextNode.valueOf(rewritten);
            }
            return node;
        }
        if (node.isObject()) {
            ObjectNode copy = objectMapper.createObjectNode();
            node.fields().forEachRemaining(entry -> {
                JsonNode child = rewriteTextUris(entry.getValue(), uriToUrl, changed);
                copy.set(entry.getKey(), child == null ? objectMapper.nullNode() : child);
            });
            return copy;
        }
        if (node.isArray()) {
            ArrayNode copy = objectMapper.createArrayNode();
            node.forEach(child -> copy.add(rewriteTextUris(child, uriToUrl, changed)));
            return copy;
        }
        return node;
    }

    public record MigrationSummary(int scannedSheets, int updatedSheets,
                                   int migratedImages, int skippedImages) {
    }

    private record DataUri(String original, String contentType, String base64Data) {
    }

    private record FieldRewrite(boolean changed, String rewritten,
                                int migratedCount, int skippedCount) {

        static FieldRewrite unchanged() {
            return new FieldRewrite(false, null, 0, 0);
        }
    }
}
