package com.argus.rag.excel.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 财务期间（月度）开关（任务 10）。
 * <p>期间关闭后该月流水禁止导入与编辑；重新打开必须填写原因。
 * 每次关闭/重开都记录操作人与原因，形成审计线索。</p>
 */
@TableName("finance_period")
@Data
public class FinancePeriod {

    public static final String STATUS_OPEN = "OPEN";
    public static final String STATUS_CLOSED = "CLOSED";

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 期间月份，格式 YYYY-MM，唯一。 */
    private String periodMonth;

    private String status;

    /** 关闭操作人用户 ID。 */
    private Long closedBy;

    /** 关闭原因（必填，审计）。 */
    private String closedReason;

    private LocalDateTime closedAt;

    /** 最近一次重新打开的操作人。 */
    private Long reopenedBy;

    /** 最近一次重新打开的原因（必填，审计）。 */
    private String reopenReason;

    private LocalDateTime reopenedAt;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
