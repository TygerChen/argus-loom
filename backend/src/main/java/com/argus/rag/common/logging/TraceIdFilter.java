package com.argus.rag.common.logging;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 请求级 TraceId 过滤器（P2-03 可观测性）。
 *
 * <p>为每个 HTTP 请求生成/透传 {@code traceId} 并写入 SLF4J {@link MDC}：
 * <ul>
 *   <li>入站请求带 {@code X-Trace-Id} 且只含安全字符（字母数字与 {@code -_.}，≤64 位）则沿用，
 *       便于网关/前端串联；否则生成 UUID（去掉连字符）。</li>
 *   <li>响应头回写 {@code X-Trace-Id}，前端报障时可直接带 ID 查日志。</li>
 *   <li>{@code finally} 中清理 MDC，避免线程池复用串号；绝不记录 Authorization/Cookie 等敏感头。</li>
 * </ul>
 * 注册为最高优先级 Servlet Filter（先于安全链执行），日志 pattern 中的 {@code %X{traceId}} 即来源于此。</p>
 */
@Component
public class TraceIdFilter extends OncePerRequestFilter implements Ordered {

    public static final String TRACE_ID_HEADER = "X-Trace-Id";
    public static final String MDC_TRACE_ID = "traceId";

    /** 合法 traceId：字母数字与 -_.，长度 8~64（过短/含特殊字符的一律视为不可信，重新生成）。 */
    private static final Pattern SAFE_TRACE_ID = Pattern.compile("^[A-Za-z0-9._-]{8,64}$");

    @Override
    public int getOrder() {
        // 最高优先级：让后续所有过滤器（含 Spring Security）与业务日志都带 traceId
        return Ordered.HIGHEST_PRECEDENCE;
    }

    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        // 异步派发（SSE/DeferredResult）沿用请求线程已写入的 traceId，不重复生成
        return true;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String incoming = request.getHeader(TRACE_ID_HEADER);
        String traceId = (incoming != null && SAFE_TRACE_ID.matcher(incoming).matches())
                ? incoming
                : UUID.randomUUID().toString().replace("-", "");
        try {
            MDC.put(MDC_TRACE_ID, traceId);
            response.setHeader(TRACE_ID_HEADER, traceId);
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_TRACE_ID);
        }
    }
}
