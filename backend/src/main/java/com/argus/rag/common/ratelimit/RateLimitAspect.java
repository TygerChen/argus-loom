package com.argus.rag.common.ratelimit;

import com.argus.rag.auth.CurrentUserService;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 限流 AOP 切面。
 * <p>基于 ConcurrentHashMap 实现内存级滑动窗口计数器，以用户 ID 为维度进行限流。
 * 生产环境高并发场景建议替换为 Redis 滑动窗口实现。</p>
 */
@Aspect
@Component
public class RateLimitAspect {

    private static final Logger log = LoggerFactory.getLogger(RateLimitAspect.class);

    /**
     * 计数器存储：key = 用户ID:方法签名, value = (窗口起始毫秒, 计数)
     */
    private final Map<String, WindowCounter> counters = new ConcurrentHashMap<>();

    private final CurrentUserService currentUserService;

    public RateLimitAspect(CurrentUserService currentUserService) {
        this.currentUserService = currentUserService;
    }

    @Around("@annotation(rateLimit)")
    public Object around(ProceedingJoinPoint joinPoint, RateLimit rateLimit) throws Throwable {
        String key = "anonymous";
        try {
            CurrentUserService.CurrentUser user = currentUserService.getRequiredCurrentUser();
            key = "user:" + user.userId();
        } catch (Exception ignored) {
        }
        key += ":" + joinPoint.getSignature().toShortString();
        long windowMs = rateLimit.unit().toMillis(rateLimit.window());

        WindowCounter counter = counters.compute(key, (k, v) -> {
            long now = System.currentTimeMillis();
            if (v == null || now - v.windowStartMs > windowMs) {
                return new WindowCounter(now, new AtomicLong(1));
            }
            v.count.incrementAndGet();
            return v;
        });

        // 检查是否仍在当前窗口内
        long now = System.currentTimeMillis();
        if (now - counter.windowStartMs > windowMs) {
            // 窗口已过期，重置
            counters.put(key, new WindowCounter(now, new AtomicLong(1)));
        } else if (counter.count.get() > rateLimit.limit()) {
            log.warn("限流触发: key={}, method={}, limit={}/{}{}, current={}",
                    key, joinPoint.getSignature().toShortString(),
                    rateLimit.limit(), rateLimit.window(),
                    rateLimit.unit().name().toLowerCase(), counter.count.get());
            throw new RateLimitExceededException(rateLimit.limit(), rateLimit.window(), rateLimit.scope());
        }

        return joinPoint.proceed();
    }

    /**
     * 滑动窗口计数器。
     */
    private record WindowCounter(long windowStartMs, AtomicLong count) {
    }
}