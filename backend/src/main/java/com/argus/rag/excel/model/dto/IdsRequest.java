package com.argus.rag.excel.model.dto;

import java.util.List;

/** 批量操作请求：文档 ID 列表。 */
public record IdsRequest(List<Long> ids) {
}
