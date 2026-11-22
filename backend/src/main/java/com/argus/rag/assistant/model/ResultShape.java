package com.argus.rag.assistant.model;

import com.argus.rag.planning.ExecutionPlan;
import com.argus.rag.verify.VerificationResult;

import java.util.List;

/**
 * 返回结果形态 —— 第九步"返回结果"的形态分类（代码派生，模型无权决定）。
 *
 * <p><b>方案四形态与派生规则</b>（按优先级，代码仲裁）：</p>
 * <ol>
 *   <li>{@link #NEEDS_CONFIRMATION}：方案确认门为真（入口标 HIGH 的删除/导出/外发类）
 *       ——交付整理结果 + 高风险提示，正式执行需确认与权限；</li>
 *   <li>{@link #REFERENCE}：第八步独立校验未获支撑——已有核验提示标注（降级形态，
 *       本枚举仅作日志口径，不重复追加标注）；</li>
 *   <li>{@link #CONFLICT}：证据/理解中发现版本或口径差异——并列多方信息 + 口径提示，
 *       <b>不替用户拍板</b>；</li>
 *   <li>{@link #DETERMINED}：验证通过的唯一结论——正常交付。</li>
 * </ol>
 *
 * <p>{@link #REFUSED} 为 QA 链路拒答形态（RETRIEVAL_NOT_GROUNDED），助手链路
 * 出错时也归入此类口径。</p>
 */
public enum ResultShape {

    /** 确定答案：验证通过、唯一结论 */
    DETERMINED("确定答案"),

    /** 冲突结果：多版本/口径并存，一起返回不拍板 */
    CONFLICT("冲突结果"),

    /** 参考结果：证据不足或未获独立校验支撑（降级形态） */
    REFERENCE("参考资料"),

    /** 需确认：高风险动作，交付整理结果但正式执行需确认 */
    NEEDS_CONFIRMATION("需确认"),

    /** 拒答：无证据或无权（安全提示，不泄露存在性） */
    REFUSED("拒答");

    private final String displayName;

    ResultShape(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }

    /**
     * 形态派生（代码仲裁；模型输出不影响形态判定）。
     *
     * @param plan            第三步方案（needConfirm 来源；可 null）
     * @param verification    第八步校验结果（可 null/empty）
     * @param ambiguities     第二步理解的歧义清单（冲突信号来源）
     * @return 结果形态
     */
    public static ResultShape resolve(ExecutionPlan plan,
                                      VerificationResult verification,
                                      List<String> ambiguities) {
        // ① 确认门（入口高风险）——最高优先级：即使内容被验证通过，执行本身仍需确认
        if (plan != null && plan.needConfirm()) {
            return NEEDS_CONFIRMATION;
        }
        // ② 独立校验未获支撑 → 参考形态（降级标注由第八步 annotate 追加，此处只定口径）
        if (verification != null && !verification.isEmpty() && !verification.supported()) {
            return REFERENCE;
        }
        // ③ 冲突信号：理解阶段的歧义里明确出现版本/口径差异词
        if (ambiguities != null) {
            for (String ambiguity : ambiguities) {
                if (ambiguity != null && (ambiguity.contains("冲突") || ambiguity.contains("不一致")
                        || ambiguity.contains("版本差异"))) {
                    return CONFLICT;
                }
            }
        }
        return DETERMINED;
    }

    /**
     * 友好提示（追加到回复末尾；DETERMINED/REFERENCE 无额外提示——
     * REFERENCE 的核验提示已由第八步生成，避免双标注）。
     */
    public String friendlyNotice() {
        return switch (this) {
            case DETERMINED, REFUSED, REFERENCE -> "";
            case CONFLICT -> "\n\n> 📚 **口径提示**：知识库中存在版本/口径差异，本回答已尽量并列多方信息；"
                    + "请以最新有效版本为准，或向制度所有方确认后再执行。";
            case NEEDS_CONFIRMATION -> "\n\n> ⚠️ **高风险动作提示**：该请求涉及导出/删除/外发等敏感操作。"
                    + "本回复仅为方案与证据的整理结果，正式执行前需要你确认并具备相应权限。";
        };
    }
}
