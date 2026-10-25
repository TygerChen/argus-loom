package com.argus.rag.excel.audit;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 操作日志实体 — 记录每次单元格变更的完整快照。
 * <p>
 * 用途：审计追溯 + 撤销重做的数据源 + 历史版本回滚。
 * 批量操作（如粘贴 100 个单元格）通过 batchId 聚合。
 */
@TableName("excel_operation_log")
@Data
public class OperationLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 文档 ID */
    private Long documentId;

    /** 工作表 ID */
    private Long sheetId;

    /** 行号（0 起始） */
    private Integer rowIndex;

    /** 列号（0 起始） */
    private Integer colIndex;

    /** 操作类型：CELL_EDIT / BATCH_EDIT / SHEET_ADD / SHEET_DEL / SHEET_RENAME */
    private String operationType;

    /** 变更前的值（JSON），BATCH_EDIT 时存批量操作的 JSON 数组 */
    private String oldValue;

    /** 变更后的值（JSON） */
    private String newValue;

    /** 操作者用户 ID */
    private Long userId;

    /** 操作者显示名 */
    private String userDisplayName;

    /** 批量操作批次 ID，独立编辑时为 null */
    private String batchId;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}