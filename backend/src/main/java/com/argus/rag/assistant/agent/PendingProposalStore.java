package com.argus.rag.assistant.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 待确认写提案存储（第十一步：写操作确认门的执行侧锚点）。
 *
 * <p><b>它承载什么</b>：对话循环内写工具登记的"待确认提案"——改动参数的
 * <b>确定性快照</b>（documentId/sheetId/行列坐标/新值）。用户在界面上点确认后，
 * 确认 API 按这份快照原样执行——<b>运行器按提案执行，不经模型复述、不重新解析</b>
 * （运行器铁律：执行是运行器的事）。用户确认的即最终执行的，所见即所写。</p>
 *
 * <p><b>状态机</b>：REGISTERED →（确认）EXECUTED /（取消）CANCELLED，
 * 转移用 {@code compute} 原子完成——并发双击确认只有一方真正执行。</p>
 *
 * <p><b>实现边界（与 RepeatRequestTracker 同一诚实口径）</b>：单实例内存态；
 * 有界（上限 {@value #MAX_PROPOSALS} 条，超限拒收新提案——宁可不服务也不挤爆内存）；
 * TTL {@value #DEFAULT_TTL_SECONDS} 秒，过期提案确认时拒绝（前端提示重新发起）。
 * 重启即失——前端卡片过期提示"重新发起"即可，落库属后续演进项。</p>
 */
@Component
public class PendingProposalStore {

    private static final Logger log = LoggerFactory.getLogger(PendingProposalStore.class);

    /** 提案默认有效期（秒）：确认窗口过了就作废，防"陈年提案"被意外执行。 */
    static final int DEFAULT_TTL_SECONDS = 600;

    /** 内存上限：封顶防刷爆（每条提案含参数快照，千级足够单实例使用）。 */
    static final int MAX_PROPOSALS = 1_000;

    /** 提案状态。 */
    public enum ProposalStatus { REGISTERED, EXECUTED, CANCELLED }

    /**
     * 待确认写提案（参数确定性快照）。
     *
     * @param proposalId   提案号（幂等键——确认 API 以此防重复执行）
     * @param userId       提议人（确认时校验同一人）
     * @param sessionId    会话 ID（0=未知，如无会话上下文的路径）
     * @param toolName     来源写工具名
     * @param summary      面向用户的改动摘要（"将任务「X」状态改为「已完成」"）
     * @param documentId   目标文档
     * @param documentName 目标文档名（前端卡片展示用）
     * @param updates      批量单元格更新快照（格式与 batchUpdateCells 入参一致）
     * @param status       状态机当前态
     * @param expiresAt    过期时间戳（毫秒）
     */
    public record PendingProposal(
            String proposalId,
            Long userId,
            Long sessionId,
            String toolName,
            String summary,
            Long documentId,
            String documentName,
            List<Map<String, Object>> updates,
            ProposalStatus status,
            long expiresAt
    ) {
    }

    private final int ttlSeconds;
    private final ConcurrentHashMap<String, PendingProposal> proposals = new ConcurrentHashMap<>();
    private final AtomicInteger trackedCount = new AtomicInteger();

    public PendingProposalStore() {
        this(DEFAULT_TTL_SECONDS);
    }

    /** 可注入 TTL 的构造（测试用）。 */
    public PendingProposalStore(int ttlSeconds) {
        this.ttlSeconds = Math.max(1, ttlSeconds);
    }

    /**
     * 登记一份待确认提案。
     *
     * @return 登记成功的提案（状态 REGISTERED）
     * @throws IllegalStateException 内存已满（拒绝新提案，fail-closed——写操作宁可不服务）
     */
    public PendingProposal register(Long userId, Long sessionId, String toolName,
                                    String summary, Long documentId, String documentName,
                                    List<Map<String, Object>> updates) {
        if (updates == null || updates.isEmpty()) {
            throw new IllegalArgumentException("提案更新内容不能为空");
        }
        if (trackedCount.get() >= MAX_PROPOSALS) {
            evictExpired();
            if (trackedCount.get() >= MAX_PROPOSALS) {
                log.warn("proposal.store: 内存已满（{}），拒绝新提案", MAX_PROPOSALS);
                throw new IllegalStateException("待确认提案数量已达上限，请稍后再试");
            }
        }
        String proposalId = "P-" + UUID.randomUUID().toString().substring(0, 8);
        PendingProposal proposal = new PendingProposal(
                proposalId, userId, sessionId == null ? 0L : sessionId,
                toolName, summary, documentId, documentName,
                List.copyOf(updates), ProposalStatus.REGISTERED,
                System.currentTimeMillis() + ttlSeconds * 1000L);
        proposals.put(proposalId, proposal);
        trackedCount.incrementAndGet();
        log.info("proposal.store: registered {} user={} doc={} summary={}",
                proposalId, userId, documentId, summary);
        return proposal;
    }

    /**
     * 确认并取出提案供执行（CAS：REGISTERED → EXECUTED）。
     *
     * @return 取出的提案（状态已置 EXECUTED）——调用方据此执行写
     * @throws com.argus.rag.common.exception.BusinessException 提案不存在/已过期/非本人/已终态
     */
    public PendingProposal confirmForExecution(String proposalId, Long userId) {
        String key = proposalId == null ? "" : proposalId.trim();
        PendingProposal[] out = new PendingProposal[1];
        proposals.compute(key, (k, existing) -> {
            if (existing == null) {
                throw new com.argus.rag.common.exception.BusinessException("提案不存在或已过期，请重新发起");
            }
            if (!existing.userId().equals(userId)) {
                log.warn("proposal.store: 越权确认被拒 {} user={} owner={}", key, userId, existing.userId());
                throw new com.argus.rag.common.exception.BusinessException("只有提案发起人可以确认");
            }
            if (System.currentTimeMillis() > existing.expiresAt()) {
                throw new com.argus.rag.common.exception.BusinessException("提案已过期，请重新发起");
            }
            if (existing.status() != ProposalStatus.REGISTERED) {
                throw new com.argus.rag.common.exception.BusinessException("提案已处理过，无需重复确认");
            }
            PendingProposal confirmed = new PendingProposal(
                    existing.proposalId(), existing.userId(), existing.sessionId(),
                    existing.toolName(), existing.summary(), existing.documentId(), existing.documentName(),
                    existing.updates(), ProposalStatus.EXECUTED, existing.expiresAt());
            out[0] = confirmed;
            return confirmed;
        });
        return out[0];
    }

    /**
     * 取消提案（REGISTERED → CANCELLED；已终态返回 false 不抛——取消是尽力而为）。
     */
    public boolean cancel(String proposalId, Long userId) {
        String key = proposalId == null ? "" : proposalId.trim();
        boolean[] cancelled = new boolean[1];
        proposals.compute(key, (k, existing) -> {
            if (existing == null || !existing.userId().equals(userId)
                    || existing.status() != ProposalStatus.REGISTERED
                    || System.currentTimeMillis() > existing.expiresAt()) {
                return existing;
            }
            cancelled[0] = true;
            return new PendingProposal(
                    existing.proposalId(), existing.userId(), existing.sessionId(),
                    existing.toolName(), existing.summary(), existing.documentId(), existing.documentName(),
                    existing.updates(), ProposalStatus.CANCELLED, existing.expiresAt());
        });
        return cancelled[0];
    }

    /** 查看提案（不转移状态；不存在/非本人返回 null）。 */
    public PendingProposal find(String proposalId, Long userId) {
        PendingProposal proposal = proposals.get(proposalId == null ? "" : proposalId.trim());
        return proposal != null && proposal.userId().equals(userId) ? proposal : null;
    }

    /** 惰性清理过期提案（登记侧满员时触发；绝对数量小，无需后台线程）。 */
    private void evictExpired() {
        long now = System.currentTimeMillis();
        proposals.entrySet().removeIf(e -> {
            boolean expired = e.getValue().expiresAt() < now;
            if (expired) {
                trackedCount.decrementAndGet();
            }
            return expired;
        });
    }

    /** 当前跟踪条数（监控/测试用）。 */
    public int trackedCount() {
        return trackedCount.get();
    }
}
