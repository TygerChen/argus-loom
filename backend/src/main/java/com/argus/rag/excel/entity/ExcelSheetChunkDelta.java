package com.argus.rag.excel.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Excel 单元格增量实体 — P4 chunk 级增量保存的落表单元。
 * <p>
 * 批量编辑先写增量，读取时与分块基数合并；同一 (sheetId, chunkIndex, row, col)
 * 只保留最新一条，达到阈值后压缩回分块基数。
 */
@TableName("excel_sheet_chunk_delta")
@Data
public class ExcelSheetChunkDelta {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long documentId;

    private Long sheetId;

    private Integer chunkIndex;

    private Integer cellRow;

    private Integer cellCol;

    /** 单元格值载荷（LS celldata 的 v 对象 JSON）；"{}" 表示删除该单元格 */
    private String cellValue;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime updateTime;
}
