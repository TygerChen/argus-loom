package com.argus.rag.engine.freeweb;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;

/**
 * 远程抓取 URL 安全策略（SSRF 防护）。
 *
 * <p>Agent 的网页抓取目标来自搜索结果或模型输出，属于不可信输入。若不加限制，
 * 模型可能被诱导请求 {@code http://127.0.0.1:8080/actuator}、云元数据服务
 * （169.254.169.254）或内网管理接口，造成服务器端请求伪造。本策略在每次请求
 * 前与每次重定向后都必须调用：</p>
 * <ol>
 *   <li>仅允许 http/https 协议；</li>
 *   <li>主机必须解析为公网地址——loopback、RFC1918 私网、链路本地、CGN、
 *       保留与多播段全部拒绝；</li>
 *   <li>主机名解析失败时拒绝（fail-closed）。</li>
 * </ol>
 */
public final class SafeRemoteUrlPolicy {

    private SafeRemoteUrlPolicy() {
    }

    /**
     * 判断 URL 是否允许远程抓取。任何解析失败都按拒绝处理。
     */
    public static boolean isAllowed(URI uri) {
        if (uri == null) {
            return false;
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            return false;
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            return false;
        }
        return isPublicHost(host);
    }

    /**
     * 主机名/IP 必须解析为公网地址。十进制/十六进制等非常规 loopback 写法
     * （2130706433、0x7f000001）由 {@link InetAddress#getByName} 统一归一化后拦截。
     */
    private static boolean isPublicHost(String host) {
        try {
            InetAddress[] addresses = InetAddress.getAllByName(host);
            for (InetAddress address : addresses) {
                if (!isPublicAddress(address)) {
                    return false;
                }
            }
            return addresses.length > 0;
        } catch (UnknownHostException e) {
            return false;
        }
    }

    private static boolean isPublicAddress(InetAddress address) {
        return !address.isLoopbackAddress()
                && !address.isSiteLocalAddress()
                && !address.isLinkLocalAddress()
                && !address.isAnyLocalAddress()
                && !address.isMulticastAddress()
                && !isUniqueLocalIpv6(address)
                && !isCarrierGradeNat(address);
    }

    /** IPv6 ULA（fc00::/7）：JDK 没有内置判定，按前 8 位前缀判断。 */
    private static boolean isUniqueLocalIpv6(InetAddress address) {
        byte[] bytes = address.getAddress();
        return bytes.length == 16 && (bytes[0] & 0xFE) == 0xFC;
    }

    /** 运营商级 NAT（100.64.0.0/10）。 */
    private static boolean isCarrierGradeNat(InetAddress address) {
        byte[] bytes = address.getAddress();
        return bytes.length == 4 && bytes[0] == 100 && (bytes[1] & 0xC0) == 64;
    }
}
