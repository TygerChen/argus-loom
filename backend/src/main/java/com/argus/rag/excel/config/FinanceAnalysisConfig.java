package com.argus.rag.excel.config;

import com.argus.rag.excel.service.FinanceAnalysisService.AnalysisThresholds;
import java.math.BigDecimal;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 财务分析参数 Bean 装配。
 *
 * <p>把 {@code argus.rag.finance.analysis.*} 配置项绑定为 {@link AnalysisThresholds}
 * 单例，供 {@code FinanceAnalysisService} 注入使用。阈值改配置即可生效，
 * 不用改代码；缺省值与《财务异常提醒》产品文档一致。</p>
 */
@Configuration
public class FinanceAnalysisConfig {

    /** 单笔大额支出阈值（元）：超过记 LARGE_AMOUNT 提醒。 */
    @Bean
    public AnalysisThresholds analysisThresholds(
            @Value("${argus.rag.finance.analysis.large-amount-limit:50000}") BigDecimal largeAmountLimit,
            @Value("${argus.rag.finance.analysis.repeat-payment-count:3}") int repeatPaymentCount) {
        return new AnalysisThresholds(largeAmountLimit, repeatPaymentCount);
    }
}
