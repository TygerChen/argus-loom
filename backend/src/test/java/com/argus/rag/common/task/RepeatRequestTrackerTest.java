package com.argus.rag.common.task;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 重复请求识别器契约：窗口内同用户同通道同输入 → 重复疑似；
 * 不同用户/不同输入/过窗重现都不算；空白输入永不标记。
 */
@DisplayName("重复请求识别器（RepeatRequestTracker）")
class RepeatRequestTrackerTest {

    @Test
    void flagsImmediateRepeatWithinWindow() {
        RepeatRequestTracker tracker = new RepeatRequestTracker(10);
        assertFalse(tracker.markAndCheckRepeat(7L, TaskChannel.KB_QA, "金蝶怎么反结账"), "首次出现不算重复");
        assertTrue(tracker.markAndCheckRepeat(7L, TaskChannel.KB_QA, "金蝶怎么反结账"), "窗口内重现应标记");
    }

    @Test
    void differentUserOrTextIsNotRepeat() {
        RepeatRequestTracker tracker = new RepeatRequestTracker(10);
        tracker.markAndCheckRepeat(7L, TaskChannel.KB_QA, "金蝶怎么反结账");
        assertFalse(tracker.markAndCheckRepeat(8L, TaskChannel.KB_QA, "金蝶怎么反结账"), "换用户不算重复");
        assertFalse(tracker.markAndCheckRepeat(7L, TaskChannel.KB_QA, "应收单怎么下推"), "换问题不算重复");
        assertFalse(tracker.markAndCheckRepeat(7L, TaskChannel.ASSISTANT, "金蝶怎么反结账"), "换通道不算重复");
    }

    @Test
    void normalizedWhitespaceStillCountsAsRepeat() {
        RepeatRequestTracker tracker = new RepeatRequestTracker(10);
        tracker.markAndCheckRepeat(7L, TaskChannel.KB_QA, "金蝶怎么 反结账？");
        assertTrue(tracker.markAndCheckRepeat(7L, TaskChannel.KB_QA, "金蝶怎么  反结账？"),
                "多一个空格属归一化等价，应识别为重复（刷请求常这么绕）");
    }

    @Test
    void blankInputNeverMarks() {
        RepeatRequestTracker tracker = new RepeatRequestTracker(10);
        assertFalse(tracker.markAndCheckRepeat(7L, TaskChannel.KB_QA, null));
        assertFalse(tracker.markAndCheckRepeat(7L, TaskChannel.KB_QA, "  "));
    }
}
