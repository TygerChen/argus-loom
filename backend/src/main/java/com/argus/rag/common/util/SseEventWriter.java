package com.argus.rag.common.util;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * SSE（Server-Sent Events）事件写入工具。
 *
 * <p>统一 QaController 与 AssistantChatController 此前各自手写的 event/data 拼装逻辑，
 * 按 SSE 规范写出 {@code event:<name>\n} + {@code data:<payload>\n\n} 并立即 flush。</p>
 */
public final class SseEventWriter {

    private SseEventWriter() {}

    /**
     * 写出一条 SSE 事件（data 为原始字符串，不转义换行）。
     *
     * @param output 响应输出流
     * @param name   事件名
     * @param data   事件数据原文
     * @throws IOException 客户端断开或写出失败
     */
    public static void write(OutputStream output, String name, String data) throws IOException {
        output.write(("event:" + name + "\n").getBytes(StandardCharsets.UTF_8));
        output.write(("data:" + data + "\n\n").getBytes(StandardCharsets.UTF_8));
        output.flush();
    }
}