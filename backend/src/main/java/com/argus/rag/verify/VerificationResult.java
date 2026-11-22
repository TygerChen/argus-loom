package com.argus.rag.verify;

import java.util.List;

/**
 * 独立校验结果 —— 第八步"验证结果"的产出。
 *
 * <p><b>方案边界</b>：验证是<b>独立环节</b>（裁判与生成者无关的另一次 LLM 调用），
 * 不让大模型自己验证自己。失败处置走降级而非丢弃：把结果作为参考资料，
 * 明确告知用户证据缺陷，绝不编造没有依据的答案。</p>
 *
 * @param supported        答案是否被证据整体支撑
 * @param score            可支撑度 0-100（对照证据逐条检查后的量化）
 * @param unsupportedClaims 未获证据支撑的具体表述（逐条列出，降级提示的素材）
 * @param reason           一句话校验结论
 */
public record VerificationResult(
        boolean supported,
        int score,
        List<String> unsupportedClaims,
        String reason
) {

    /** 空结果：校验关闭/失败时的 fail-soft 值（不加任何标注，行为与从前一致）。 */
    public static VerificationResult empty() {
        return new VerificationResult(true, 100, List.of(), "");
    }

    public boolean isEmpty() {
        return reason == null || reason.isBlank();
    }
}
