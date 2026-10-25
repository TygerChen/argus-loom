package com.argus.rag.assistant.agent;

import org.springframework.util.StringUtils;

/**
 * assistant 域无关的纯解析助手（同包可见）。
 * <p>
 * 把 {@code AssistantKnowledgeBaseTool} 与 {@code StructuredDataQueryTool} 重复的
 * {@code parsePositiveLong} 收敛为单一实现：仅当入参可解析出正整数时返回 Long，否则 null。
 * 两处分身行为等价（空串在 KB 侧被 {@code hasText} 拦截、在结构化侧由 {@code parseLong}
 * 抛 {@code NumberFormatException} 吞掉，结果一致），故安全合并。
 * </p>
 */
final class AssistantIds {

    private AssistantIds() {
    }

    /** 仅在可解析且为正时返回 Long；Number/正串→正数，负数/零/空/非数字→null。 */
    static Long parsePositiveLong(Object value) {
        if (value instanceof Number number && number.longValue() > 0) {
            return number.longValue();
        }
        if (value instanceof String text) {
            if (!StringUtils.hasText(text)) {
                return null;
            }
            try {
                long parsed = Long.parseLong(text.trim());
                return parsed > 0 ? parsed : null;
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }
}