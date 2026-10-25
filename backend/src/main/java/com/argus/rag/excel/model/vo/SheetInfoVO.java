package com.argus.rag.excel.model.vo;

import java.util.List;
import java.util.Map;

/**
 * Sheet 元信息视图对象。
 */
public record SheetInfoVO(
        Long sheetId,
        Integer sheetIndex,
        String sheetName,
        Integer totalRows,
        Integer totalCols,
        Integer chunkCount,
        Integer active,
        Map<String, Object> config,
        Map<String, Object> mergeConfig,
        Map<String, Object> columnLen,
        Map<String, Object> rowLen,
        Map<String, Object> hyperlink,
        Map<String, Object> images,
        List<Object> conditionFormat,
        List<Object> chart
) {}