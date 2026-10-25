package com.argus.rag.excel.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 文档级编辑栈 —— 决定“下一步撤销谁 / 重做谁”。
 */
@TableName("excel_edit_stack")
@Data
public class ExcelEditStack {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long documentId;

    /** 对应的历史批次 ID */
    private String batchId;

    /** 该批次撤销/重做所反向的原始批次 */
    private String parentBatchId;

    /** 操作类型：CHANGE 改值 / STRUCTURAL 结构改动 / UNDO 撤销 / REDO 重做 */
    private String action;

    /** 1 当前生效；0 已被反向或作废 */
    private Integer applied;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
