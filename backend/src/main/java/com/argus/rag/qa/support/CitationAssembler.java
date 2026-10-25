package com.argus.rag.qa.support;

import com.argus.rag.common.util.MetadataReaders;
import com.argus.rag.qa.model.vo.AskQuestionResponse;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 引用来源组装器 —— "这个答案出自哪份文档"的最后一步（04 篇引用机制的实现）。
 *
 * <p><b>为什么引用如此重要</b>：RAG 的答案可信度不靠模型嘴上说"我确定"，
 * 而靠<b>可点开的出处</b>。引用让用户能自己验证答案（开卷考试的"注明页码"），
 * 也能让排查有起点——用户反馈"答案不对"时，第一件事就是看引用对不对：
 * 引用错 = 检索的锅，引用对答案错 = 生成的锅（04 篇 Q16 的排查分流）。</p>
 *
 * <p><b>按文件名去重的取舍（面试官可能追问）</b>：一次检索命中 5 个块，
 * 可能 3 块都来自同一份文档——给用户展示 3 条重复引用是噪音，
 * 所以 putIfAbsent 只保留每个文件的第一条（= RRF 排序里最相关的块）。
 * 代价是丢掉了同文档的其他块信息；如果产品要做"引用跳转到具体段落"，
 * 就得去掉去重或改成"一文件多引用嵌套"——<b>当前粒度是面向'来源可信'的，
 * 不是面向'段落定位'的，两种产品诉求的组装策略不同</b>。</p>
 *
 * <p><b>缺文件名的块直接丢弃（返回 null）</b>：宁可少一条引用也不展示
 * "来源未知"的引用——一条没有出处的引用会拉低整份答案的可信度。
 * 元数据读取全部走安全读取（类型不符返回 null 而不是抛异常）：
 * 元数据来自向量库/ES 两条链路，类型实现可能有差异（PgVectorRetrievalAdapter
 * 的类注释讲过同样的兼容问题），组装层必须防御。</p>
 */
@Component
public class CitationAssembler {

    /**
     * 将检索文档列表组装为引用来源列表。
     * <p>按文件名去重，保留每个文件的第一次命中，保持插入顺序。</p>
     *
     * @param documents 检索返回的文档列表
     * @return 去重后的引用来源列表
     */
    public List<AskQuestionResponse.Citation> assembleDocuments(List<Document> documents) {
        if (documents == null || documents.isEmpty()) {
            return List.of();
        }
        Map<String, AskQuestionResponse.Citation> citationsByFileName = new LinkedHashMap<>();
        for (Document document : documents) {
            AskQuestionResponse.Citation citation = toCitation(document);
            if (citation != null) {
                citationsByFileName.putIfAbsent(citation.fileName(), citation);
            }
        }
        return List.copyOf(citationsByFileName.values());
    }

    /**
     * 将单个检索文档转换为引用来源对象。
     * <p>从文档元数据中提取各字段，若缺少文件名则返回 {@code null}。</p>
     */
    private AskQuestionResponse.Citation toCitation(Document document) {
        Map<String, Object> metadata = document.getMetadata();
        String fileName = readFileName(metadata);
        if (!StringUtils.hasText(fileName)) {
            return null;
        }
        return new AskQuestionResponse.Citation(
                readLong(metadata, "documentId"),
                readLong(metadata, "chunkId"),
                readInteger(metadata, "chunkIndex"),
                fileName,
                readScore(metadata),
                null,
                readLong(metadata, "groupId")
        );
    }

    /** 从元数据中安全读取 Long 类型值，非 Number 类型时返回 {@code null} */
    private Long readLong(Map<String, Object> metadata, String key) {
        Object value = metadata.get(key);
        if (value instanceof Number number) {
            return number.longValue();
        }
        return null;
    }

    /** 从元数据中安全读取 Integer 类型值，非 Number 类型时返回 {@code null} */
    private Integer readInteger(Map<String, Object> metadata, String key) {
        return MetadataReaders.intValue(metadata, key);
    }

    /** 从元数据中读取检索评分，默认返回 0 */
    private double readScore(Map<String, Object> metadata) {
        return MetadataReaders.score(metadata);
    }

    /** 从元数据中安全读取 String 类型值，自动 trim，非 String 类型时返回 {@code null} */
    private String readText(Map<String, Object> metadata, String key) {
        return MetadataReaders.text(metadata, key);
    }

    /** 从元数据中读取文件名，优先读取 "fileName"，回退读取 "documentName" */
    private String readFileName(Map<String, Object> metadata) {
        String fileName = readText(metadata, "fileName");
        if (StringUtils.hasText(fileName)) {
            return fileName;
        }
        return readText(metadata, "documentName");
    }
}
