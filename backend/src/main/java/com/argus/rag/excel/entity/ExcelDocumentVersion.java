package com.argus.rag.excel.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 文档版本快照主表。
 * <p>
 * 版本内容按“主表 → 版本 Sheet → 版本分块”三层落库，
 * 快照保存的是合并后的完整 celldata，回滚时直接重建当前工作簿。
 */
@TableName("excel_document_version")
@Data
public class ExcelDocumentVersion {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long documentId;

    /** 创建快照时 excel_document.version 的值 */
    private Long docVersion;

    /** 版本类型：MANUAL 手动 / STRUCTURE 结构改动 / CELL 单元格改动 / ROLLBACK_POINT 回滚点 */
    private String snapshotType;

    private Long creatorId;

    private String creatorName;

    private String summary;

    private Integer sheetCount;

    private Long cellCount;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
