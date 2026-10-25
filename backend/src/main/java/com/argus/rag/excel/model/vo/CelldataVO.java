package com.argus.rag.excel.model.vo;

import java.util.List;

/**
 * Celldata 响应视图对象。
 */
public record CelldataVO(
        Long sheetId,
        List<Object> celldata,
        int cellCount
) {}