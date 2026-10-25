package com.argus.rag.common.security;

import com.argus.rag.common.enums.SystemRole;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * {@link UserContext} 请求级用户上下文契约（纯 JVM/ThreadLocal，零 IO）。
 * 初始未认证 get()→null；set 后 get 同返回；clear 后归 null；set(null) 也归 null；
 * ThreadLocal 保证线程隔离——本线程设置不泄漏到他线程，他线程设置不影响本线程。
 */
@DisplayName("请求级用户上下文（UserContext）")
class UserContextTest {

    private static final AuthenticatedUser USER =
            new AuthenticatedUser(7L, "U007", "测试用户", SystemRole.USER, false);

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void initiallyEmpty() {
        assertNull(UserContext.get());
    }

    @Test
    void setThenGetReturnsSameInstance() {
        UserContext.set(USER);
        assertSame(USER, UserContext.get());
        assertEquals(7L, UserContext.get().userId());
        assertEquals("U007", UserContext.get().userCode());
    }

    @Test
    void clearRemovesCurrentUser() {
        UserContext.set(USER);
        UserContext.clear();
        assertNull(UserContext.get());
    }

    @Test
    void settingNullClears() {
        UserContext.set(USER);
        UserContext.set(null);
        assertNull(UserContext.get());
    }

    @Test
    void setOverwritesPrevious() {
        AuthenticatedUser second =
                new AuthenticatedUser(2L, "member", "成员", SystemRole.USER, true);
        UserContext.set(USER);
        UserContext.set(second);
        assertSame(second, UserContext.get());
    }

    @Test
    void doesNotLeakAcrossThreads() throws Exception {
        UserContext.set(USER);
        AtomicReference<AuthenticatedUser> workerSeesOnEntry = new AtomicReference<>();
        AtomicReference<AuthenticatedUser> workerSeesAfterOwnSetNull = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            // 另一线程初始应为 null（读不到主线程设置的值），写入 null 也不影响主线程
            workerSeesOnEntry.set(UserContext.get());
            UserContext.set(null);
            workerSeesAfterOwnSetNull.set(UserContext.get());
        });
        worker.start();
        worker.join();
        // worker 的两次读均为 null（其线程没有这个用户上下文）
        assertNull(workerSeesOnEntry.get());
        assertNull(workerSeesAfterOwnSetNull.get());
        // 主线程不受影响
        assertSame(USER, UserContext.get());
    }

    @Test
    void clearInWorkerDoesNotAffectMain() throws Exception {
        UserContext.set(USER);
        Thread worker = new Thread(() -> {
            UserContext.clear();
        });
        worker.start();
        worker.join();
        assertSame(USER, UserContext.get());
    }
}