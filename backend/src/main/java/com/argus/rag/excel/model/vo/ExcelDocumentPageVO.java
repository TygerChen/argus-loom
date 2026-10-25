package com.argus.rag.excel.model.vo;

import java.util.List;

/**
 * 文档分页查询的返回值（不可变 record）。
 * <p>承载文档列表分页数据：{@code total} 总条数、{@code pages} 总页数、{@code current} 当前页码，以及 {@code records} 当前页的文档摘要列表。</p>
 */
public record ExcelDocumentPageVO(
        long total,
        long pages,
        long current,
        List<ExcelDocumentListItemVO> records
) {}
