package com.argus.rag.planning;

import com.argus.rag.common.enums.SystemRole;
import com.argus.rag.common.security.AuthenticatedUser;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 方案校验器 —— 第三步的"代码把关"段（模型提方案，代码做校验）。
 *
 * <p><b>五项校验（方案第五节）全部由代码执行，模型意见不采信</b>：</p>
 * <ol>
 *   <li>工具白名单：步骤工具不在注册清单 → 剥除该步（模型编造工具直接淘汰）；</li>
 *   <li>参数合法性：模型提议的参数一律标待定（trusted=false 强制覆盖），
 *       可信参数由代码从系统身份另行注入；条目/长度封顶；</li>
 *   <li>预算：maxRounds 收敛到 1..5（模型建议仅供参照，代码封顶防循环失控）；</li>
 *   <li>退出条件：模型没写 → 注入默认退出条件（易漏点 3：没写退出条件循环会失控）；</li>
 *   <li>确认门：needConfirm 由代码按入口风险派生（规则 4：删除/导出/外发等
 *       高风险在入口已标 HIGH），模型建议不采信。</li>
 * </ol>
 */
@Component
public class PlanValidator {

    private static final int MAX_STEPS = 8;
    private static final int MAX_PARAMS_PER_STEP = 6;
    private static final int MAX_RISKS = 5;
    private static final int MIN_ROUNDS = 1;
    private static final int MAX_ROUNDS = 5;
    private static final int MAX_ITEM_CHARS = 150;
    private static final String DEFAULT_EXIT_CONDITION =
            "预算轮次用尽即停止：输出已获得的部分结论并说明证据不足";

    /**
     * 校验并修正模型提议的方案。
     *
     * @param candidate          模型提议的候选方案
     * @param questionRiskHigh   入口风险标记是否 HIGH（确认门唯一依据）
     * @param user               可信身份（用于注入可信参数）
     * @param groupIds           可信知识库范围
     * @return 校验后的正式方案（永不返回 null；步骤可能被剥除后为空——调用方走 fallback）
     */
    public ExecutionPlan validate(ExecutionPlan candidate, boolean questionRiskHigh,
                                  AuthenticatedUser user, List<Long> groupIds) {
        if (candidate == null) {
            return null;
        }
        List<ExecutionPlan.PlanStep> validSteps = new ArrayList<>();
        int order = 1;
        for (ExecutionPlan.PlanStep step : candidate.steps()) {
            if (step == null || validSteps.size() >= MAX_STEPS) {
                continue;
            }
            PlanTool tool = PlanTool.fromName(step.toolName());
            if (tool == null) {
                continue;
            }
            validSteps.add(new ExecutionPlan.PlanStep(
                    order++,
                    truncate(normalize(step.description()), MAX_ITEM_CHARS),
                    tool.toolName() != null ? tool.toolName() : tool.name(),
                    sanitizeParams(step.params())
            ));
        }

        List<ExecutionPlan.PlanParam> trusted = new ArrayList<>();
        if (user != null) {
            trusted.add(new ExecutionPlan.PlanParam("userId", "U" + user.userId(), true));
            trusted.add(new ExecutionPlan.PlanParam("role",
                    user.systemRole() == null ? SystemRole.USER.name() : user.systemRole().name(), true));
        }
        if (groupIds != null && !groupIds.isEmpty()) {
            trusted.add(new ExecutionPlan.PlanParam("knowledgeGroups", joinIds(groupIds), true));
        }

        int rounds = Math.max(MIN_ROUNDS, Math.min(MAX_ROUNDS, candidate.maxRounds()));
        String exit = normalize(candidate.exitCondition());
        if (exit == null || exit.isBlank()) {
            exit = DEFAULT_EXIT_CONDITION;
        }
        boolean needConfirm = questionRiskHigh;
        return new ExecutionPlan(
                truncate(normalize(candidate.goal()), 200),
                validSteps,
                trusted,
                rounds,
                sanitizeList(candidate.risks(), MAX_RISKS),
                exit,
                needConfirm
        );
    }

    /** 模型提议参数强制降级为待定（trusted=false），并做封顶卫生。 */
    private List<ExecutionPlan.PlanParam> sanitizeParams(List<ExecutionPlan.PlanParam> params) {
        if (params == null || params.isEmpty()) {
            return List.of();
        }
        List<ExecutionPlan.PlanParam> out = new ArrayList<>();
        Set<String> seenNames = new LinkedHashSet<>();
        for (ExecutionPlan.PlanParam param : params) {
            if (param == null || param.name() == null || out.size() >= MAX_PARAMS_PER_STEP) {
                continue;
            }
            String name = normalize(param.name());
            if (name.isBlank() || !seenNames.add(name)) {
                continue;
            }
            out.add(new ExecutionPlan.PlanParam(name, truncate(normalize(param.value()), 120), false));
        }
        return out;
    }

    private List<String> sanitizeList(List<String> items, int max) {
        if (items == null) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String item : items) {
            if (out.size() >= max) {
                break;
            }
            String normalized = truncate(normalize(item), MAX_ITEM_CHARS);
            if (normalized != null && !normalized.isBlank()) {
                out.add(normalized);
            }
        }
        return out;
    }

    private String joinIds(List<Long> ids) {
        StringBuilder sb = new StringBuilder();
        for (Long id : ids) {
            if (id != null && id > 0) {
                if (sb.length() > 0) {
                    sb.append(',');
                }
                sb.append(id);
            }
        }
        return sb.toString();
    }

    private String normalize(String text) {
        return text == null ? "" : text.replaceAll("\\s+", " ").trim();
    }

    private String truncate(String text, int maxChars) {
        if (text == null || text.length() <= maxChars) {
            return text == null ? "" : text;
        }
        return text.substring(0, maxChars) + "…";
    }
}
