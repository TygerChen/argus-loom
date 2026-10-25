package com.argus.rag.qa.service;

import com.argus.rag.common.exception.BusinessException;
import com.argus.rag.qa.model.QueryPlanResult;
import com.argus.rag.qa.model.QueryPlanStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 查询规划服务 —— 提问进检索引擎前的"审题官"（04 篇 Q5 的实现）。
 *
 * <p><b>为什么要先审题</b>：用户的问题经常不适合直接拿去检索——
 * 口语化（"那个报销流程咋弄"）、有指代（"那食宿呢"，检索词是空的）、
 * 或者一句话里塞了三个问题（"A、B、C 三个方案的审批时限各是多少"）。
 * 直接把原文丢给向量检索，召回质量会崩。本类在检索前调用大模型"审题"，
 * 产出两样东西：<b>策略</b>（QueryPlanStrategy）和<b>检索语句列表</b>。</p>
 *
 * <p><b>四种策略分别什么时候用</b>（面试必背，对应 04 篇 Q5/Q12）：</p>
 * <ul>
 *   <li><b>DIRECT</b>：问题本身清晰，原句直接检索——最优路径，零额外开销；</li>
 *   <li><b>REWRITE</b>：口语/指代问题，改写成检索友好的书面语。
 *       最终语句 = 原句 + 改写句（见 buildRewriteQueries），原句保留是因为
 *       改写可能丢掉原句里的专有名词，两路都查更稳；</li>
 *   <li><b>DECOMPOSE</b>：复合问题拆成多个子问题，每个子问题独立走一遍
 *       双路检索再融合。注意代价：子问题数直接放大检索次数
 *       （04 篇 Q26 的"检索放大系数"），所以有 MAX_QUERY_COUNT=3 封顶；</li>
 *   <li><b>GLOBAL</b>："总结一下整个知识库"这类全景问题，精确命中没有意义，
 *       交给 HybridChunkRetrievalService 的全局遍历模式扫概览。</li>
 * </ul>
 *
 * <p><b>为什么规划失败要回退 DIRECT 而不是报错</b>：规划是"锦上添花"环节，
 * 不是"没有它就不能检索"的必需环节。大模型超时、返回格式解析失败，都只该
 * 意味着"退化为拿原句直接检索"——用户最多损失一点召回质量，不该看到报错。
 * fallback(originalQuestion) 就是这个安全网。<b>降级路径是检索类系统的标配</b>：
 * 每个可选增强环节都要想清楚"它挂了以后主链路怎么走"（同类设计：
 * rerank 出错回退 RRF 原序，04 篇 Q19）。</p>
 *
 * <p><b>为什么 MAX_QUERY_COUNT = 3</b>：每条检索语句要跑向量 + ES 两路，
 * 3 条就是 6 次检索——检索收益边际递减，延迟和成本线性上涨，3 是常用平衡点。
 * 模型不听话返回了 5 条？limitQueries 直接截断。<b>永远不要信任大模型的输出
 * 会自觉遵守约定</b>，代码侧必须有硬性护栏——这是所有 LLM 应用的通用原则。</p>
 *
 * <p><b>容错清单（validatePlan）</b>：模型返回 null/空 → 回退；策略是 GLOBAL
 * 但语句列表被模型编造 → 强制只保留原句；语句空白/重复 → 规范化去重。
 * 每一步都在防"模型输出污染检索链路"。</p>
 */
@Service
public class QueryPlanningService {

    private static final Logger log = LoggerFactory.getLogger(QueryPlanningService.class);

    /** 检索语句最大数量限制 */
    private static final int MAX_QUERY_COUNT = 3;

    private final ChatClient queryPlanningChatClient;
    private final PromptTemplate queryPlanningUserPromptTemplate;

    /**
     * 构造函数。
     *
     * @param queryPlanningChatClient         查询规划专用的 ChatClient
     * @param queryPlanningUserPromptTemplate 查询规划用户提示词模板
     */
    public QueryPlanningService(
            @Qualifier("queryPlanningChatClient") ChatClient queryPlanningChatClient,
            @Qualifier("queryPlanningUserPromptTemplate") PromptTemplate queryPlanningUserPromptTemplate
    ) {
        this.queryPlanningChatClient = queryPlanningChatClient;
        this.queryPlanningUserPromptTemplate = queryPlanningUserPromptTemplate;
    }

