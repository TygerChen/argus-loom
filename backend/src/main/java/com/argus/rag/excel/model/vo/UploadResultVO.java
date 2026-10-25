package com.argus.rag.excel.model.vo;

import java.util.List;

/**
 * 文件上传结果视图对象。
 */
public record UploadResultVO(
        Long documentId,
        String name,
        Integer sheetCount,
        List<SheetMetaVO> sheets
) {}