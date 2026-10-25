package com.argus.rag.excel.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 财务标准流水导入批次。
 */
@Data
@TableName("finance_import_batch")
public class FinanceImportBatch {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long documentId;

    /** 业务账本标识，如 BJ-2026、TS-A-2026、TS-B-2026 */
    private String bookKey;

    /** 同一账本的导入版本号，重导时递增 */
    private Integer batchVersion;

    /** 1=当前版本 0=已被新版本覆盖 */
    private Integer isCurrent;

    private String regionName;

    private String fileName;

    private String filePath;

    /** 导入文件 SHA-256（任务 10 文件幂等）；同哈希文件不允许重复导入。 */
    private String fileHash;

    private Long fileSize;

    private Integer totalSheets;

    private Integer importedSheets;

    private Integer totalRows;

    private Integer mappedRows;

    private Integer skippedRows;

    /** 1=处理中 2=成功 3=部分成功 4=失败 */
    private Integer status;

    private String errorSummary;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
