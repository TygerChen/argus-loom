package com.argus.rag.planning;

import com.argus.rag.assistant.model.enums.AssistantToolMode;
import com.argus.rag.common.enums.SystemRole;
import com.argus.rag.common.security.AuthenticatedUser;
import com.argus.rag.common.security.UserContext;
import com.argus.rag.common.task.TaskRiskClassifier;
import com.argus.rag.qa.support.QaAnswerParser;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 任务规划服务 —— 第三步"制定方案"的编排层（模型提方案，代码做校验）。
 *
 * <p><b>流程</b>：任务理解结果 + 地面事实 + 工具清单 → 大模型提议 JSON 方案 →
 * {@link PlanValidator} 代码校验（工具白名单/可信参数/预算/退出条件/确认门）→
 * 正式方案。不真正调用任何工具——方案是计划书。</p>
 *
 * <p><b>失败语义与第二步不同</b>：方案是第四步的必需输入，因此失败不走"空"，
 * 而走<b>代码兜底的默认方案</b>（单步直答/检索 + 标准退出条件）——保证第四步
 * 永远拿到一份可执行的计划书，同时日志告警让降级可见。</p>
 */
@Service
public class TaskPlanningService {

    private static final Logger log = LoggerFactory.getLogger(TaskPlanningService.class);
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() { };
    private static final String RETRY_SUFFIX =
            "\n\n【重试要求】你上一次的输出不是合法 JSON。请严格只输出一个 JSON 对象，"
                    + "不要 Markdown 代码块，不要任何解释。";

    private final PlanClient planClient;
    private final PlanValidator validator;
    private final ObjectMapper objectMapper;
    private final String planningPromptText;
    private final boolean enabled;

    public TaskPlanningService(
            PlanClient planClient,
            PlanValidator validator,
            ObjectMapper objectMapper,
            @Qualifier("planningPromptText") String planningPromptText,
            @Value("${argus.rag.assistant.planning.enabled:true}") boolean enabled
    ) {
        this.planClient = planClient;
        this.validator = validator;
        this.objectMapper = objectMapper;
        this.planningPromptText = planningPromptText;
        this.enabled = enabled;
    }

    /**
     * 制定执行方案（不执行）。
     *
     * @param question     用户原始问题
     * @param understanding 第二步理解结果（可为 empty——理解失败时按原问题兜底）
     * @param groupIds     可信知识库范围（CHAT 模式为空）
     * @param toolMode     助手模式
     * @return 正式方案（代码校验通过；LLM 失败时代码兜底默认方案）
     */
    public ExecutionPlan plan(String question, com.argus.rag.understanding.TaskUnderstanding understanding,
                              List<Long> groupIds, AssistantToolMode toolMode) {
        boolean knowledgeMode = toolMode == AssistantToolMode.KB_SEARCH && groupIds != null && !groupIds.isEmpty();
        boolean riskHigh = TaskRiskClassifier.classify(question).riskLevel()
                == com.argus.rag.common.task.TaskRiskLevel.HIGH;
        try {
            if (enabled && question != null && !question.isBlank()) {
                ExecutionPlan candidate = propose(question, understanding, groupIds, toolMode);
                ExecutionPlan validated = validator.validate(candidate, riskHigh, UserContext.get(), groupIds);
                if (validated != null && !validated.isEmpty()) {
                    return validated;
                }
                log.warn("方案提议经校验后无有效步骤，使用代码兜底方案");
            }
        } catch (RuntimeException planningFailure) {
            log.warn("方案制定失败（使用代码兜底方案）: {}", planningFailure.getMessage());
        }
        return ExecutionPlan.fallback(goalOf(understanding, question), knowledgeMode, question, riskHigh);
    }

