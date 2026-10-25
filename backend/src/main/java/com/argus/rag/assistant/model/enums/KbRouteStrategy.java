package com.argus.rag.assistant.model.enums;

/**
 * 知识库路由策略。
 * <p>KB_SEARCH 模式下决定检索范围如何确定：手动选择或自动路由。</p>
 */
public enum KbRouteStrategy {
    /**
     * 手动选择：仅检索用户明确指定的一个或多个知识库。
     */
    SELECTED,
    /**
     * 自动路由：根据用户问题从可见知识库（或给定的候选知识库）中自动挑选最相关的多个知识库。
     */
    AUTO
}
