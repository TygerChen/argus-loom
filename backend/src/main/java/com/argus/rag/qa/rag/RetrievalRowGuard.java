package com.argus.rag.qa.rag;

import com.argus.rag.common.exception.BusinessException;
import org.springframework.util.StringUtils;

import java.util.Map;

/**
 * 检索结果行数据的纯取值/规整工具（无状态、无外部依赖）。
 *
 * <p>从 {@code HybridChunkRetrievalService} 下沉：这些函数只对传入的
 * {@code row} 或单值做「安全取值 / 转换 / 校验」，不触碰检索编排、DAO 或评分状态。
 * 抽出后可独立做契约测试，且不改变任何对外行为。</p>
 */
public final class RetrievalRowGuard {

    private RetrievalRowGuard() {
    }

    /**
     * 将 RRF 融合原始分映射到 {@code [0,1)} 的归一化得分。
     */
    public static double normalizeScore(double rawRrfScore) {
        return 1.0 - Math.exp(-rawRrfScore);
    }

    /**
     * 从 Row Map 中获取值，支持驼峰和小写两种键名。
     */
    public static Object getValue(Map<String, Object> row, String field) {
        Object value = row.get(field);
        if (value != null) {
            return value;
        }
        return row.get(field.toLowerCase());
    }

    /**
     * 校验 groupId 为正数。
     */
    public static Long requirePositiveGroupId(Long groupId) {
        if (groupId == null || groupId <= 0) {
            throw new BusinessException("groupId 非法");
        }
        return groupId;
    }

    /**
     * 校验问题非空并 trim。
     */
    public static String requireQuestion(String question) {
        if (!StringUtils.hasText(question)) {
            throw new BusinessException("问题不能为空");
        }
        return question.trim();
    }

    /**
     * 安全转换为 Long，失败时抛出业务异常。
     */
    public static Long requireLong(Object value, String field) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        throw new BusinessException("检索结果缺少字段: " + field);
    }

    /**
     * 安全转换为 Integer，失败时抛出业务异常。
     */
    public static Integer requireInteger(Object value, String field) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        throw new BusinessException("检索结果缺少字段: " + field);
    }

    /**
     * 安全转换为非空字符串，失败时抛出业务异常。
     */
    public static String requireText(Object value, String field) {
        if (value instanceof String text && StringUtils.hasText(text)) {
            return text.trim();
        }
        throw new BusinessException("检索结果缺少字段: " + field);
    }
}