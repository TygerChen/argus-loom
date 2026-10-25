package com.argus.rag.excel.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 单元格级操作历史 —— 撤销/重做的账本。
 */
@TableName("excel_cell_history")
@Data
public class ExcelCellHistory {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long documentId;

    private Long sheetId;

    /** 单元格行号；STRUCTURAL 记录固定为 -1 */
    private Integer rowNo;

    /** 单元格列号；STRUCTURAL 记录固定为 -1 */
    private Integer colNo;

    /** 变更类型：CHANGE 改值 / STRUCTURAL 结构改动 / UNDO 撤销 / REDO 重做 */
    private String action;

    /** 变更前该格 v 对象 JSON；NULL 表示删除/无值 */
    private String beforeValueJson;

    /** 变更后该格 v 对象 JSON；NULL 表示删除/无值 */
    private String afterValueJson;

    private Long baseRev;

    private Long afterRev;

    /** 一次用户操作的批次 ID */
    private String batchId;

    private Long operatorId;

    private String operatorName;

    /** 变更来源：COLLAB 协作编辑 / HTTP 接口 / WORKBOOK 手工 */
    private String source;

    private Integer merged;

    private Integer conflict;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
