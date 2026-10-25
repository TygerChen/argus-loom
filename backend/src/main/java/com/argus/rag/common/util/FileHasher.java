package com.argus.rag.common.util;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 文件 / 字节数组的 SHA-256 摘要工具。
 *
 * <p>纯函数、无业务依赖，从 {@code DocumentUploadService} 中抽离，供全库复用</a>
 * （分片哈希去重、文件内容指纹等）。</p>
 */
public final class FileHasher {

    private FileHasher() {
    }

    /**
     * 计算字节数组的 SHA-256 摘要。
     *
     * @param data 待哈希的字节数组
     * @return 64 字符小写十六进制摘要
     * @throws IllegalStateException 当运行环境不提供 SHA-256 算法时（几乎不会发生）
     */
    public static String sha256Hex(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(data));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 algorithm is unavailable", exception);
        }
    }
}