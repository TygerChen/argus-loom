package com.argus.rag.common.task;

/**
 * 业务通道枚举 —— 请求进入系统时的"挂号科室"。
 * <p>
 * 四个通道对应产品的四类入口：知识库问答、AI 助手、Excel 协作、财务工作台。
 * 通道由请求 URI 前缀静态映射（{@link #fromUri}），不需要业务代码手动声明——
 * 入口审计的原则是"接线一处、通道自动归档"。
 * </p>
 * <p>
 * 当前接入节奏：{@code KB_QA / ASSISTANT}（运行器未来的主通道）已挂审计；
 * {@code EXCEL / FINANCE} 枚举与映射先行定义，待各自优化步骤再接入，
 * 避免一步铺开四个通道、回归面过大。
 * </p>
 */
public enum TaskChannel {

    /** 知识库问答（/api/qa/**） */
    KB_QA,

    /** AI 助手（/api/assistant/**） */
    ASSISTANT,

    /** Excel 协作（/api/excel/**） */
    EXCEL,

    /** 财务工作台（/api/finance/**） */
    FINANCE,

    /**
     * 知识库文档通道（/api/documents/**）——独立的<b>写操作入口</b>：
     * 上传文档改变知识库内容，与只读提问分属不同风险面（文档方案第三篇）。
     * POST=上传（写），DELETE=删除（高危），GET=列表/预览/下载（读）。
     */
    KB_DOCUMENT;

    /** 通道中文名（可信任务上下文展示用，如"AI 助手"）。 */
    public String displayName() {
        return switch (this) {
            case KB_QA -> "知识库问答";
            case ASSISTANT -> "AI 助手";
            case EXCEL -> "Excel 协作";
            case FINANCE -> "财务工作台";
            case KB_DOCUMENT -> "知识库文档";
        };
    }

    /**
     * 按请求 URI 静态映射业务通道；非业务通道（认证/健康检查等）返回 {@code null}。
     * <p>映射是纯前缀判断，无任何 IO——过滤器每请求调用，必须零成本。</p>
     *
     * @param requestUri 请求 URI（如 {@code /api/qa/ask}），可为 null
     * @return 对应通道；不属于业务通道时返回 null
     */
    public static TaskChannel fromUri(String requestUri) {
        if (requestUri == null) {
            return null;
        }
        if (requestUri.startsWith("/api/qa/")) {
            return KB_QA;
        }
        if (requestUri.startsWith("/api/assistant/")) {
            return ASSISTANT;
        }
        if (requestUri.startsWith("/api/excel/")) {
            return EXCEL;
        }
        if (requestUri.startsWith("/api/finance/")) {
            return FINANCE;
        }
        if (requestUri.startsWith("/api/documents")) {
            return KB_DOCUMENT;
        }
        return null;
    }
}
