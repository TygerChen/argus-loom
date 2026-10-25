package com.argus.rag.assistant.agent.tool;

/**
 * 模型上下文窗口（Context Window）的预算分配。
 *
 * <p>为模型可用的上下文长度做 token 预算划分：把窗口切成多个组成部分，
 * 防止其中某一项（如检索到的知识）吃掉整个窗口，从而保证其他部分有位置可放。
 * 各组成部分的「比例」在下方常量中定义，占比之和通常 ≈ 1（不含输出预留）。
 * </p>
 */
public final class ContextBudget {

    private ContextBudget() {}

    /** 默认总窗口大小（已为模型输出预留空间）。 */
    public static final int DEFAULT_WINDOW = 8192;

    /** 预留给系统指令（system prompt）的预算比例。 */
    public static final double SYSTEM_INSTRUCTION_RATIO = 0.20;

    /** 预留给工具定义（tool definitions）的预算比例。 */
    public static final double TOOL_DEFINITIONS_RATIO = 0.05;

    /** 预留给长期记忆注入的预算比例。 */
    public static final double LONG_TERM_MEMORY_RATIO = 0.10;

    /** 预留给检索知识的预算比例。 */
    public static final double KNOWLEDGE_RATIO = 0.25;

    /** 预留给对话历史消息的预算比例。 */
    public static final double MESSAGE_HISTORY_RATIO = 0.25;

    /** 预留给当前用户输入内容的预算比例。 */
    public static final double CURRENT_INPUT_RATIO = 0.10;

    /** 预留用于模型输出的预算比例。 */
    public static final double OUTPUT_RESERVE_RATIO = 0.05;

    public static int budget(int windowSize, double ratio) {
        return (int) (windowSize * ratio);
    }
}