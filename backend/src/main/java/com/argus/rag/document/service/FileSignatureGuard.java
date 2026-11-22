package com.argus.rag.document.service;

import com.argus.rag.common.exception.BusinessException;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.io.InputStream;

/**
 * 文件签名守卫 —— 上传入口的"扩展名 vs 真实内容"一致性校验（magic bytes）。
 *
 * <p><b>它解决什么问题</b>：扩展名白名单（txt/md/pdf/docx）只挡住了"声明可执行"的
 * 文件，挡不住<b>伪装扩展名</b>——把 .exe 改名 .pdf 照样过白名单。方案（文档上传
 * 入口）明确要求"扩展名与真实内容是否一致"。本守卫读文件头几个字节比对
 * 已知格式签名：PDF 头必须是 {@code %PDF-}，docx 是 ZIP 容器（{@code PK\003\004}），
 * txt/md 无固定签名则退化为"头部不得含 NUL 字节"的二进制伪装启发式。</p>
 *
 * <p><b>定位与降级语义</b>：这是<b>入口的快速拒绝</b>，不是内容安全扫描——
 * 深层损坏/加密/宏文件由 ETL 解析层失败兜底（标 FAILED、保留原始文件、可重试），
 * 两层各管一段。校验失败在入口直接 400，文件绝不落 MinIO。</p>
 */
public final class FileSignatureGuard {

    private FileSignatureGuard() {
    }

    /** 需要读取并比对的文件头字节数（覆盖 %PDF- 与 PK 头足够）。 */
    public static final int HEAD_BYTES = 8;

    private static final byte[] PDF_SIGNATURE = "%PDF-".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    private static final byte[] ZIP_SIGNATURE = {'P', 'K', 0x03, 0x04};

    /**
     * 校验文件头与声明的扩展名一致；不一致抛业务异常（入口 400 拒绝）。
     *
     * @param head    文件头字节（{@link #readHead} 产出；空数组视为空文件）
     * @param fileExt 小写扩展名（txt/md/pdf/docx，白名单已保证）
     * @throws BusinessException 空文件、或签名与扩展名不符
     */
    public static void verify(byte[] head, String fileExt) {
        if (head == null || head.length == 0) {
            throw new BusinessException("上传文件内容为空");
        }
        if (!StringUtils.hasText(fileExt)) {
            throw new BusinessException("文件扩展名非法");
        }
        switch (fileExt) {
            case "pdf" -> requireSignature(head, PDF_SIGNATURE, "文件内容与 .pdf 扩展名不符");
            case "docx" -> requireSignature(head, ZIP_SIGNATURE, "文件内容与 .docx 扩展名不符");
            case "txt", "md" -> rejectIfBinaryHead(head, "文件内容疑似二进制伪装成 " + fileExt);
            default -> {
                // 白名单之外扩展名已被 UploadInputGuard 拒绝，理论上到不了这里；
                // 防御式放行仅记录语义，不阻塞。
            }
        }
    }

    /**
     * 从流中读取文件头（最多 {@value #HEAD_BYTES} 字节，不足读满即止）。
     * <p>只读头部、立即关闭流——不做全量读取，入口校验保持零重担。</p>
     *
     * @param input 文件内容流（由调用方打开并关闭）
     * @return 实际读到的头字节（流为空返回空数组）
     * @throws BusinessException 读取失败
     */
    public static byte[] readHead(InputStream input) {
        if (input == null) {
            throw new BusinessException("无法读取上传文件内容");
        }
        byte[] buffer = new byte[HEAD_BYTES];
        int read = 0;
        try {
            while (read < HEAD_BYTES) {
                int n = input.read(buffer, read, HEAD_BYTES - read);
                if (n < 0) {
                    break;
                }
                read += n;
            }
            return read == HEAD_BYTES ? buffer : java.util.Arrays.copyOf(buffer, read);
        } catch (IOException exception) {
            throw new BusinessException("读取上传文件失败");
        }
    }

    /** 头部必须以指定签名开始。 */
    private static void requireSignature(byte[] head, byte[] signature, String message) {
        if (head.length < signature.length) {
            throw new BusinessException(message);
        }
        for (int i = 0; i < signature.length; i++) {
            if (head[i] != signature[i]) {
                throw new BusinessException(message);
            }
        }
    }

    /** 文本类格式的启发式：真实文本头部不会出现 NUL 字节，出现即视为二进制伪装。 */
    private static void rejectIfBinaryHead(byte[] head, String message) {
        for (byte b : head) {
            if (b == 0) {
                throw new BusinessException(message);
            }
        }
    }
}
