package com.argus.rag.excel.model.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 重命名文档请求。
 */
public record RenameDocumentRequest(
        @NotBlank(message = "名称不能为空")
        String name
) {}