package com.argus.rag.understanding;

import com.argus.rag.assistant.model.enums.AssistantToolMode;
import com.argus.rag.common.enums.SystemRole;
import com.argus.rag.common.security.AuthenticatedUser;
import com.argus.rag.common.security.UserContext;
import com.argus.rag.common.task.TaskRiskClassifier;
import com.argus.rag.common.task.TaskTicketInspector;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 任务理解服务 —— 第二步"理解任务"的编排层（三段式分工）。
 *
 * <p><b>方案核心：不能纯代码，也不能让大模型自由发挥</b>：</p>
 * <ol>
 *   <li><b>第一段·代码前置约束</b>：组装"地面事实"——可信身份（UserContext，非用户自称）、
 *       通道与知识库范围、入口风险标记（复用第一步 TaskRiskClassifier/TaskTicketInspector）、
 *       输出 schema——全部由代码生成；</li>
 *   <li><b>第二段·大模型语义理解</b>：任务类型/意图/实体/约束/歧义（{@link UnderstandingClient} 端口）；</li>
 *   <li><b>第三段·代码后置校验</b>：{@link UnderstandingPostValidator} 剥离权限断言、
 *       仲裁权限歧义、schema 卫生。</li>
 * </ol>
 *
 * <p><b>失败语义：整层 fail-soft</b>——理解是"锦上添花"，任何失败（关闭/超时/输出不合法）
 * 都返回 {@link TaskUnderstanding#empty()}，Agent 按原流程运行，绝不因理解失败而失败。
 * JSON 解析失败重试一次（补"只输出 JSON"纠偏），仍失败则放弃——不无限重试拖垮延迟。</p>
 */
@Service
public class TaskUnderstandingService {

    private static final Logger log = LoggerFactory.getLogger(TaskUnderstandingService.class);
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() { };
    /** JSON 解析失败重试时的纠偏后缀（只给一次机会，保住延迟预算）。 */
    private static final String RETRY_SUFFIX =
            "\n\n【重试要求】你上一次的输出不是合法 JSON。请严格只输出一个 JSON 对象，"
                    + "不要 Markdown 代码块，不要任何解释。";

    private final UnderstandingClient understandingClient;
    private final UnderstandingPostValidator validator;
    private final ObjectMapper objectMapper;
    /** 提示词原文（{{taskContext}}/{{question}} 双花括号占位，纯文本替换渲染）。 */
    private final String understandingPromptText;
    private final boolean enabled;

    public TaskUnderstandingService(
            UnderstandingClient understandingClient,
            UnderstandingPostValidator validator,
            ObjectMapper objectMapper,
            @Qualifier("understandingPromptText") String understandingPromptText,
            @Value("${argus.rag.assistant.understanding.enabled:true}") boolean enabled
    ) {
        this.understandingClient = understandingClient;
        this.validator = validator;
        this.objectMapper = objectMapper;
        this.understandingPromptText = understandingPromptText;
        this.enabled = enabled;
    }

    /**
     * 理解本轮任务（三段式编排，全链路 fail-soft）。
     *
     * @param question 用户原始问题
     * @param groupIds 本次知识库范围（CHAT 模式为空）
     * @param toolMode 助手模式
     * @return 结构化理解结果；失败/关闭返回 {@link TaskUnderstanding#empty()}
     */
    public TaskUnderstanding understand(String question, List<Long> groupIds, AssistantToolMode toolMode) {
        if (!enabled || question == null || question.isBlank()) {
            return TaskUnderstanding.empty();
        }
        try {
            // 第一段：代码前置约束（地面事实全部由代码生成，模型只做语义）
            String taskContext = buildGroundFacts(question, groupIds, toolMode);
            String prompt = understandingPromptText
                    .replace("{{taskContext}}", taskContext)
                    .replace("{{question}}", question.trim());

            // 第二段：大模型语义理解（解析失败纠偏重试一次）
            Map<String, Object> parsed = parseOrEmpty(understandingClient.complete(prompt));
            if (parsed == null) {
                parsed = parseOrEmpty(understandingClient.complete(prompt + RETRY_SUFFIX));
            }
            if (parsed == null) {
                log.warn("任务理解输出两次均不合法 JSON，按无理解继续");
                return TaskUnderstanding.empty();
            }

            // 第三段：代码后置校验（权限歧义仲裁 + 越界剥离 + schema 卫生）
            return validator.validate(toCandidate(parsed), UserContext.get());
        } catch (RuntimeException understandingFailure) {
            log.warn("任务理解失败（不影响主流程）: {}", understandingFailure.getMessage());
            return TaskUnderstanding.empty();
        }
    }

    /**
     * 第一段：地面事实（可信身份/能力边界/入口风险标记/日期）。
     * <p>身份只取自 {@link UserContext}（JWT 验签产物）——方案铁律：
     * 用户文本里"我是XX"只是待核实声明（入口已标记），这里绝不采信。</p>
     */
    private String buildGroundFacts(String question, List<Long> groupIds, AssistantToolMode toolMode) {
        StringBuilder sb = new StringBuilder();
        AuthenticatedUser user = UserContext.get();
        sb.append("可信身份：");
        if (user != null) {
            sb.append("用户 U").append(user.userId())
                    .append("（编号 ").append(user.userCode()).append("）")
                    .append("，角色 ").append(user.systemRole() == null ? SystemRole.USER.name() : user.systemRole().name());
        } else {
            sb.append("未认证（按最低权限处理）");
        }
        sb.append('\n');
        sb.append("通道：AI 助手（").append(toolMode == null ? "UNKNOWN" : toolMode.name()).append("）\n");
        sb.append("本次知识库范围：").append(groupIds == null || groupIds.isEmpty() ? "未选择（AUTO 路由）" : groupIds).append('\n');
        TaskRiskClassifier.RiskMark riskMark = TaskRiskClassifier.classify(question);
        sb.append("入口风险标记：动作 ").append(riskMark.actionType().name())
                .append(" / 级别 ").append(riskMark.riskLevel().name())
                .append(" / 需要确认门 ").append(riskMark.needConfirmation()).append('\n');
        sb.append("用户文本疑似身份声明：").append(TaskTicketInspector.flagIdentityClaim(question) ? "是（不可采信）" : "否").append('\n');
        sb.append("当前日期：").append(LocalDate.now()).append('\n');
        return sb.toString();
    }

    /** 解析模型输出为 JSON Map；不合法返回 null（不抛异常）。 */
    @SuppressWarnings("unchecked")
    private Map<String, Object> parseOrEmpty(String rawOutput) {
        if (rawOutput == null || rawOutput.isBlank()) {
            return null;
        }
        try {
            String sanitized = com.argus.rag.qa.support.QaAnswerParser.sanitizeJson(rawOutput);
            Map<String, Object> parsed = objectMapper.readValue(sanitized, MAP_TYPE);
            return parsed == null || parsed.isEmpty() ? null : parsed;
        } catch (Exception parseFailure) {
            return null;
        }
    }

    /** Map → 候选理解（字段缺失/类型不符一律安全降级为空列表）。 */
    private TaskUnderstanding toCandidate(Map<String, Object> parsed) {
        return new TaskUnderstanding(
                parseTypes(parsed.get("taskTypes")),
                textOf(parsed.get("intent")),
                stringsOf(parsed.get("entities")),
                stringsOf(parsed.get("constraints")),
                stringsOf(parsed.get("ambiguities")),
                stringsOf(parsed.get("clarifications")),
                stringsOf(parsed.get("assumptions"))
        );
    }

    private Set<TaskUnderstandingType> parseTypes(Object rawTypes) {
        Set<TaskUnderstandingType> types = new LinkedHashSet<>();
        if (rawTypes instanceof List<?> list) {
            for (Object item : list) {
                try {
                    types.add(TaskUnderstandingType.valueOf(String.valueOf(item).trim()));
                } catch (IllegalArgumentException ignored) {
                    // 模型编造的类型由后置校验的空集语义处理，这里仅跳过
                }
            }
        }
        return types;
    }

    private List<String> stringsOf(Object raw) {
        List<String> items = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object item : list) {
                items.add(item == null ? "" : String.valueOf(item));
            }
        } else if (raw instanceof String text && !text.isBlank()) {
            items.add(text);
        }
        return items;
    }

    private String textOf(Object raw) {
        return raw == null ? "" : String.valueOf(raw);
    }
}
