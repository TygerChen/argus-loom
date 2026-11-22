package com.argus.rag.understanding;

/**
 * 任务类型 —— 第二步（理解任务）的类型判断维度。
 * <p>方案边界：只判断"类型"，<b>不决定用什么工具、什么步骤</b>（那是第三步制定方案的事）。
 * 一条请求可以是复合类型（如"查数据 + 生成日报 + 推送"= DATA_QUERY + BUSINESS_OPS）。</p>
 */
public enum TaskUnderstandingType {

    /** 直接问答：无需检索即可回答（如"金蝶是什么"） */
    DIRECT_QA,

    /** 知识库检索：需要依据制度/手册回答（如"怎么反结账"） */
    KB_RETRIEVAL,

    /** 数据查询：需要查结构化数据（如"华东区本月薪酬异常有哪些"） */
    DATA_QUERY,

    /** 业务操作：涉及生成/变更/推送等动作（如"生成日报并推送钉钉"） */
    BUSINESS_OPS;

    /** 中文名（任务理解上下文展示用）。 */
    public String displayName() {
        return switch (this) {
            case DIRECT_QA -> "直接问答";
            case KB_RETRIEVAL -> "知识库检索";
            case DATA_QUERY -> "数据查询";
            case BUSINESS_OPS -> "业务操作";
        };
    }
}
