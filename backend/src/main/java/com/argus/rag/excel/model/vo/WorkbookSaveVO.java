package com.argus.rag.excel.model.vo;

import java.util.Map;

/**
 * 工作簿保存结果视图对象。
 */
public record WorkbookSaveVO(
        int sheetCount,
        Map<Integer, Long> sheetIdMap
) {}