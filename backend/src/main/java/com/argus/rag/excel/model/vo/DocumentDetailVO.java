package com.argus.rag.excel.model.vo;

import java.util.List;

/**
 * 文档详情视图对象（含 Sheet 列表，不含 celldata）。
 */
public record DocumentDetailVO(
        Long id,
        String name,
        Integer sheetCount,
        Long version,
        List<SheetInfoVO> sheets,
        String permission,
        boolean canEdit,
        boolean canManage
) {}
