package com.argus.rag.excel.model.dto;

import java.time.Instant;
import java.util.Map;

/**
 * WebSocket 协作消息协议。
 * <p>
 * 所有实时协作消息均通过此结构在客户端与服务端之间传递。
 * 消息通过 STOMP 的 /topic/excel/{documentId} 广播到文档的所有在线用户。
 */
public record CollabMessage(
        /** 消息类型：CELL_EDIT / STRUCTURE_CHANGE / USER_JOIN / USER_LEAVE */
        String type,
        /** 文档 ID */
        Long documentId,
        /** 工作表 ID */
        Long sheetId,
        /** 行号（0 起始） */
        Integer r,
        /** 列号（0 起始） */
        Integer c,
        /** 单元格值（Luckysheet/Univer v 对象格式） */
        Map<String, Object> v,
        /** 操作者用户 ID */
        Long userId,
        /** 操作者显示名 */
        String displayName,
        /** 客户端发送时的时间戳——用于 LWW 冲突解决 */
        long timestamp,
        /** 服务端接收时的时间戳，客户端不传 */
        long serverTimestamp,
        /** 客户端生成的批次 ID（用于广播去重与前端忽略自己的回播） */
        String requestId,
        /** 客户端提交编辑时所基于的文档版本号；与服务器当前 version 不一致即冲突（单元格级乐观锁）。null=旧客户端未启用校验。 */
        Long baseRevision
) {
    /** 工厂方法：客户端发送的消息（无 serverTimestamp） */
    public static CollabMessage fromClient(
            String type, Long documentId, Long sheetId, Integer r, Integer c,
            Map<String, Object> v, Long userId, String displayName) {
        return new CollabMessage(
                type, documentId, sheetId, r, c, v,
                userId, displayName,
                Instant.now().toEpochMilli(), 0L, null, null);
    }

    /** 工厂方法：服务端广播的消息（附加 serverTimestamp） */
    public static CollabMessage forBroadcast(CollabMessage clientMsg) {
        return new CollabMessage(
                clientMsg.type, clientMsg.documentId, clientMsg.sheetId,
                clientMsg.r, clientMsg.c, clientMsg.v,
                clientMsg.userId, clientMsg.displayName,
                clientMsg.timestamp, Instant.now().toEpochMilli(),
                clientMsg.requestId, clientMsg.baseRevision);
    }
}
