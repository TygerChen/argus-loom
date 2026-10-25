package com.argus.rag.assistant.memory.longterm;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MemoryRetrieverTest {

    private final MemoryRetriever retriever = new MemoryRetriever(null);

    @Test
    @DisplayName("empty query returns zero")
    void emptyZero() {
        assertEquals(0.0, retriever.lexicalScore("", "content"), 0.001);
    }

    @Test
    @DisplayName("overlapping tokens score positive")
    void overlapPositive() {
        double s = retriever.lexicalScore("report format", "report format preferences");
        assertTrue(s > 0.1, "score: " + s);
    }

    @Test
    @DisplayName("no overlap returns zero")
    void noOverlapZero() {
        assertEquals(0.0, retriever.lexicalScore("weather", "report format"), 0.001);
    }

    @Test
    @DisplayName("recent update scores near 1.0")
    void recentNearOne() {
        var now = java.time.LocalDateTime.now();
        assertEquals(1.0, retriever.recencyScore(now, now), 0.01);
    }

    @Test
    @DisplayName("30 days old decays below 0.5")
    void oldDecays() {
        var now = java.time.LocalDateTime.now();
        var old = now.minusDays(30);
        double s = retriever.recencyScore(old, now);
        assertTrue(s < 0.5 && s > 0.3, "score: " + s);
    }

    @Test
    @DisplayName("null updatedAt returns zero")
    void nullZero() {
        assertEquals(0.0, retriever.recencyScore(null, java.time.LocalDateTime.now()), 0.001);
    }
}