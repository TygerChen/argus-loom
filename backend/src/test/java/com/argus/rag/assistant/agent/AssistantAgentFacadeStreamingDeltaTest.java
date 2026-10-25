package com.argus.rag.assistant.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AssistantAgentFacade} 流式/超时/缩写三组纯函数行为锁
 * （纯 JVM、static 同包直调、零构造——该类实例态含真实守护线程池，故不做实例化）。
 * <p>锁契约：normalizeStreamingDelta 剥离已累计前缀成增量、prefix 不匹配时原样返回；
 * isTimeoutException 沿 cause 链识别 TimeoutException（Reactor ReactiveException 包裹亦可命中）；
 * abbreviate 将 CRLF/换行折叠为空格并 trim、整段超 200 截断加省略号。</p>
 */
@DisplayName("Assistant 流式增量/超时识别/摘要缩写（AssistantAgentFacade 纯 static 族）")
class AssistantAgentStreamingDeltaTest {

    // ---------- normalizeStreamingDelta ----------

    @Test
    void nullOrBlankCandidateIsEmptyDelta() {
        assertEquals("", AssistantAgentFacade.normalizeStreamingDelta(null, "已累计"));
        assertEquals("", AssistantAgentFacade.normalizeStreamingDelta("   ", "已累计"));
    }

    @Test
    void noAccumulatedTextReturnsCandidateWhole() {
        assertEquals("你好，我是助手", AssistantAgentFacade.normalizeStreamingDelta("你好，我是助手", ""));
    }

    @Test
    void stripsMatchingAccumulatedPrefix() {
        // 模型反复回传"截至当前全文" → 裁掉已 append 的前缀，只给增量
        assertEquals("delta", AssistantAgentFacade.normalizeStreamingDelta("已累计delta", "已累计"));
    }

    @Test
    void fullPrefixMatchYieldsEmptyDelta() {
        // 候选已完全被累计前缀覆盖（无新增内容）→ 增量空
        assertEquals("", AssistantAgentFacade.normalizeStreamingDelta("已累计", "已累计"));
    }

    @Test
    void nonPrefixCandidateReturnsWhole() {
        // 前缀不匹配（服务端重发首段或前缀不同）→ 原样返还，避免丢字
        assertEquals("你好世界", AssistantAgentFacade.normalizeStreamingDelta("你好世界", "信息"));
    }

    @Test
    void partialChinesePrefixStillSliced() {
        assertEquals("界！", AssistantAgentFacade.normalizeStreamingDelta("你好世界！", "你好世"));
    }

    // ---------- isTimeoutException ----------

    @Test
    void directTimeoutRecognized() {
        assertTrue(AssistantAgentFacade.isTimeoutException(new TimeoutException("超时")));
    }

    @Test
    void timeoutDeepInCauseChainRecognized() {
        CauseRoot root = new CauseRoot(new TimeoutException("深层超时"));
        assertTrue(AssistantAgentFacade.isTimeoutException(new IllegalStateException("包装", root)));
    }

    @Test
    void nonTimeoutReturnsFalse() {
        assertFalse(AssistantAgentFacade.isTimeoutException(new IllegalArgumentException("普通错误")));
        assertFalse(AssistantAgentFacade.isTimeoutException(null));
    }

    // ---------- abbreviate ----------

    @Test
    void abbreviateNullAndWhitespaceTrimmed() {
        assertNull(AssistantAgentFacade.abbreviate(null));
        assertEquals("a b", AssistantAgentFacade.abbreviate("  a\r\nb  "));
    }

    @Test
    void abbreviateTruncatesOver200WithEllipsis() {
        String longText = "字".repeat(250);
        String out = AssistantAgentFacade.abbreviate(longText);
        assertEquals(203, out.length());
        assertTrue(out.endsWith("..."));
        assertTrue(out.startsWith("字".repeat(200)));
    }

    @Test
    void abbreviateKeepsAtOrUnder200() {
        String exact200 = "中".repeat(200);
        assertEquals(exact200, AssistantAgentFacade.abbreviate(exact200));
        assertEquals("简", AssistantAgentFacade.abbreviate("简"));
    }

    /** 为 cause 链测试提供非标准低层 cause（不被顶层 instanceof 直接命中）。 */
    private static final class CauseRoot extends RuntimeException {
        private static final long serialVersionUID = 1L;

        CauseRoot(Throwable cause) {
            super(cause);
        }
    }
}