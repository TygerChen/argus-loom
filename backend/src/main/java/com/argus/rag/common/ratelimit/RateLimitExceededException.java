package com.argus.rag.common.ratelimit;

/**
 * 限流超限异常。
 * <p>当用户在指定时间窗口内请求次数超过限制时抛出，
 * 由 {@link com.argus.rag.common.exception.GlobalExceptionHandler} 统一处理为 HTTP 429。</p>
 */
public class RateLimitExceededException extends RuntimeException {

    private final int limit;
    private final int window;
    private final String scope;

    public RateLimitExceededException(int limit, int window, String scope) {
        super(String.format("请求过于频繁，%s 维度每 %d 秒最多 %d 次，请稍后再试。", scope, window, limit));
        this.limit = limit;
        this.window = window;
        this.scope = scope;
    }

    public int getLimit() {
        return limit;
    }

    public int getWindow() {
        return window;
    }

    public String getScope() {
        return scope;
    }
}