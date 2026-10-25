package com.argus.rag.excel.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 异步导出任务。
 */
@TableName("excel_export_task")
@Data
public class ExcelExportTask {

    public static final int STATUS_PENDING = 0;
    public static final int STATUS_RUNNING = 1;
    public static final int STATUS_SUCCESS = 2;
    public static final int STATUS_FAILED = 3;
    public static final int STATUS_CANCELLED = 4;
    public static final int STATUS_EXPIRED = 5;

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long documentId;

    private String taskNo;

    private Long userId;

    private String username;

    private Integer status;

    private Integer progress;

    private String fileName;

    private String objectKey;

    private String downloadUrl;

    private String errorMessage;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    private LocalDateTime finishTime;

    /** 导出文件过期时间；超过后任务被清扫为 EXPIRED，产物不再可下载。 */
    private LocalDateTime expireAt;
}
