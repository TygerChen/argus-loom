package com.argus.rag.common.ratelimit;

import com.argus.rag.auth.CurrentUserService;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link RateLimitAspect} 内存滑动窗口限流算法契约（纯 JVM / Mockito，不经 Spring AOP）。
 * 锁 burst 语义：同一窗口内第 limit+1 次调用抛 {@link RateLimitExceededException}，前 limit 次放行；
 * 计数器按「user:id:方法签名」隔离——一用户超限不牵连他用户、anonymous 兜底独立计桶。
 * 注：窗口过期的 reset 由真实系统时钟驱动，time-dependent 不做稳定断言（同 FinancePdfRenderer/time 先例）。
 */
@DisplayName("限流切面算法（RateLimitAspect）")
class RateLimitAspectTest {

    private static final String SIG = "Controller.ask()";
    private static final String SIG_OTHER = "Controller.other()";

    private RateLimit rl(int limit) {
        RateLimit rl = mock(RateLimit.class);
        when(rl.limit()).thenReturn(limit);
        when(rl.window()).thenReturn(60);
        when(rl.unit()).thenReturn(TimeUnit.SECONDS);
        when(rl.scope()).thenReturn("USER");
        return rl;
    }

    private ProceedingJoinPoint jp(String signature) {
        ProceedingJoinPoint jp = mock(ProceedingJoinPoint.class);
        MethodSignature ms = mock(MethodSignature.class);
        when(ms.toShortString()).thenReturn(signature);
        when(jp.getSignature()).thenReturn(ms);
        try {
            doReturn("ok").when(jp).proceed();
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
        return jp;
    }

    private CurrentUserService userSvc(Long userId) {
        CurrentUserService svc = mock(CurrentUserService.class);
        when(svc.getRequiredCurrentUser()).thenReturn(
                new CurrentUserService.CurrentUser(userId, "u" + userId, "User" + userId));
        return svc;
    }

    private CurrentUserService anonSvc() {
        CurrentUserService svc = mock(CurrentUserService.class);
        when(svc.getRequiredCurrentUser()).thenThrow(new IllegalStateException("not logged in"));
        return svc;
    }

    @Test
    void allowedWithinLimit() throws Throwable {
        RateLimitAspect aspect = new RateLimitAspect(userSvc(1L));
        ProceedingJoinPoint jp = jp(SIG);
        assertDoesNotThrow(() -> { aspect.around(jp, rl(2)); });
        assertEquals("ok", aspect.around(jp, rl(2)));
    }

    @Test
    void burstOverLimitThrows() throws Throwable {
        RateLimitAspect aspect = new RateLimitAspect(userSvc(1L));
        ProceedingJoinPoint jp = jp(SIG);
        assertDoesNotThrow(() -> { aspect.around(jp, rl(2)); });
        assertDoesNotThrow(() -> { aspect.around(jp, rl(2)); });
        assertThrows(RateLimitExceededException.class, () -> { aspect.around(jp, rl(2)); });
    }

    @Test
    void otherUserNotBlockedByAnothersBurst() throws Throwable {
        RateLimitAspect user1 = new RateLimitAspect(userSvc(1L));
        ProceedingJoinPoint jpA = jp(SIG);
        assertDoesNotThrow(() -> { user1.around(jpA, rl(2)); });
        assertDoesNotThrow(() -> { user1.around(jpA, rl(2)); });
        assertThrows(RateLimitExceededException.class, () -> { user1.around(jpA, rl(2)); });

        RateLimitAspect user2 = new RateLimitAspect(userSvc(2L));
        assertDoesNotThrow(() -> { user2.around(jp(SIG), rl(2)); });
    }

    @Test
    void anonymousFallbackGetsOwnBucket() throws Throwable {
        RateLimitAspect aspect = new RateLimitAspect(anonSvc());
        ProceedingJoinPoint jp = jp(SIG);
        assertDoesNotThrow(() -> { aspect.around(jp, rl(2)); });
        assertDoesNotThrow(() -> { aspect.around(jp, rl(2)); });
        assertThrows(RateLimitExceededException.class, () -> { aspect.around(jp, rl(2)); });
    }

    @Test
    void exceptionCarriesLimitAndWindow() throws Throwable {
        RateLimitAspect aspect = new RateLimitAspect(userSvc(1L));
        ProceedingJoinPoint jp = jp(SIG);
        assertDoesNotThrow(() -> { aspect.around(jp, rl(2)); });
        assertDoesNotThrow(() -> { aspect.around(jp, rl(2)); });
        RateLimitExceededException e = assertThrows(RateLimitExceededException.class, () -> { aspect.around(jp, rl(2)); });
        assertEquals(2, e.getLimit());
        assertEquals(60, e.getWindow());
    }

    @Test
    void differentSignatureIsSeparateBucket() throws Throwable {
        RateLimitAspect aspect = new RateLimitAspect(userSvc(1L));
        assertDoesNotThrow(() -> { aspect.around(jp(SIG), rl(2)); });
        assertDoesNotThrow(() -> { aspect.around(jp(SIG), rl(2)); });
        assertThrows(RateLimitExceededException.class, () -> { aspect.around(jp(SIG), rl(2)); });
        assertDoesNotThrow(() -> { aspect.around(jp(SIG_OTHER), rl(2)); });
    }
}