package com.argus.rag.ingestion.service.pipeline.transformer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link StructureAwareChunkTransformer} 结构感知分块契约（纯 JVM/零 DB/零 LLM）。
 * 空/空白跳过、短文档成单块、分块元数据（sectionPath/charStart/charEnd/chunkStrategy）与带索引 ID、
 * 标题切分章节、超限段落按 token 预算硬截断、多文档扇出、重叠 pullback。
 */
@DisplayName("结构感知分块（StructureAwareChunkTransformer）")
class StructureAwareChunkTransformerTest {

    private static final String STRATEGY = "structure-aware-token-budget-v1";

    private StructureAwareChunkTransformer withDefaults() {
        return new StructureAwareChunkTransformer(new ChunkingProperties());
    }

    @Test
    void nullOrEmptyDocumentsReturnEmpty() {
        StructureAwareChunkTransformer t = withDefaults();
        assertEquals(List.of(), t.apply(null));
        assertEquals(List.of(), t.apply(List.of()));
    }

    @Test
    void nullOrBlankDocumentInListSkipped() {
        StructureAwareChunkTransformer t = withDefaults();
        assertEquals(List.of(), t.apply(Collections.singletonList((Document) null)));
        assertEquals(List.of(), t.apply(List.of(new Document("   \n  "))));
    }

    @Test
    void singleShortDocProducesOneChunk() {
        StructureAwareChunkTransformer t = withDefaults();
        List<Document> chunks = t.apply(List.of(new Document("hello world")));
        assertEquals(1, chunks.size());
        assertEquals("hello world", chunks.get(0).getText());
    }

    @Test
    void chunkCarriesIdIndexAndMetadata() {
        StructureAwareChunkTransformer t = withDefaults();
        Document src = Document.builder().id("d1").text("hi there").build();
        List<Document> chunks = t.apply(List.of(src));
        assertEquals(1, chunks.size());
        Document chunk = chunks.get(0);
        assertEquals("d1:0", chunk.getId());
        assertEquals(STRATEGY, chunk.getMetadata().get("chunkStrategy"));
        assertEquals("", chunk.getMetadata().get("sectionPath"));
        assertEquals(Integer.valueOf(0), chunk.getMetadata().get("charStart"));
        assertEquals(Integer.valueOf(8), chunk.getMetadata().get("charEnd"));
    }

    @Test
    void headingSplitsIntoLeadingAndSectionedChunks() {
        StructureAwareChunkTransformer t = withDefaults();
        List<Document> chunks = t.apply(List.of(new Document("intro\n\n# 标题\n正文")));
        assertEquals(2, chunks.size());
        assertEquals("intro", chunks.get(0).getText());
        assertEquals("", chunks.get(0).getMetadata().get("sectionPath"));
        assertEquals("标题", chunks.get(1).getMetadata().get("sectionPath"));
        assertEquals("# 标题\n正文", chunks.get(1).getText());
    }

    @Test
    void oversizedParagraphSplitByTokenBudget() {
        ChunkingProperties props = new ChunkingProperties();
        props.setTargetTokens(3);
        props.setMaxTokens(5);
        props.setCharsPerToken(1);
        props.setOverlapTokens(0);
        StructureAwareChunkTransformer t = new StructureAwareChunkTransformer(props);
        // 10 字符无句子标点无空行 → 按 5 字符硬切为两片
        List<Document> chunks = t.apply(List.of(new Document("abcdefghij")));
        assertEquals(2, chunks.size());
        assertEquals("abcde", chunks.get(0).getText());
        assertEquals(Integer.valueOf(0), chunks.get(0).getMetadata().get("charStart"));
        assertEquals("fghij", chunks.get(1).getText());
        assertEquals(Integer.valueOf(5), chunks.get(1).getMetadata().get("charStart"));
    }

    @Test
    void overlapPullsSecondChunkStartBack() {
        ChunkingProperties props = new ChunkingProperties();
        props.setTargetTokens(4);
        props.setMaxTokens(8);
        props.setCharsPerToken(1);
        props.setOverlapTokens(2);
        StructureAwareChunkTransformer t = new StructureAwareChunkTransformer(props);
        List<Document> chunks = t.apply(List.of(new Document("aaaaaaaaaaaaaaaa")));
        assertEquals(2, chunks.size());
        // 首块 [0,8)；第二块 intrinsic [8,16)，overlap=2 字符 pullback → 起始 6
        assertEquals(Integer.valueOf(0), chunks.get(0).getMetadata().get("charStart"));
        assertEquals(8, chunks.get(0).getText().length());
        assertEquals(Integer.valueOf(6), chunks.get(1).getMetadata().get("charStart"));
        assertEquals(10, chunks.get(1).getText().length());
    }

    @Test
    void applyFansOutMultipleDocuments() {
        StructureAwareChunkTransformer t = withDefaults();
        List<Document> chunks = t.apply(List.of(new Document("one"), new Document("two")));
        assertEquals(2, chunks.size());
        assertEquals("one", chunks.get(0).getText());
        assertEquals("two", chunks.get(1).getText());
    }
}