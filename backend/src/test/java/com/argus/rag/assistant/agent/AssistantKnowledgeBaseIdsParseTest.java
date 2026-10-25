package com.argus.rag.assistant.agent;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@link AssistantKnowledgeBaseTool#parsePositiveLong} 与 {@link AssistantKnowledgeBaseTool#parseCommaSeparated}
 * 组 ID 解析行为锁。
 * <p>
 * 两函数纯 String/Number→List&lt;Long&gt;、无 IO：仅接受正 Long（Number 取 longValue&gt;0；
 * String 去空白解析 &gt;0），其余返回 null；逗号分隔逐 token 解析并丢弃非正/非法。置为 static 同包可见直接测试。
 * </p>
 */
class AssistantKnowledgeBaseIdsParseTest {

    // ---------- parsePositiveLong ----------

    @Test
    void numberInputsAcceptedWhenPositive() {
        assertEquals(42L, AssistantKnowledgeBaseTool.parsePositiveLong(42L));
        assertEquals(7L, AssistantKnowledgeBaseTool.parsePositiveLong(Integer.valueOf(7)));
        assertEquals(Long.MAX_VALUE, AssistantKnowledgeBaseTool.parsePositiveLong(Long.MAX_VALUE));
    }

    @Test
    void numbersNonPositiveOrZeroRejected() {
        assertEquals(null, AssistantKnowledgeBaseTool.parsePositiveLong(-3L));
        assertEquals(null, AssistantKnowledgeBaseTool.parsePositiveLong(0L));
    }

    @Test
    void stringParsedWhenHasText() {
        assertEquals(5L, AssistantKnowledgeBaseTool.parsePositiveLong("5"));
        assertEquals(5L, AssistantKnowledgeBaseTool.parsePositiveLong(" 5 "));
    }

    @Test
    void stringsNegativeZeroBlankOrNonNumericRejected() {
        assertEquals(null, AssistantKnowledgeBaseTool.parsePositiveLong("-3"));
        assertEquals(null, AssistantKnowledgeBaseTool.parsePositiveLong("0"));
        assertEquals(null, AssistantKnowledgeBaseTool.parsePositiveLong(""));
        assertEquals(null, AssistantKnowledgeBaseTool.parsePositiveLong("   "));
        assertEquals(null, AssistantKnowledgeBaseTool.parsePositiveLong(null));
        assertEquals(null, AssistantKnowledgeBaseTool.parsePositiveLong("5.5"));
        assertEquals(null, AssistantKnowledgeBaseTool.parsePositiveLong("abc"));
    }

    // ---------- parseCommaSeparated ----------

    @Test
    void commaListParsedAndTrimmed() {
        assertEquals(List.of(1L, 2L, 3L), AssistantKnowledgeBaseTool.parseCommaSeparated("1,2,3"));
        assertEquals(List.of(1L, 2L), AssistantKnowledgeBaseTool.parseCommaSeparated(" 1 , 2"));
    }

    @Test
    void blankTokensAndEmptyInputYieldEmptyList() {
        assertEquals(List.of(), AssistantKnowledgeBaseTool.parseCommaSeparated(""));
        assertEquals(List.of(), AssistantKnowledgeBaseTool.parseCommaSeparated("   "));
    }

    @Test
    void nonPositiveAndMalformedTokensSkipped() {
        assertEquals(List.of(2L), AssistantKnowledgeBaseTool.parseCommaSeparated("-1,2"));
        assertEquals(List.of(2L), AssistantKnowledgeBaseTool.parseCommaSeparated("abc,2"));
        assertEquals(List.of(1L, 3L), AssistantKnowledgeBaseTool.parseCommaSeparated("1,0,3"));
        assertEquals(List.of(1L, 3L), AssistantKnowledgeBaseTool.parseCommaSeparated("1,,3"));
    }

    // ---------- 收敛后一致性：KB 与 Structured 分身应同共享助手一致 ----------

    @Test
    void toolDelegatesAgreeWithSharedParser() {
        Object[] samples = {null, "", "   ", "45", "-3", "5.5", "abc", 42L, Integer.valueOf(7), 0L};
        for (Object sample : samples) {
            assertEquals(AssistantIds.parsePositiveLong(sample),
                    AssistantKnowledgeBaseTool.parsePositiveLong(sample),
                    "KB and shared disagree for: " + sample);
            assertEquals(AssistantIds.parsePositiveLong(sample),
                    StructuredDataQueryTool.parsePositiveLong(sample),
                    "SQD and shared disagree for: " + sample);
        }
    }
}