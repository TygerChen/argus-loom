package com.argus.rag.assistant.memory.longterm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 长期记忆检索服务。
 *
 * <p>多维度综合打分排序：相关度×0.45 + 重要度×0.20 + 置信度×0.20 + 新鲜度×0.15。
 * 打分之前，先在数据访问层强制按 用户(userId)+租户(tenantId) 过滤，避免串号/越权。</p>
 */
@Service
public class MemoryRetriever {

    private static final Logger log = LoggerFactory.getLogger(MemoryRetriever.class);

    private static final double DECAY_DAYS = 30.0;
    private static final double W_RELEVANCE = 0.45;
    private static final double W_IMPORTANCE = 0.20;
    private static final double W_CONFIDENCE = 0.20;
    private static final double W_RECENCY = 0.15;

    private static final Pattern WORD_PATTERN = Pattern.compile("[A-Za-z0-9]{2,}");

    private final UserMemoryMapper mapper;

    public MemoryRetriever(UserMemoryMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * 检索该用户在该租户下的长期记忆，并按「和当前问题的相关度」排序。
     * <p>这是"AI 助手为什么记得你"的落地处：把所有仍生效（ACTIVE）的记忆捞出来，
     * 逐条给 相关度×重要×可信×新鲜 四维打分，越相关的排越前，最后只取最靠前的 {@code limit} 条。</p>
     *
     * @param userId   用户 ID（谁的记忆）
     * @param tenantId 租户 ID（数据隔离：不同租户的记忆不可混查）
     * @param query    当前问题/上下文，用来算"哪条记忆此刻最有用"
     * @param limit    最多返回多少条
     * @return 打分后从高到低排列的记忆；用户或租户为空、或没有任何生效记忆时返回空列表
     */
    public List<MemoryContextBuilder.ScoredMemory> search(
            Long userId, Long tenantId, String query, int limit
    ) {
        if (userId == null || tenantId == null) return List.of();

        List<UserMemoryEntity> active = mapper.selectActiveByUser(userId, tenantId);
        if (active.isEmpty()) return List.of();

        LocalDateTime now = LocalDateTime.now();
        List<MemoryContextBuilder.ScoredMemory> scored = new ArrayList<>();

        for (UserMemoryEntity m : active) {
            double relevance = lexicalScore(query, m.getContent());
            if (relevance == 0.0) continue;

            double importance = m.getImportance() != null ? m.getImportance().doubleValue() : 0.5;
            double confidence = m.getConfidence() != null ? m.getConfidence().doubleValue() : 0.5;
            double recency = recencyScore(m.getUpdatedAt(), now);

            double score = relevance * W_RELEVANCE
                    + importance * W_IMPORTANCE
                    + confidence * W_CONFIDENCE
                    + recency * W_RECENCY;

            scored.add(new MemoryContextBuilder.ScoredMemory(m, score, confidence * 100.0));
        }

        scored.sort(Comparator.comparingDouble(MemoryContextBuilder.ScoredMemory::score).reversed());
        return scored.size() > limit ? scored.subList(0, limit) : scored;
    }

    /**
     * 计算「问题」和「某条记忆内容」的文字相似度（0~1 的实数）。
     * <p>做法：把两边都拆成词（token）集合，看问题里有多少词也出现在记忆里；
     * 重叠越多分越高。这里只是「字面上像不像」，不含语义——真正的语义靠大模型那层。</p>
     *
     * @return 相似度；问题或内容为空时返回 0.0（完全不像）
     */
    double lexicalScore(String query, String content) {
        if (query == null || query.isBlank() || content == null || content.isBlank()) return 0.0;
        Set<String> qTokens = tokenize(query);
        Set<String> cTokens = tokenize(content);
        if (qTokens.isEmpty()) return 0.0;
        Set<String> overlap = new HashSet<>(qTokens);
        overlap.retainAll(cTokens);
        return overlap.size() / Math.sqrt(qTokens.size() * Math.max(1, cTokens.size()));
    }

    /**
     * 给「最近更新时间」打分：越新分值越高（0~1）。
     * <p>用指数衰减：距今越久分数呈指数下降，天然贴合"久远记忆逐渐淡忘"。
     * {@code updatedAt} 为空则给 0 分（无从判断新旧）。</p>
     *
     * @return 0~1 的新鲜度分数
     */
    double recencyScore(LocalDateTime updatedAt, LocalDateTime now) {
        if (updatedAt == null) return 0.0;
        long days = ChronoUnit.DAYS.between(updatedAt, now);
        return Math.exp(-Math.max(0.0, (double) days) / DECAY_DAYS);
    }

    /**
     * 把一段文本拆成可比的词集：英文数字按单词、中文按相邻两字一组（bigram）。
     * <p>这样 `lexicalScore` 才能比「词的重叠」。中文没有天然空格分词，所以用相邻两字作为词片段，
     * 提高中文内容的匹配召回率。</p>
     */
    private static Set<String> tokenize(String text) {
        Set<String> tokens = new LinkedHashSet<>();
        String lower = text.toLowerCase();
        Matcher m = WORD_PATTERN.matcher(lower);
        while (m.find()) {
            tokens.add(m.group());
        }
        // 同时提取中文成对的二元组（CJK bigram），提升中文检索的召回率
        Pattern cjkPattern = Pattern.compile("[\\p{IsHan}]");
        StringBuilder cjkBuffer = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (cjkPattern.matcher(String.valueOf(ch)).matches()) {
                cjkBuffer.append(ch);
            } else {
                if (cjkBuffer.length() >= 2) {
                    for (int j = 0; j <= cjkBuffer.length() - 2; j++) {
                        tokens.add(cjkBuffer.substring(j, j + 2));
                    }
                }
                cjkBuffer.setLength(0);
            }
        }
        if (cjkBuffer.length() >= 2) {
            for (int j = 0; j <= cjkBuffer.length() - 2; j++) {
                tokens.add(cjkBuffer.substring(j, j + 2));
            }
        }
        return tokens;
    }
}