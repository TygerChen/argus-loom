package com.argus.rag.excel.model.vo;

import java.time.LocalDateTime;

/**
 * 文档列表项视图对象。
 */
public record ExcelDocumentListItemVO(
        Long id,
        String name,
        Integer sheetCount,
        String sheetNames,
        Long version,
        Long fileSize,
        LocalDateTime createTime,
        LocalDateTime updateTime,
        String permission,
        boolean canEdit,
        boolean canManage
) {}
