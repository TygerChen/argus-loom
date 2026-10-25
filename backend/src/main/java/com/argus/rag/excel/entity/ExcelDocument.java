package com.argus.rag.excel.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Excel 文档主表实体（仅存元数据）
 * <p>
 * 单元格数据由 {@link ExcelSheet} + {@link ExcelSheetChunk} 分块管理，
 * 彻底解决十万级数据下单行存储过大的问题。
 */
@TableName("excel_document")
@Data
public class ExcelDocument {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String name;

    private Integer sheetCount;

    private String sheetNames;

    @Version
    private Long version;

    /** 状态：1正常 2回收站 3已删除 */
    private Integer status;

    private String filePath;

    private Long fileSize;

    private String creatorId;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    /** 移入回收站的操作人（用户名） */
    private String deletedBy;

    /** 移入回收站时间；恢复时清空 */
    private LocalDateTime deletedAt;

    /** 财务文件标记：true 时仅财务/领导角色可见，普通员工（含系统管理员）不可见 */
    private Boolean financeRequired;

}
