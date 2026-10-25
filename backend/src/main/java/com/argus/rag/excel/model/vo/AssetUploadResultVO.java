package com.argus.rag.excel.model.vo;

/**
 * 工作簿图片上传结果视图。
 *
 * @param url         公开访问 URL
 * @param assetKey    对象存储键（含前缀 dir/owner）
 * @param contentType 实际解析/回退后的内容类型
 * @param size        字节数
 */
public record AssetUploadResultVO(
        String url,
        String assetKey,
        String contentType,
        long size
) {
}