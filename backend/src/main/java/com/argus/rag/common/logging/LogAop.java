package com.argus.rag.common.logging;

import com.argus.rag.common.api.ApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 操作日志 AOP 切面，记录被 {@link OperationLog} 标记的方法的入参、耗时和返回值。
 *
 * <p><b>AOP 的活教材</b>（14 篇 Spring 考点的实例）：@Pointcut 声明
 * "凡是标了 @OperationLog 的类或方法"，@Around 在方法前后各插一脚——
 * 记录入参（敏感字段脱敏 maskArgs）、耗时、返回值。业务代码一行日志都没有，
 * 但所有标记过的接口都被完整记录了——这就是"横切关注点与业务分离"。</p>
 *
 * <p><b>@Order(1)</b>：多个切面时的执行优先级——日志切面要在最外层，
 * 这样连事务回滚、限流拒绝都能被记录到。</p>
 */
@Slf4j
@Component
@Aspect
@Order(1)
public class LogAop {

    @Pointcut("@within(com.argus.rag.common.logging.OperationLog) || @annotation(com.argus.rag.common.logging.OperationLog)")
    public void logAdvicePointcut() {
    }

    @Around("logAdvicePointcut()")
    public Object logAroundAdvice(ProceedingJoinPoint joinPoint) throws Throwable {
        String className = joinPoint.getTarget().getClass().getSimpleName();
        String methodName = joinPoint.getSignature().getName();
        String args = maskArgs(joinPoint.getArgs());

        long startTime = System.currentTimeMillis();
        log.info("--> [{}] {}({})", className, methodName, args);

        try {
            Object result = joinPoint.proceed();
            long elapsed = System.currentTimeMillis() - startTime;
            logResult(className, methodName, elapsed, result);
            return result;
        } catch (Throwable e) {
            long elapsed = System.currentTimeMillis() - startTime;
            log.error("<-- [{}] {} FAIL [{}ms] {}", className, methodName, elapsed, e.toString());
            throw e;
        }
    }

    private void logResult(String className, String methodName, long elapsed, Object result) {
        if (result instanceof ApiResponse<?> apiResponse) {
            log.info("<-- [{}] {} [{}ms] success={} {}",
                    className, methodName, elapsed,
                    apiResponse.isSuccess(),
                    apiResponse.getMessage() != null ? apiResponse.getMessage() : "");
        } else {
            log.info("<-- [{}] {} [{}ms]", className, methodName, elapsed);
        }
    }

    /** 命中即视为敏感字段，入参日志一律以掩码输出，避免明文落盘。 */
    private static final Set<String> SENSITIVE_KEYS = Set.of(
            "password", "pwd", "passwd",
            "oldPassword", "newPassword",
            "accessToken", "refreshToken", "token", "secret", "credential");

    /** 单个入参值超过此长度即截断，防止长内容刷屏。 */
    private static final int MAX_VALUE_LEN = 120;

    private String maskArgs(Object[] args) {
        if (args == null || args.length == 0) {
            return "";
        }
        return Arrays.stream(args)
                .map(this::maskArg)
                .collect(Collectors.joining(", "));
    }

    /** record 入参按无参 accessor 方法遍历；敏感字段掩码、超长截断；非 record 保持 toString。 */
    private String maskArg(Object arg) {
        if (arg == null) {
            return "null";
        }
        Class<?> type = arg.getClass();
        if (!type.isRecord()) {
            return cap(String.valueOf(arg));
        }
        String fields = Arrays.stream(type.getMethods())
                .filter(method -> !method.getDeclaringClass().equals(Object.class))
                .filter(method -> method.getParameterCount() == 0)
                .map(method -> {
                    String name = method.getName();
                    Object value = readValue(method, arg);
                    if (SENSITIVE_KEYS.contains(name)) {
                        return name + "=******";
                    }
                    return name + "=" + cap(String.valueOf(value));
                })
                .collect(Collectors.joining(", "));
        return type.getSimpleName() + "[" + fields + "]";
    }

    private Object readValue(Method method, Object record) {
        try {
            return method.invoke(record);
        } catch (ReflectiveOperationException ignored) {
            return "<unreadable>";
        }
    }

    private static String cap(String value) {
        if (value == null) {
            return "null";
        }
        if (value.length() <= MAX_VALUE_LEN) {
            return value;
        }
        return value.substring(0, MAX_VALUE_LEN) + "…(" + value.length() + ")";
    }
}