    /**
     * 对用户问题执行查询规划。
     * <p>
     * 调用大模型分析问题并返回检索策略和检索语句，
     * 失败时回退为 DIRECT 策略。
     * </p>
     *
     * @param question 用户原始问题
     * @return 查询规划结果
     */
    public QueryPlanResult plan(String question) {
        String normalizedQuestion = requireQuestion(question);
        try {

            Prompt planPrompt = queryPlanningUserPromptTemplate.create(Map.of("question", normalizedQuestion));

            QueryPlanResult rawResult = queryPlanningChatClient.prompt(planPrompt)
                    .call()
                    .entity(QueryPlanResult.class);
            QueryPlanResult validatedPlan = validatePlan(rawResult, normalizedQuestion);
            log.info("查询规划完成: strategy={}, queries={}", validatedPlan.strategy(), validatedPlan.queries());
            return validatedPlan;
        } catch (RuntimeException exception) {
            log.warn("查询规划失败，回退为直接检索: question={}", normalizedQuestion, exception);
            return QueryPlanResult.fallback(normalizedQuestion);
        }
    }

    /**
     * 校验规划结果，确保策略和检索语句有效。
     * 根据不同策略构建最终的检索语句列表。
     */
    private QueryPlanResult validatePlan(QueryPlanResult rawResult, String originalQuestion) {
        if (rawResult == null || rawResult.strategy() == null) {
            return QueryPlanResult.fallback(originalQuestion);
        }
        if (rawResult.strategy() == QueryPlanStrategy.GLOBAL) {
            return new QueryPlanResult(QueryPlanStrategy.GLOBAL, List.of(originalQuestion));
        }
        Set<String> normalizedQueries = normalizeQueries(rawResult.queries());
        if (normalizedQueries.isEmpty()) {
            return QueryPlanResult.fallback(originalQuestion);
        }
        List<String> finalQueries = switch (rawResult.strategy()) {
            case DIRECT -> List.of(originalQuestion);
            case REWRITE -> buildRewriteQueries(originalQuestion, normalizedQueries);
            case DECOMPOSE -> limitQueries(normalizedQueries);
            case GLOBAL -> List.of(originalQuestion);
        };
        if (finalQueries.isEmpty()) {
            return QueryPlanResult.fallback(originalQuestion);
        }
        return new QueryPlanResult(rawResult.strategy(), finalQueries);
    }

    /** 构建 REWRITE 策略的检索语句：原始问题 + 重写后的语句 */
    private static List<String> buildRewriteQueries(String originalQuestion, Set<String> normalizedQueries) {
        LinkedHashSet<String> rewriteQueries = new LinkedHashSet<>();
        rewriteQueries.add(originalQuestion);
        rewriteQueries.addAll(normalizedQueries);
        return limitQueries(rewriteQueries);
    }

    /** 规范化检索语句：去除空白、去重 */
    private static Set<String> normalizeQueries(List<String> queries) {
        LinkedHashSet<String> normalizedQueries = new LinkedHashSet<>();
        if (queries == null) {
            return normalizedQueries;
        }
        for (String query : queries) {
            if (!StringUtils.hasText(query)) {
                continue;
            }
            String normalized = query.replaceAll("\\s+", " ").trim();
            if (StringUtils.hasText(normalized)) {
                normalizedQueries.add(normalized);
            }
        }
        return normalizedQueries;
    }

    /** 限制检索语句数量不超过 {@link #MAX_QUERY_COUNT} */
    private static List<String> limitQueries(Set<String> queries) {
        return queries.stream()
                .limit(MAX_QUERY_COUNT)
                .toList();
    }

    /** 渲染用户提示词模板（当前未使用，保留备用） */
    /** 校验问题非空并规范化空白字符 */
    private static String requireQuestion(String question) {
        if (!StringUtils.hasText(question)) {
            throw new BusinessException("问题不能为空");
        }
        return question.replaceAll("\\s+", " ").trim();
    }
}
