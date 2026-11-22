package com.argus.rag.assistant.loop;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.hook.HookPosition;
import com.alibaba.cloud.ai.graph.agent.hook.HookPositions;
import com.alibaba.cloud.ai.graph.agent.hook.messages.AgentCommand;
import com.alibaba.cloud.ai.graph.agent.hook.messages.MessagesModelHook;
import com.alibaba.cloud.ai.graph.agent.hook.messages.UpdatePolicy;
import com.argus.rag.planning.PlanTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 模型决策审计 Hook —— 第五步"模型决策"的运行器把关（AFTER_MODEL）。
 *
 * <p><b>方案原则的落点</b>：大模型本质是计算概率，没有执行工具的能力——
 * 它返回的 tool_calls 只是"提案"。本 Hook 在每次模型响应后：</p>
 * <ul>
 *   <li><b>结构化审计</b>：把每个提案记为 {@code DecisionRecord}
 *       （轮次/动作/工具/参数摘要/是否在方案清单内），INFO 日志留痕；</li>
 *   <li><b>越界校验</b>：工具名不在第三步方案清单内（方案缺省时按内置注册表）→
 *       WARN 告警 + 状态计数，并在下一轮上下文的状态块里向模型通报越界记录——
 *       上下文回压，比硬拦截更稳（硬拦截需框架级工具执行层介入，属演进项）；</li>
 *   <li><b>动作识别</b>：有 tool_calls → CALL_TOOL（逐个审计）；纯文本 → ANSWER
 *       （最终回答候选，后续由既有验证/引用链路把关）。</li>
 * </ul>
 *
 * <p><b>透传语义</b>：本 Hook 只读不改——返回原消息列表与 REPLACE，
 * 决策的执行与消息流转完全交回框架。</p>
 */
@Component
@HookPositions({HookPosition.AFTER_MODEL})
public class DecisionAuditHook extends MessagesModelHook {

    private static final Logger log = LoggerFactory.getLogger(DecisionAuditHook.class);
    /** 参数摘要最大字符数（审计不存全量参数，防敏感值进日志）。 */
    private static final int ARGS_DIGEST_MAX = 100;

    /** 与循环状态注入共用开关（loop.enabled），缺省 true 保证无 Spring 单测可用。 */
    @org.springframework.beans.factory.annotation.Value("${argus.rag.assistant.loop.enabled:true}")
    private boolean loopEnabled = true;

    @Override
    public String getName() {
        return "assistant_decision_audit_hook";
    }

    @Override
    public AgentCommand afterModel(List<Message> previousMessages, RunnableConfig config) {
        if (!loopEnabled || previousMessages == null || previousMessages.isEmpty() || config == null) {
            return new AgentCommand(previousMessages, UpdatePolicy.REPLACE);
        }
        com.argus.rag.assistant.loop.TaskLoopState state = metadataAsLoopState(config);
        if (state == null) {
            return new AgentCommand(previousMessages, UpdatePolicy.REPLACE);
        }
        AssistantMessage decision = lastAssistantMessage(previousMessages);
        if (decision == null) {
            return new AgentCommand(previousMessages, UpdatePolicy.REPLACE);
        }
        int round = state.currentRound();
        if (decision.hasToolCalls() && decision.getToolCalls() != null) {
            for (AssistantMessage.ToolCall toolCall : decision.getToolCalls()) {
                if (toolCall == null || toolCall.name() == null) {
                    continue;
                }
                boolean inWhitelist = isWhitelisted(state, toolCall.name());
                state.recordDecision("CALL_TOOL", toolCall.name(),
                        digest(toolCall.arguments()), inWhitelist);
                if (inWhitelist) {
                    log.info("agent.decision: round={} action=CALL_TOOL tool={} argsDigest={} inWhitelist=true",
                            round, toolCall.name(), digest(toolCall.arguments()));
                } else {
                    log.warn("agent.decision: round={} action=CALL_TOOL tool={} 越出方案工具清单（已审计）",
                            round, toolCall.name());
                }
            }
        } else if (decision.getText() != null && !decision.getText().isBlank()) {
            state.recordDecision("ANSWER", null, digest(decision.getText()), true);
            log.info("agent.decision: round={} action=ANSWER answerDigest={}",
                    round, digest(decision.getText()));
        }
        return new AgentCommand(previousMessages, UpdatePolicy.REPLACE);
    }

    /** 白名单判定：方案清单优先；方案未限定（空集）时按内置注册表。 */
    private boolean isWhitelisted(com.argus.rag.assistant.loop.TaskLoopState state, String toolName) {
        if (state.planToolNames().isEmpty()) {
            return PlanTool.fromName(toolName) != null;
        }
        return state.planToolNames().contains(toolName);
    }

    private AssistantMessage lastAssistantMessage(List<Message> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i) instanceof AssistantMessage assistantMessage) {
                return assistantMessage;
            }
        }
        return null;
    }

    private com.argus.rag.assistant.loop.TaskLoopState metadataAsLoopState(RunnableConfig config) {
        try {
            Object value = config.metadata("taskLoopState").orElse(null);
            return value instanceof com.argus.rag.assistant.loop.TaskLoopState state ? state : null;
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private static String digest(String text) {
        if (text == null) {
            return "";
        }
        String normalized = text.replaceAll("\\s+", " ").trim();
        return normalized.length() <= ARGS_DIGEST_MAX ? normalized : normalized.substring(0, ARGS_DIGEST_MAX) + "…";
    }
}
