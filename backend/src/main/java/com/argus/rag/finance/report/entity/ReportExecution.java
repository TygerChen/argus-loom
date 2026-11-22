package com.argus.rag.finance.report.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 报表执行记录：一定义一基准日一行，重跑覆盖（先清后写，天然幂等）。
 * result_json 是前端看板与钉钉推送共用的数据源，与页面展示严格同源。
 */
@Data
@TableName("report_execution")
public class ReportExecution {

    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_SUCCEEDED = "SUCCEEDED";
    public static final String STATUS_FAILED = "FAILED";

    public static final String PUSH_PENDING = "PENDING";
    public static final String PUSH_SENT = "SENT";
    public static final String PUSH_SKIPPED = "SKIPPED";
    public static final String PUSH_FAILED = "FAILED";

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long definitionId;

    /** 报表基准日 */
    private LocalDate bizDate;

    private String status;

    /** 计算产物 JSON（看板数据源） */
    private String resultJson;

    private String aiBriefText;

    private String htmlContent;

    private String pushStatus;

    private String pushDetail;

    private String errorMessage;

    private Long durationMs;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
