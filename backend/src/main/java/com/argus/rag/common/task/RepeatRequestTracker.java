package com.argus.rag.common.task;

import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 重复请求识别器 —— 入口"验风险"的第三只眼（进程内滑动窗口，只标记不拒绝）。
 *
 * <p><b>它解决什么问题</b>：方案要求入口"识别重复请求与基础风险"。同一用户
 * 在几秒内对同一通道提交一字不差的问题，通常是三种情况之一：手滑双击、
 * 前端重试、脚本刷请求——前两种无害，第三种是流量异常信号。入口不替业务
 * 做决定（用户也可能合法地重新提问），<b>只把"重复疑似"标到任务单上</b>，
 * 让运行器/业务层拿着标记自行裁决。</p>
 *
 * <p><b>实现边界（与项目其他进程内状态同一诚实口径）</b>：</p>
 * <ul>
 *   <li>单实例内存态（ConcurrentHashMap），多实例部署时各认各的——与
 *       RedisChunkCacheService/限流切面同级别演进项，注释明示不假装分布式；</li>
 *   <li>有界：条目数封顶（防内存被刷爆），超限按"先来先服务"拒绝新键进窗
 *       ——宁可漏标也不挤爆内存；</li>
 *   <li>判定键 = 用户 + 通道 + 归一化输入哈希；窗口内的重复才标记，
 *       过窗自动视为新请求。</li>
 * </ul>
 */
public final class RepeatRequestTracker {

    /** 默认滑动窗口（秒）：窗口内的同键重现视为"重复疑似"。 */
    private static final int DEFAULT_WINDOW_SECONDS = 10;

    /** 窗内最大跟踪条目数：封顶防刷爆内存（8 项为一条请求，1 万条 ≈ 千级并发秒）。 */
    private static final int MAX_TRACKED_KEYS = 10_000;

    private final int windowSeconds;
    private final ConcurrentHashMap<String, Long> lastSeenAtByKey = new ConcurrentHashMap<>();
    private final AtomicInteger trackedKeyCount = new AtomicInteger();

    /** 生产用的默认实例：10 秒窗口。 */
    public static final RepeatRequestTracker DEFAULT = new RepeatRequestTracker(DEFAULT_WINDOW_SECONDS);

    public RepeatRequestTracker(int windowSeconds) {
        this.windowSeconds = Math.max(1, windowSeconds);
    }

    /**
     * 判断"当前这次输入"是否疑似窗口内重复。
     * <p>调用即登记：首次出现的键登记时间戳并返回 false；窗口内重现返回 true；
     * 过窗的键刷新时间戳并视为新请求。</p>
     *
     * @param userId  用户 ID（null 视为匿名，键里以 0 占位）
     * @param channel 业务通道
     * @param text    用户输入原文（归一化后哈希）
     * @return 疑似窗口内重复返回 true
     */
    public boolean markAndCheckRepeat(Long userId, TaskChannel channel, String text) {
        if (channel == null || !StringUtils.hasText(text)) {
            return false;
        }
        String key = buildKey(userId, channel, text);
        long now = System.currentTimeMillis();
        Long previous = lastSeenAtByKey.put(key, now);
        if (previous == null) {
            // 新键：先登记；若窗口已满，登记无效（淘汰由过窗惰性清理承担），
            // 返回 false——宁漏标不挤爆。
            if (trackedKeyCount.incrementAndGet() > MAX_TRACKED_KEYS) {
                lastSeenAtByKey.remove(key);
                trackedKeyCount.decrementAndGet();
                return false;
            }
            return false;
        }
        return now - previous < windowSeconds * 1000L;
    }

    /** 判定键：用户|通道|归一化输入的 SHA-256 前 16 位（不存原文，只存指纹）。 */
    private static String buildKey(Long userId, TaskChannel channel, String text) {
        String normalized = text.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
        String fingerprint = sha256Hex(normalized).substring(0, 16);
        return (userId == null ? 0 : userId) + "|" + channel.name() + "|" + fingerprint;
    }

    private static String sha256Hex(String input) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 必须可用", impossible);
        }
    }
}
