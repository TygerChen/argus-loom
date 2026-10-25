package com.argus.rag.metrics.cost;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link LlmCostCalculatorImpl} 计价契约（纯 JVM）。
 * 输入×每千 token 费率 /1000（HALF_UP 保留 6 位小数）。已知模型取各自费率，
 * 未知模型回落默认费率（0.0008 输入 / 0.002 输出）。
 */
@DisplayName("LLM 计价（LlmCostCalculatorImpl）")
class LlmCostCalculatorImplTest {

    private final LlmCostCalculatorImpl calc = new LlmCostCalculatorImpl();

    @Test
    void knownModelUsesItsOwnRate() {
        // qwen-plus: 0.0008 / 0.002
        BigDecimal cost = calc.calculate("qwen-plus", 1000, 1000);
        // 输入 0.0008*1000/1000=0.000800，输出 0.002*1000/1000=0.002000 → 合计 0.002800
        assertEquals(new BigDecimal("0.002800"), cost);
    }

    @Test
    void qwenTurboIsCheaper() {
        BigDecimal cost = calc.calculate("qwen-turbo", 1000, 1000);
        assertEquals(new BigDecimal("0.000900"), cost); // 0.0003+0.0006
    }

    @Test
    void unknownModelFallsBackToDefaults() {
        BigDecimal cost = calc.calculate("gpt-unknown", 1000, 1000);
        assertEquals(new BigDecimal("0.002800"), cost); // 默认 0.0008+0.0020
    }

    @Test
    void proportionalToTokenCount() {
        BigDecimal base = calc.calculate("qwen-plus", 1000, 1000);   // 0.002800
        BigDecimal two = calc.calculate("qwen-plus", 2000, 2000);    // 0.005600
        assertEquals(new BigDecimal("0.005600"), two);
        assertEquals(new BigDecimal("0.000000"), calc.calculate("qwen-plus", 0, 0));
    }

    @Test
    void scaleAndHalfUpPreserved() {
        // 1 token 输入 → 0.0008/1000 = 0.0000008 → HALF_UP 六位 → 0.000001
        BigDecimal oneToken = calc.calculate("qwen-plus", 1, 0);
        assertEquals(new BigDecimal("0.000001"), oneToken);
        assertEquals(6, oneToken.scale());
    }
}