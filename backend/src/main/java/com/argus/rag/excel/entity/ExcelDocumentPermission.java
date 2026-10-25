package com.argus.rag.excel.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 文档访问控制表：记录“哪个文档分享给了哪个用户、什么权限”。
 */
@TableName("excel_document_permission")
@Data
public class ExcelDocumentPermission {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long documentId;

    private Long userId;

    /** 权限档位：OWNER 所有者 / EDITOR 编辑 / VIEWER 查看 */
    private String role;

    private Integer canShare;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime grantedAt;
}
