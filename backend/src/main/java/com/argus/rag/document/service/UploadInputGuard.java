package com.argus.rag.document.service;

import com.argus.rag.common.exception.BusinessException;
import org.springframework.util.StringUtils;

import java.util.Set;

/**
 * 文档上传入参的纯校验/规范化工具（无状态、无外部依赖）。
 *
 * <p>从 {@link DocumentUploadService} 下沉而来：这些函数不触碰 DAO、
 * 对象存储或会话状态，只做「参数 → 合法值或抛异常」的纯转换。
 * 抽出后可用单元测试独立覆盖边界，且不改变任何对外行为。</p>
 */
public final class UploadInputGuard {

    private UploadInputGuard() {
    }

    /** 文件名最大长度 */
    private static final int MAX_FILE_NAME_LENGTH = 255;
    /** Content-Type 最大长度 */
    private static final int MAX_CONTENT_TYPE_LENGTH = 128;
    /** 文件哈希最大长度 */
    private static final int MAX_FILE_HASH_LENGTH = 128;
    /** 文件扩展名最大长度 */
    private static final int MAX_FILE_EXT_LENGTH = 16;
    /** 支持直接上传/分片上传的文件扩展名白名单 */
    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of("txt", "md", "pdf", "docx");

    /**
     * 清洗文件名：去除路径、父目录引用，校验长度。
     *
     * @param fileName 原始文件名
     * @return 清洗后的文件名
     * @throws BusinessException 文件名为空或过长时抛出
     */
    public static String sanitizeFileName(String fileName) {
        if (!StringUtils.hasText(fileName)) {
            throw new BusinessException("文件名非法");
        }
        String normalizedFileName = StringUtils.cleanPath(fileName.trim());
        String sanitizedFileName = normalizedFileName.substring(normalizedFileName.lastIndexOf('/') + 1);
        if (!StringUtils.hasText(sanitizedFileName) || sanitizedFileName.length() > MAX_FILE_NAME_LENGTH) {
            throw new BusinessException("文件名非法");
        }
        return sanitizedFileName;
    }

    /**
     * 从文件名中提取扩展名并校验是否受支持。
     *
     * @param fileName 文件名
     * @return 小写的文件扩展名
     * @throws BusinessException 扩展名非法或类型不支持时抛出
     */
    public static String extractFileExt(String fileName) {
        int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex <= 0 || dotIndex == fileName.length() - 1) {
            throw new BusinessException("文件扩展名非法");
        }
        String fileExt = fileName.substring(dotIndex + 1).toLowerCase();
        if (fileExt.length() > MAX_FILE_EXT_LENGTH || !SUPPORTED_EXTENSIONS.contains(fileExt)) {
            throw new BusinessException("文件类型不支持");
        }
        return fileExt;
    }

    /**
     * 规范化 Content-Type，空值回落为通用二进制类型，过长则报错。
     *
     * @param contentType 原始 Content-Type
     * @return 规范化后的 Content-Type
     * @throws BusinessException Content-Type 过长时抛出
     */
    public static String normalizeContentType(String contentType) {
        if (!StringUtils.hasText(contentType)) {
            return "application/octet-stream";
        }
        String normalizedContentType = contentType.trim();
        if (normalizedContentType.length() > MAX_CONTENT_TYPE_LENGTH) {
            throw new BusinessException("文件类型描述过长");
        }
        return normalizedContentType;
    }

    /**
     * 规范化文件哈希，校验非空且长度合法。
     *
     * @param fileHash 原始文件哈希
     * @return 规范化后的文件哈希
     * @throws BusinessException fileHash 为空或过长时抛出
     */
    public static String normalizeFileHash(String fileHash) {
        if (!StringUtils.hasText(fileHash)) {
            throw new BusinessException("fileHash 非法");
        }
        String normalizedFileHash = fileHash.trim();
        if (normalizedFileHash.length() > MAX_FILE_HASH_LENGTH) {
            throw new BusinessException("fileHash 非法");
        }
        return normalizedFileHash;
    }

    /**
     * 校验 Long 值必须为正数。
     *
     * @param value   待校验的值
     * @param message 校验失败时的错误消息
     * @return 合法的值
     * @throws BusinessException value 为 null 或 &lt;= 0 时抛出
     */
    public static long requirePositive(Long value, String message) {
        if (value == null || value <= 0) {
            throw new BusinessException(message);
        }
        return value;
    }

    /**
     * 校验 Integer 值必须为正数。
     *
     * @param value   待校验的值
     * @param message 校验失败时的错误消息
     * @return 合法的值
     * @throws BusinessException value 为 null 或 &lt;= 0 时抛出
     */
    public static int requirePositive(Integer value, String message) {
        if (value == null || value <= 0) {
            throw new BusinessException(message);
        }
        return value;
    }

    /**
     * 校验 userId 为正数。
     */
    public static Long requirePositiveUserId(Long userId) {
        if (userId == null || userId <= 0) {
            throw new BusinessException("userId 非法");
        }
        return userId;
    }

    /**
     * 校验 fileSize 为正数。
     */
    public static long requirePositiveFileSize(Long fileSize) {
        if (fileSize == null || fileSize <= 0) {
            throw new BusinessException("fileSize 非法");
        }
        return fileSize;
    }

    /**
     * 校验文本非空，返回去除首尾空白的值。
     *
     * @param value   待校验的文本
     * @param message 校验失败时的错误消息
     * @return 去除首尾空白后的值
     * @throws BusinessException value 为空白时抛出
     */
    public static String requireText(String value, String message) {
        if (!StringUtils.hasText(value)) {
            throw new BusinessException(message);
        }
        return value.trim();
    }

    /**
     * 校验可复用的文件名（语义同 {@link #sanitizeFileName}）。
     */
    public static String validateReusableFileName(String fileName) {
        return sanitizeFileName(fileName);
    }
}