    /** 大模型提议 + 解析（失败纠偏重试一次；两次失败返回 null → 调用方走 fallback）。 */
    private ExecutionPlan propose(String question,
                                  com.argus.rag.understanding.TaskUnderstanding understanding,
                                  List<Long> groupIds, AssistantToolMode toolMode) {
        String groundFacts = buildGroundFacts(groupIds, toolMode);
        String understandingBlock = understanding == null || understanding.isEmpty()
                ? "（理解结果缺失，请直接从问题推断目标）"
                : understanding.asInstructionBlock();
        String prompt = planningPromptText
                .replace("{{groundFacts}}", groundFacts)
                .replace("{{understandingBlock}}", understandingBlock)
                .replace("{{question}}", question.trim());

        Map<String, Object> parsed = parseOrNull(planClient.complete(prompt));
        if (parsed == null) {
            parsed = parseOrNull(planClient.complete(prompt + RETRY_SUFFIX));
        }
        return parsed == null ? null : toCandidate(parsed, question);
    }

    /** 地面事实（可信身份 + 范围 + 日期），与第二步同源。 */
    private String buildGroundFacts(List<Long> groupIds, AssistantToolMode toolMode) {
        StringBuilder sb = new StringBuilder();
        AuthenticatedUser user = UserContext.get();
        if (user != null) {
            sb.append("用户 U").append(user.userId())
                    .append("，角色 ").append(user.systemRole() == null ? SystemRole.USER.name() : user.systemRole().name())
                    .append("；");
        }
        sb.append("通道 AI 助手（").append(toolMode == null ? "UNKNOWN" : toolMode.name()).append("）；");
        sb.append("知识库范围：").append(groupIds == null || groupIds.isEmpty() ? "AUTO 路由" : groupIds).append("；");
        sb.append("当前日期 ").append(LocalDate.now());
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private ExecutionPlan toCandidate(Map<String, Object> parsed, String question) {
        List<ExecutionPlan.PlanStep> steps = new ArrayList<>();
        Object rawSteps = parsed.get("steps");
        int order = 1;
        if (rawSteps instanceof List<?> list) {
            for (Object item : list) {
                if (!(item instanceof Map<?, ?> stepMap) || steps.size() >= 12) {
                    continue;
                }
                steps.add(new ExecutionPlan.PlanStep(
                        order++,
                        textOf(stepMap.get("description")),
                        textOf(stepMap.get("tool")),
                        paramsOf(stepMap.get("params"))
                ));
            }
        }
        List<String> risks = new ArrayList<>();
        if (parsed.get("risks") instanceof List<?> riskList) {
            for (Object item : riskList) {
                risks.add(String.valueOf(item));
            }
        }
        int rounds = parsed.get("maxRounds") instanceof Number number ? number.intValue() : 3;
        return new ExecutionPlan(
                textOr(parsed.get("goal"), question),
                steps,
                List.of(),
                rounds,
                risks,
                textOf(parsed.get("exitCondition")),
                Boolean.TRUE.equals(parsed.get("needConfirmSuggestion"))
        );
    }

    private List<ExecutionPlan.PlanParam> paramsOf(Object raw) {
        List<ExecutionPlan.PlanParam> params = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> paramMap) {
                    params.add(new ExecutionPlan.PlanParam(
                            textOf(paramMap.get("name")),
                            textOf(paramMap.get("value")),
                            false
                    ));
                }
            }
        }
        return params;
    }

    private Map<String, Object> parseOrNull(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            Map<String, Object> parsed = objectMapper.readValue(QaAnswerParser.sanitizeJson(raw), MAP_TYPE);
            return parsed == null || parsed.isEmpty() ? null : parsed;
        } catch (Exception parseFailure) {
            return null;
        }
    }

    private String textOf(Object raw) {
        return raw == null ? "" : String.valueOf(raw);
    }

    /** 兜底方案的目标：优先取第二步 intent，缺失退回用户原话。 */
    private String goalOf(com.argus.rag.understanding.TaskUnderstanding understanding, String question) {
        if (understanding != null && !understanding.isEmpty() && understanding.intent() != null
                && !understanding.intent().isBlank()) {
            return understanding.intent();
        }
        return question;
    }

    private String textOr(Object raw, String fallback) {
        String text = textOf(raw);
        return text.isBlank() ? fallback : text;
    }
}
