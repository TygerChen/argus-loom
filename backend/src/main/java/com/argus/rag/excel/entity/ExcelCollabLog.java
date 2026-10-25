package com.argus.rag.excel.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 协同操作日志：一次 WebSocket 协同写入对应一行。
 */
@TableName("excel_collab_log")
@Data
public class ExcelCollabLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long documentId;

    private Long sheetId;

    private Integer rowNo;

    private Integer colNo;

    private Long actorId;

    private String actorName;

    private String requestId;

    private Long baseRev;

    private Long serverRev;

    private String valueText;

    private String resultValueText;

    private Integer merged;

    private Integer conflict;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
