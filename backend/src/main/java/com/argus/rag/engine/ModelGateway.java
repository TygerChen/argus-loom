package com.argus.rag.engine;

import org.springframework.stereotype.Component;

/**
 * 模型网关抽象入口。
 *
 * <p>未来用于统一模型路由（routing）、鉴权、超时、重试、限流与成本管控的集成点。
 *
 * <p>当前尚未接入多供应商的降级/熔断链。在真正落地之前，本入口<b>明确失败</b>，
 * 而不返回空白占位——空串会让调用方误以为「成功但没有内容」，属于静默丢功能的反模式。
 * 调用方在接入 {@code ModelClient} 供应商之前不应依赖本类产生有效回复。</p>
 */
@Component
public class ModelGateway {

    /**
     * 统一的模型降级入口（当前为占位）。
     *
     * @param prompt 待生成的提示词
     * @return 不会返回；该入口尚未落地可降解策略
     * @throws UnsupportedOperationException 始终抛出，明确告知调用方功能未就绪
     * @deprecated 降级链尚未实现；如确实需要降级，请直接在 {@code ModelClient} 供应商层接入 fallback。
     */
    @Deprecated
    public String generateWithFallback(String prompt) {
        throw new UnsupportedOperationException(
                "ModelGateway fallback chain is not wired yet; route through ModelClient instead.");
    }
}