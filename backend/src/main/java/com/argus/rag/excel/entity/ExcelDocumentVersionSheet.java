package com.argus.rag.excel.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** 某个文档版本的 Sheet 元信息快照。 */
@TableName("excel_document_version_sheet")
@Data
public class ExcelDocumentVersionSheet {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long versionId;

    private Integer sheetIndex;

    private String sheetName;

    private Integer totalRows;

    private Integer totalCols;

    private Integer chunkCount;

    private String mergeConfigJson;

    private String columnLenJson;

    private String rowLenJson;

    private String configJson;

    private String hyperlinkConfigJson;

    private String imagesConfigJson;

    private String conditionFormatJson;

    private String chartJson;

    private Integer active;
}
