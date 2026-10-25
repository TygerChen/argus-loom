package com.argus.rag.common.ratelimit;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.concurrent.TimeUnit;

/**
 * 声明式限流注解。
 * <p>标记在 Controller 方法上，基于用户维度的滑动窗口计数器进行限流，
 * 超限时抛出 {@link RateLimitExceededException}，由全局异常处理器返回 HTTP 429。</p>
 *
 * <pre>{@code
 *   @RateLimit(limit = 20, window = 60)
 *   @PostMapping("/ask")
 *   public ApiResponse<AskQuestionResponse> ask(...) { ... }
 * }</pre>
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface RateLimit {

    /**
     * 时间窗口内允许的最大请求次数。
     */
    int limit();

    /**
     * 时间窗口长度。
     */
    int window();

    /**
     * 时间窗口单位，默认为秒。
     */
    TimeUnit unit() default TimeUnit.SECONDS;

    /**
     * 限流范围描述，仅用于日志。
     */
    String scope() default "USER";
}