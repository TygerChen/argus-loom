package com.argus.rag.assistant.memory.longterm;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MemoryContextBuilderTest {

    private final MemoryContextBuilder builder = new MemoryContextBuilder();

    @Test
    @DisplayName("empty list returns blank")
    void emptyListBlank() {
        assertEquals("", builder.build(List.of()));
        assertEquals("", builder.build(null));
    }

    @Test
    @DisplayName("valid memory includes boundary tags")
    void includesBoundaryTags() {
        UserMemoryEntity m = new UserMemoryEntity();
        m.setMemoryType("PREFERENCE");
        m.setContent("use Markdown for reports");
        m.setSource("USER_EXPLICIT");
        var scored = new MemoryContextBuilder.ScoredMemory(m, 0.9, 98.0);
        String result = builder.build(List.of(scored));
        assertTrue(result.contains("BEGIN MEMORY REFERENCE"));
        assertTrue(result.contains("END MEMORY REFERENCE"));
        assertTrue(result.contains("PREFERENCE"));
        assertTrue(result.contains("Markdown"));
    }

    @Test
    @DisplayName("should limit to 5 memories")
    void limitFive() {
        var list = new java.util.ArrayList<MemoryContextBuilder.ScoredMemory>();
        for (int i = 0; i < 10; i++) {
            UserMemoryEntity m = new UserMemoryEntity();
            m.setMemoryType("FACT");
            m.setContent("fact #" + i);
            m.setSource("USER_EXPLICIT");
            list.add(new MemoryContextBuilder.ScoredMemory(m, 1.0 - i * 0.01, 98.0));
        }
        String result = builder.build(list);
        int count = result.split("- \\[").length - 1;
        assertTrue(count <= 5, "max 5, actual: " + count);
    }

    @Test
    @DisplayName("MODEL_INFERENCE source translated to model-inferred")
    void translatesModelInference() {
        UserMemoryEntity m = new UserMemoryEntity();
        m.setMemoryType("PREFERENCE");
        m.setContent("prefers concise");
        m.setSource("MODEL_INFERENCE");
        var scored = new MemoryContextBuilder.ScoredMemory(m, 0.5, 35.0);
        String result = builder.build(List.of(scored));
        assertTrue(result.contains("model-inferred"));
    }

    @Test
    @DisplayName("ScoredMemory record fields")
    void scoredMemoryFields() {
        UserMemoryEntity m = new UserMemoryEntity();
        var scored = new MemoryContextBuilder.ScoredMemory(m, 0.85, 90.0);
        assertSame(m, scored.memory());
        assertEquals(0.85, scored.score(), 0.001);
        assertEquals(90.0, scored.confidencePercent(), 0.01);
    }
}