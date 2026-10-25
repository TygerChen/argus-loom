package com.argus.rag.engine.freeweb;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SafeRemoteUrlPolicy} SSRF 防护行为锁：
 * 网页抓取只允许 http/https 公网地址；loopback、私网、链路本地、保留段
 * 以及解析到这些地址的主机名一律拒绝，防止 Agent 被诱导探测内网。
 */
@DisplayName("远程抓取 URL 白名单（SafeRemoteUrlPolicy）")
class SafeRemoteUrlPolicyTest {

    private void assertBlocked(String url) {
        assertFalse(SafeRemoteUrlPolicy.isAllowed(URI.create(url)), url);
    }

    private void assertAllowed(String url) {
        assertTrue(SafeRemoteUrlPolicy.isAllowed(URI.create(url)), url);
    }

    @Test
    void allowsPublicHttpAndHttps() {
        assertAllowed("https://www.example.com/news");
        assertAllowed("http://cn.bing.com/search?q=x");
    }

    @Test
    void blocksNonHttpSchemes() {
        assertBlocked("file:///etc/passwd");
        assertBlocked("ftp://example.com/file");
        assertBlocked("gopher://example.com");
    }

    @Test
    void blocksLoopbackAddresses() {
        assertBlocked("http://127.0.0.1/x");
        assertBlocked("http://127.3.4.5/x");
        assertBlocked("http://localhost/x");
        assertBlocked("http://[::1]/x");
    }

    @Test
    void blocksPrivateNetworks() {
        assertBlocked("http://10.0.0.1/x");
        assertBlocked("http://10.255.255.255/x");
        assertBlocked("http://172.16.0.1/x");
        assertBlocked("http://172.31.255.255/x");
        assertBlocked("http://192.168.1.1/x");
    }

    @Test
    void blocksLinkLocalAndReservedRanges() {
        assertBlocked("http://169.254.169.254/latest/meta-data");
        assertBlocked("http://0.0.0.0/x");
        assertBlocked("http://100.64.0.1/x");
    }

    @Test
    void blocksOtherLoopbackAliases() {
        assertBlocked("http://0x7f000001/x");
        assertBlocked("http://2130706433/x");
    }

    @Test
    void rejectsMalformedUrls() {
        assertThrows(IllegalArgumentException.class,
                () -> SafeRemoteUrlPolicy.isAllowed(URI.create("http://inva lid.com")));
    }
}
