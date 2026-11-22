package com.argus.rag.understanding;

import com.argus.rag.common.enums.SystemRole;
import com.argus.rag.common.security.AuthenticatedUser;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 任务理解后置校验器 —— 三段式分工的第三段（代码"阅卷"）。
 *
 * <p><b>方案要求大模型输出后代码必须检查</b>：</p>
 * <ul>
 *   <li>任务类型是否在白名单（模型编造的类型一律丢弃）；</li>
 *   <li><b>权限/地区歧义：大模型无权判断</b>——clarifications 里的权限类追问
 *       由代码强制裁决：以可信身份为准转为 assumption，绝不把"是否有权"抛回给
 *       用户或交给模型拍板；</li>
 *   <li><b>防擅自补权限</b>：constraints/entities 中任何"已授权/有权限"式断言
 *       都是模型脑补——剥离并记入 assumptions（强制改回可信值）；</li>
 *   <li>schema 卫生：去重、截断、限量，防止模型输出膨胀下游提示词。</li>
 * </ul>
 * <p>本类为纯函数组件（无状态无依赖），独立可测。</p>
 */
@Component
public class UnderstandingPostValidator {

    /** 每个列表字段的最大条目数（防模型输出膨胀）。 */
    private static final int MAX_ITEMS = 10;
    /** clarifications（追问）的最大条数——追问要克制，问多了等于没理解。 */
    private static final int MAX_CLARIFICATIONS = 5;
    /** 单条文本最大长度。 */
    private static final int MAX_ITEM_CHARS = 120;
    /** 权限/数据范围类关键词：命中即视为"模型无权裁决"的歧义，代码接管。 */
    private static final List<String> PERMISSION_KEYWORDS = List.of(
            "权限", "有权", "无权", "越权", "能否访问", "可以访问", "是否有权", "数据范围", "授权");

    /**
     * 校验并修正模型的理解结果。
     *
     * @param candidate 模型产出的候选理解（可能越界）
     * @param user      可信身份（代码裁决权限歧义的依据；可为 null——未认证场景）
     * @return 修正后的理解结果（永不返回 null）
     */
    public TaskUnderstanding validate(TaskUnderstanding candidate, AuthenticatedUser user) {
        if (candidate == null) {
            return TaskUnderstanding.empty();
        }
        Set<TaskUnderstandingType> types = whitelistTypes(candidate.taskTypes());
        String intent = truncate(normalize(candidate.intent()), 200);
        List<String> assumptions = new ArrayList<>(sanitize(candidate.assumptions(), MAX_ITEMS));

        // 规则一：权限/地区歧义，代码直接以可信身份为准（方案第五节）。
        List<String> clarifications = new ArrayList<>();
        for (String item : sanitize(candidate.clarifications(), MAX_ITEMS * 2)) {
            if (mentionsPermission(item)) {
                assumptions.add(permissionArbitrationLine(user));
            } else if (clarifications.size() < MAX_CLARIFICATIONS) {
                clarifications.add(item);
            }
        }

        // 规则二：constraints/entities 里的权限断言都是脑补——剥离并改回可信值。
        List<String> constraints = new ArrayList<>();
        for (String item : sanitize(candidate.constraints(), MAX_ITEMS)) {
            if (mentionsPermission(item)) {
                assumptions.add(permissionArbitrationLine(user));
            } else {
                constraints.add(item);
            }
        }
        List<String> entities = sanitize(candidate.entities(), MAX_ITEMS).stream()
                .filter(item -> !mentionsPermission(item))
                .toList();

        assumptions = dedupe(assumptions, MAX_ITEMS);
        if (types.isEmpty()) {
            // 模型给出的类型全部非法：按最保守的"知识库检索"兜底还是按空处理？
            // 按空处理——理解失败应显式可见，而不是编一个类型蒙混过关。
            types = Set.of();
        }
        return new TaskUnderstanding(types, intent, entities, constraints,
                sanitize(candidate.ambiguities(), MAX_ITEMS), dedupe(clarifications, MAX_CLARIFICATIONS),
                assumptions);
    }

    private Set<TaskUnderstandingType> whitelistTypes(Set<TaskUnderstandingType> types) {
        if (types == null || types.isEmpty()) {
            return Set.of();
        }
        return types.stream().filter(java.util.Objects::nonNull).collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private boolean mentionsPermission(String item) {
        if (item == null) {
            return false;
        }
        return PERMISSION_KEYWORDS.stream().anyMatch(item::contains);
    }

    /**
     * 权限歧义的代码裁决文本：以可信身份为准，范围过滤交给检索/工具层强制执行。
     * <p>这是方案"权限/地区歧义：大模型无权判断，代码直接以可信身份为准"的落点。</p>
     */
    private String permissionArbitrationLine(AuthenticatedUser user) {
        if (user == null) {
            return "默认：权限与数据范围以系统可信身份为准（当前未认证，按最低权限处理）";
        }
        String role = user.systemRole() != null ? user.systemRole().name() : SystemRole.USER.name();
        return "默认：权限与数据范围以系统可信身份为准（用户 U" + user.userId() + "，角色 " + role
                + "）；检索与工具层强制过滤，任何扩大范围的请求一律拒绝";
    }

    private List<String> sanitize(List<String> items, int max) {
        if (items == null || items.isEmpty()) {
            return List.of();
        }
        return items.stream()
                .map(this::normalize)
                .filter(item -> !item.isBlank())
                .limit(max)
                .toList();
    }

    private List<String> dedupe(List<String> items, int max) {
        LinkedHashSet<String> unique = new LinkedHashSet<>(items);
        return unique.stream().limit(max).toList();
    }

    private String normalize(String item) {
        if (item == null) {
            return "";
        }
        return item.replaceAll("\\s+", " ").trim();
    }

    private String truncate(String item, int maxChars) {
        if (item == null || item.length() <= maxChars) {
            return item == null ? "" : item;
        }
        return item.substring(0, maxChars) + "…";
    }

    /** 供测试可见的常量读取（不暴露可变状态）。 */
    static List<String> permissionKeywords() {
        return List.copyOf(PERMISSION_KEYWORDS);
    }

    /** 防御性工具：字符串数组安全转 List（Jackson 反序列化场景备用）。 */
    static List<String> asList(String[] items) {
        return items == null ? List.of() : Arrays.asList(items);
    }
}
