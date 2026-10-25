package com.argus.rag.finance.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * AI 财务简报快照：内容、模型与生成人完整留痕。
 */
@TableName("finance_report")
@Data
public class FinanceReport {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String title;

    private String reportMonth;

    private String contentMarkdown;

    private String model;

    private Long generatedBy;

    private String generatedByName;

    /** 简报状态：SUCCESS 成功 / FAILED 失败 */
    private String status;

    private String errorMessage;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
