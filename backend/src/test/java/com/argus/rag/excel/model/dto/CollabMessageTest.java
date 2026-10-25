package com.argus.rag.excel.model.dto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link CollabMessage} 协作消息协议 record 契约（纯 JVM/零 mock/零网络）。
 * fromClient：客户端发送载荷——timestamp=当前毫秒、serverTimestamp=0、requestId=null，
 * 其余字段原样透传；forBroadcast：以客户端消息为基础复制全字段、timestamp 沿用客户端值、
 * serverTimestamp 刷新为当前服务器毫秒。
 */
@DisplayName("协作消息协议（CollabMessage）")
class CollabMessageTest {

    private static final Map<String, Object> CELL = Map.of("v", "你好", "ct", "{t:0}");

    @Test
    void fromClientStampsAndClearsServerFields() {
        CollabMessage msg = CollabMessage.fromClient(
                "CELL_EDIT", 7L, 3L, 2, 4, CELL, 9L, "李四");
        assertEquals("CELL_EDIT", msg.type());
        assertEquals(7L, msg.documentId());
        assertEquals(3L, msg.sheetId());
        assertEquals(2, msg.r());
        assertEquals(4, msg.c());
        assertEquals(CELL, msg.v());
        assertEquals(9L, msg.userId());
        assertEquals("李四", msg.displayName());
        assertEquals(0L, msg.serverTimestamp());
        assertNull(msg.requestId());
        assertTrue(msg.timestamp() > 0);
    }

    @Test
    void forBroadcastRefreshesServerTimestampOnly() {
        CollabMessage client = CollabMessage.fromClient(
                "USER_JOIN", 11L, 2L, 0, 0, null, 5L, "王五");
        // 固定客户端时间戳，保证可断言 serverTimestamp 刷新值
        long fixedTimestamp = 1_700_000_000_000L;
        CollabMessage patched = new CollabMessage(
                client.type(), client.documentId(), client.sheetId(),
                client.r(), client.c(), client.v(),
                client.userId(), client.displayName(),
                fixedTimestamp, 0L, "req-1", null);

        CollabMessage broadcast = CollabMessage.forBroadcast(patched);

        // 客户端字段原样保留
        assertEquals("USER_JOIN", broadcast.type());
        assertEquals("王五", broadcast.displayName());
        assertEquals(fixedTimestamp, broadcast.timestamp());
        assertEquals("req-1", broadcast.requestId());
        assertEquals("王五", broadcast.displayName());
        // 仅 serverTimestamp 刷新为新的当前时刻（>= 客户端时间戳）
        assertTrue(broadcast.serverTimestamp() >= fixedTimestamp);
        assertTrue(broadcast.serverTimestamp() > 0);
    }

    @Test
    void broadcastKeepsCellValueAndSheetCoordinates() {
        CollabMessage client = CollabMessage.fromClient(
                "STRUCTURE_CHANGE", 3L, 9L, 4, 7, CELL, 2L, "赵六");
        CollabMessage broadcast = CollabMessage.forBroadcast(client);
        assertEquals(3L, broadcast.documentId());
        assertEquals(9L, broadcast.sheetId());
        assertEquals(4, broadcast.r());
        assertEquals(7, broadcast.c());
        assertEquals(CELL, broadcast.v());
        assertEquals(2L, broadcast.userId());
        // 构造后 cell map 引用同一实例（record 不拷贝 v）
        assertEquals(CELL, broadcast.v());
    }
}
