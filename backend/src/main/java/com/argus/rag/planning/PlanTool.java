package com.argus.rag.planning;

/**
 * 方案工具清单（注册表）—— 代码持有的"合法工具白名单"。
 *
 * <p><b>方案铁律（第三步）：模型提方案，代码做校验。</b>大模型提议的步骤里
 * 只允许出现本清单内的工具——清单外的工具名一律被 {@code PlanValidator} 剥除。</p>
 *
 * <p><b>读写分离（第六步）</b>：每个工具声明是否为写操作。当前清单全部只读；
 * 未来写操作工具（写 Excel 单元格、推送日报等）必须 {@code writeOperation=true}——
 * 工具执行门禁（{@code ToolExecutionGuard}）据此强制：写操作需要确认门通过 +
 * 幂等键，缺失直接拒绝执行。把读写分离做在注册表里，新工具接线即被门禁覆盖。</p>
 */
public enum PlanTool {

    /** 不调工具：基于上下文/记忆直接回答（CHAT 闲聊、纯推理） */
    DIRECT_ANSWER(null, "直接回答（不调用工具）", false),

    /** RAG 知识库检索（对应 assistant 的 knowledgeBaseSearch 工具） */
    KNOWLEDGE_BASE_SEARCH("knowledgeBaseSearch", "知识库检索（带出处证据）", false),

    /** 结构化数据精确查询 */
    STRUCTURED_DATA_QUERY("query_structured_data", "Excel 结构化查询", false),

    /** 获取表格 Schema（先看有哪些列再查） */
    TABLE_SCHEMA("get_table_schema", "获取表格结构", false),

    /** 表格模糊搜索 */
    TABLE_FUZZY_SEARCH("search_table_data", "表格模糊搜索", false),

    /** 互联网搜索 */
    WEB_SEARCH("webSearch", "联网搜索", false),

    /** 当前时间 */
    CURRENT_TIME("currentTime", "获取当前时间", false),

    /**
     * 更新任务进度表状态（第一个真实写工具，第十一步）。
     * <p><b>写语义（确认门）</b>：对话循环内模型调用它<b>只登记待确认提案</b>——
     * 工具把改动参数快照进 {@code PendingProposalStore} 并返回回执，绝不执行写；
     * 真正的写执行只发生在确认 API 的运行器路径（用户点确认 → 幂等键=proposalId →
     * 按提案存储的参数确定性应用）。模型从头到尾摸不到写执行路径。</p>
     */
    UPDATE_TASK_PROGRESS("updateTaskProgress", "更新任务进度表（写操作，需用户确认）", true);

    private final String toolName;
    private final String displayName;
    private final boolean writeOperation;

    PlanTool(String toolName, String displayName, boolean writeOperation) {
        this.toolName = toolName;
        this.displayName = displayName;
        this.writeOperation = writeOperation;
    }

    public String toolName() {
        return toolName;
    }

    public String displayName() {
        return displayName;
    }

    /** 是否写操作（写操作在执行门禁要求确认门 + 幂等键）。 */
    public boolean writeOperation() {
        return writeOperation;
    }

    /**
     * 按模型输出的工具名安全解析；不在清单内返回 null（校验器据此剥除该步骤）。
     */
    public static PlanTool fromName(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        String normalized = name.trim();
        for (PlanTool tool : values()) {
            if (normalized.equalsIgnoreCase(tool.name()) || normalized.equalsIgnoreCase(tool.toolName)) {
                return tool;
            }
        }
        return null;
    }
}
