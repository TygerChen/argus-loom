package com.argus.rag.common.logging;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P2-03：请求级 TraceId 过滤器契约。
 * 锁住：无头生成 UUID、合法入站头透传、非法头拒用重新生成、响应头回写、
 * MDC 在链内可见且请求结束后清理（防线程池串号）。
 */
@DisplayName("TraceId 过滤器（TraceIdFilter / P2-03）")
class TraceIdFilterTest {

    private final TraceIdFilter filter = new TraceIdFilter();

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    @Test
    void generatesTraceIdAndWritesResponseHeader() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/qa/ask");
        MockHttpServletResponse response = new MockHttpServletResponse();
        String[] seenInChain = new String[1];

        filter.doFilter(request, response, (req, res) -> seenInChain[0] = MDC.get(TraceIdFilter.MDC_TRACE_ID));

        assertNotNull(seenInChain[0], "链内应能从 MDC 读到 traceId");
        assertEquals(32, seenInChain[0].length(), "默认 traceId 为去连字符 UUID");
        assertEquals(seenInChain[0], response.getHeader(TraceIdFilter.TRACE_ID_HEADER), "响应头应回写 traceId");
        assertNull(MDC.get(TraceIdFilter.MDC_TRACE_ID), "请求结束后 MDC 必须清理，防线程池串号");
    }

    @Test
    void reusesSafeIncomingTraceId() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/qa/ask");
        request.addHeader(TraceIdFilter.TRACE_ID_HEADER, "gateway-abc-123456");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertEquals("gateway-abc-123456", response.getHeader(TraceIdFilter.TRACE_ID_HEADER),
                "合法入站 traceId 应原样透传，便于跨端串联");
    }

    @Test
    void rejectsUnsafeIncomingTraceId() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/qa/ask");
        request.addHeader(TraceIdFilter.TRACE_ID_HEADER, "bad trace id!\nX-Injected: 1");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        String echoed = response.getHeader(TraceIdFilter.TRACE_ID_HEADER);
        assertNotNull(echoed);
        assertEquals(32, echoed.length(), "非法入站头必须弃用并重新生成");
        assertTrue(echoed.matches("^[A-Za-z0-9._-]{8,64}$"));
    }

    @Test
    void runsAtHighestPrecedence() {
        assertEquals(Ordered.HIGHEST_PRECEDENCE, filter.getOrder(),
                "traceId 必须先于安全链写入，保证后续日志均可归因");
    }
}
