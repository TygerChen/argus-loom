package com.argus.rag.excel.model.dto;

import jakarta.validation.constraints.NotEmpty;

import java.util.List;
import java.util.Map;

/**
 * 全量工作簿保存请求。
 */
public record SaveWorkbookRequest(
        @NotEmpty(message = "sheets 不能为空")
        List<Map<String, Object>> sheets
) {}