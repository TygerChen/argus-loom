package com.argus.rag.excel.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Excel Sheet 数据分块实体 — 将一个 Sheet 的 celldata 按行范围切片存储
 * <p>
 * 每块约1000行，单块 JSON 通常在几百KB以内，可按需加载。
 * 对应关系：ExcelDocument → ExcelSheet → ExcelSheetChunk (1:N:N)
 */
@TableName("excel_sheet_chunk")
@Data
public class ExcelSheetChunk {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属文档 ID（冗余，方便按文档删除） */
    private Long documentId;

    private Long sheetId;

    /** 块序号（0起始，按行范围升序） */
    private Integer chunkIndex;

    /** 该块包含的起始行号（含，0起始） */
    private Integer rowStart;

    /** 该块包含的结束行号（含，0起始） */
    private Integer rowEnd;

    /**
     * 该块的 celldata JSON 数组
     * 格式：[{"r":0,"c":0,"v":{"v":"值","m":"值","ct":{"fa":"General","t":"s"}}},...]
     */
    private String celldataJson;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

}