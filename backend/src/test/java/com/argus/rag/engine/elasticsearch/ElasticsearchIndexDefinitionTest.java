package com.argus.rag.engine.elasticsearch;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * {@link ElasticsearchChunkIndexService#buildCreateIndexRequestBody()} 索引定义行为锁
 * （纯 Map 组装、package-private 同包直调、`httpClient=null` 占位零网络）。
 * <p>锁契约：settings 内 IK 分词器双 tokenizer（索引 ik_max_word / 搜索 ik_smart）；
 * mappings 字段类型与 analyzer 结构（chunkText/fileName 使用 argus_rag_ik_index/search 双 analyzer、
 * status=keyword、deleted=boolean、fileName 带 keyword 子字段 ignore_above=256）。</p>
 */
@DisplayName("ES 索引定义请求体（ElasticsearchChunkIndexService.buildCreateIndexRequestBody）")
class ElasticsearchIndexDefinitionTest {

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object o) {
        return (Map<String, Object>) o;
    }

    private static Map<String, Object> body() {
        return new ElasticsearchChunkIndexService(
                new ObjectMapper(), null, "localhost", 9200, "http", "idx").buildCreateIndexRequestBody();
    }

    private static Map<String, Object> properties() {
        Map<String, Object> mappings = map(body().get("mappings"));
        return map(mappings.get("properties"));
    }

    // ---------- 顶层 ----------

    @Test
    void topLevelHasSettingsAndMappings() {
        Map<String, Object> body = body();
        assertEquals(2, body.size());
        assertNotNull(map(body.get("settings")));
        assertNotNull(map(body.get("mappings")));
    }

    // ---------- IK 分词器 ----------

    @Test
    void ikAnalyzersConfiguredForIndexAndSearch() {
        Map<String, Object> settings = map(body().get("settings"));
        Map<String, Object> analysis = map(settings.get("analysis"));
        Map<String, Object> analyzers = map(analysis.get("analyzer"));
        assertEquals("ik_max_word", map(analyzers.get("argus_rag_ik_index")).get("tokenizer"));
        assertEquals("ik_smart", map(analyzers.get("argus_rag_ik_search")).get("tokenizer"));
    }

    // ---------- mappings 字段类型 ----------

    @Test
    void mappingFieldTypesAreDeclared() {
        Map<String, Object> properties = properties();
        assertEquals("long", map(properties.get("groupId")).get("type"));
        assertEquals("long", map(properties.get("documentId")).get("type"));
        assertEquals("long", map(properties.get("chunkId")).get("type"));
        assertEquals("integer", map(properties.get("chunkIndex")).get("type"));
        assertEquals("keyword", map(properties.get("status")).get("type"));
        assertEquals("boolean", map(properties.get("deleted")).get("type"));
    }

    @Test
    void chunkTextUsesBothAnalyzers() {
        Map<String, Object> chunkText = map(properties().get("chunkText"));
        assertEquals("text", chunkText.get("type"));
        assertEquals("argus_rag_ik_index", chunkText.get("analyzer"));
        assertEquals("argus_rag_ik_search", chunkText.get("search_analyzer"));
    }

    @Test
    void fileNameHasKeywordSubFieldWithIgnoreAbove() {
        Map<String, Object> fileName = map(properties().get("fileName"));
        assertEquals("argus_rag_ik_index", fileName.get("analyzer"));
        Map<String, Object> keywordField = map(map(fileName.get("fields")).get("keyword"));
        assertEquals("keyword", keywordField.get("type"));
        assertEquals(Integer.valueOf(256), keywordField.get("ignore_above"));
    }
}