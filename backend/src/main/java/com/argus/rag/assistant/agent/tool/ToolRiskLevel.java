package com.argus.rag.assistant.agent.tool;

/**
 * 工具（Tool）风险等级分级。
 *
 * <p>参考：Function Calling 指南 2.7 节（工具风险等级）。
 * 策略：只读类工具可以直接自动执行；涉及写操作的工具必须等用户确认。</p>
 */
public enum ToolRiskLevel {
    /** 只读、无副作用。可自动执行，无需用户确认。 */
    LOW,
    /** 会读用户自己的数据。需要做权限校验并留审计记录。 */
    MEDIUM,
    /** 会创建或修改资源。需要用户确认后才能执行。 */
    HIGH,
    /** 破坏性操作或涉及认证（身份）变更。需要人工审批。 */
    CRITICAL
}