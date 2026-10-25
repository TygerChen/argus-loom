package com.argus.rag.excel.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** 某个文档版本的 Sheet 分块快照。 */
@TableName("excel_document_version_chunk")
@Data
public class ExcelDocumentVersionChunk {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long versionId;

    private Long versionSheetId;

    private Integer chunkIndex;

    private Integer rowStart;

    private Integer rowEnd;

    private String celldataJson;
}
