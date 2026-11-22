package com.argus.rag.assistant.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 写工具状态归一化契约（第十一步）：写状态比日报扫描更严——识别不了返回 null 让用户澄清，
 * 绝不悄悄归到"未开始"（写错状态是真实副作用，不能像日报那样容错兜底）。
 */
@DisplayName("任务进度写状态归一化（AssistantTaskProgressWriteTool）")
class AssistantTaskProgressWriteToolTest {

    @Test
    void canonicalStatusPassesThrough() {
        assertEquals("已完成", AssistantTaskProgressWriteTool.normalizeWriteStatus("已完成"));
        assertEquals("进行中", AssistantTaskProgressWriteTool.normalizeWriteStatus("进行中"));
        assertEquals("阻塞", AssistantTaskProgressWriteTool.normalizeWriteStatus("阻塞"));
        assertEquals("未开始", AssistantTaskProgressWriteTool.normalizeWriteStatus("未开始"));
    }

    @Test
    void colloquialVariantsMapped() {
        assertEquals("已完成", AssistantTaskProgressWriteTool.normalizeWriteStatus("完成了"));
        assertEquals("已完成", AssistantTaskProgressWriteTool.normalizeWriteStatus("done"));
        assertEquals("阻塞", AssistantTaskProgressWriteTool.normalizeWriteStatus("被阻塞了"));
        assertEquals("进行中", AssistantTaskProgressWriteTool.normalizeWriteStatus("正在做"));
        assertEquals("未开始", AssistantTaskProgressWriteTool.normalizeWriteStatus("还没开始"));
    }

    @Test
    void unrecognizedStatusReturnsNull() {
        // 关键契约：不识别 = null（让用户澄清），绝不静默归"未开始"
        assertNull(AssistantTaskProgressWriteTool.normalizeWriteStatus("差不多了"));
        assertNull(AssistantTaskProgressWriteTool.normalizeWriteStatus(""));
        assertNull(AssistantTaskProgressWriteTool.normalizeWriteStatus(null));
        assertNull(AssistantTaskProgressWriteTool.normalizeWriteStatus("随便"));
    }
}
