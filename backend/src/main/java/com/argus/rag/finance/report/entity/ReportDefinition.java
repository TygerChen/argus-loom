package com.argus.rag.finance.report.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 报表定义：每用户一条日报流水线配置（数据源/规则/模板/推送），JSONB 描述。
 * 资金日报是第一个实例，pipeline_json 暂存解析与推送的差异化配置。
 */
@Data
@TableName("report_definition")
public class ReportDefinition {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long ownerUserId;

    /** 报表名，同用户内唯一（如「资金日报」） */
    private String name;

    /** 报表类型：引擎按类型分派到对应实现 */
    private String reportType;

    /** 流水线配置 JSON（数据源/规则链/推送渠道等） */
    private String pipelineJson;

    private Boolean enabled;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
