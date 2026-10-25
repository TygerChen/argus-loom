package com.argus.rag.excel.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 协同事件 Outbox（任务 9）。
 *
 * <p>业务事务内只写这张表（状态 PENDING），由 {@code ExcelCollabEventPublisher}
 * 在事务提交后异步广播并标记 SENT；广播失败的事件保持 PENDING，由定时清扫重发，
 * 保证"先持久化、后广播"的核心语义。</p>
 */
@TableName("excel_collab_outbox")
@Data
public class ExcelCollabOutbox {

    /** Outbox 状态：待发送。 */
    public static final String STATUS_PENDING = "PENDING";

    /** Outbox 状态：已广播。 */
    public static final String STATUS_SENT = "SENT";

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 事件所属的 Excel 文档 ID（决定 WebSocket 广播主题）。 */
    private Long documentId;

    /** 事件类型，例如 CELL_EDIT。 */
    private String eventType;

    /** 广播负载的 JSON 串（与实时广播的消息体字段一致）。 */
    private String payloadJson;

    private String status;

    private LocalDateTime createdAt;
}
