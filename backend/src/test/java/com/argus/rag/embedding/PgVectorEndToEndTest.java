package com.argus.rag.embedding;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 本地 embedding → 本地 pgvector 真实端到端（连本机 Postgres）。
 * <p>流程：连 localhost:5432/argus_rag → 建临时 vector(512) 表 → 用本地 bge-small-zh
 * 把文本转 512 维向量写入 → 用 {{@code <=>}} 余弦距离检索 → 断言相似语义召回靠前 → 收尾 DROP。</p>
 * <p>纯 JDBC 直连，不依赖 Spring 上下文；若本地无库/无 pgvector 扩展会如实失败并提示。</p>
 */
class PgVectorEndToEndTest {

    private static final String URL = "jdbc:postgresql://localhost:5432/argus_rag";
    private static final String USER = "postgres";
    private static final String PASSWORD = "yct412825";

    private static final List<String> DOCS = List.of(
            "员工请假需要提前一天在 OA 系统提交申请",
            "如何申请年假，需要填写请假流程并多部门审批",
            "公司食堂中午十二点开饭，二楼有小炒窗口",
            "差旅报销要附发票和行程单，财务三日内处理");

    @Test
    void localEmbedStoredAndRetrievedInPgvector() throws Exception {
        Class.forName("org.postgresql.Driver");
        LocalEmbeddingConfig.LocalBgeEmbeddingModel model = new LocalEmbeddingConfig.LocalBgeEmbeddingModel();
        String table = "_pg_smoke_" + UUID.randomUUID().toString().replace("-", "");

        try (Connection conn = DriverManager.getConnection(URL, USER, PASSWORD);
             Statement st = conn.createStatement()) {
            st.execute("CREATE EXTENSION IF NOT EXISTS vector");
            st.execute("CREATE TABLE " + table + " (id serial primary key, content text, emb vector(512))");

            // 1) 本地嵌入 → 写入 pgvector
            try {
                for (String doc : DOCS) {
                    float[] vec = model.embed(doc);
                    assertThat(vec).hasSize(512);
                    st.executeUpdate(String.format(
                            "INSERT INTO %s (content, emb) VALUES ('%s', '%s')",
                            table, doc, toPostgres(vec)));
                }

                // 2) 用"年假申请"查询向量真实检索
                String query = "如何申请年假";
                float[] q = model.embed(query);
                ResultSet rs = st.executeQuery(String.format(
                        "SELECT content FROM %s ORDER BY emb <=> '%s' ASC LIMIT 3", table, toPostgres(q)));
                List<String> ranked = new ArrayList<>();
                while (rs.next()) ranked.add(rs.getString("content"));

                // 3) 断言：最相近的字面出现在第一（本地 embedding + pgvector 协同成立）
                assertThat(ranked).isNotEmpty();
                // nearest 是年假那句
                assertThat(ranked.get(0)).contains("年假");
                // 完全无关的 差旅/菜 排在靠后（允许排在年假与请假之后）
                System.out.println("[pgvector] query=年假 rank0=" + ranked.get(0));
            } finally {
                st.execute("DROP TABLE IF EXISTS " + table);
            }
        }
    }

    private static String toPostgres(float[] vec) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < vec.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(vec[i]);
        }
        return sb.append(']').toString();
    }
}