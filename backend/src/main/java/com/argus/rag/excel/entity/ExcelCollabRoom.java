package com.argus.rag.excel.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 协同在线房间：每个文档下的一条 WebSocket 连接状态。
 */
@TableName("excel_collab_room")
@Data
public class ExcelCollabRoom {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long documentId;

    private Long actorId;

    private String actorName;

    private String sessionId;

    private String nodeId;

    private Integer canEdit;

    private Long sheetId;

    private Integer cursorRow;

    private Integer cursorCol;

    private String presenceJson;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
