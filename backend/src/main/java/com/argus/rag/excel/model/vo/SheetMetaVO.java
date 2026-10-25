package com.argus.rag.excel.model.vo;

/**
 * Sheet 元数据视图对象（用于上传响应，精简版）。
 */
public record SheetMetaVO(
        Long sheetId,
        Integer sheetIndex,
        String sheetName,
        Integer totalRows,
        Integer totalCols,
        Integer chunkCount,
        Integer active
) {}