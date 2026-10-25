package com.argus.rag.job.service;

import com.argus.rag.job.mapper.BackgroundJobMapper;
import com.argus.rag.job.model.entity.BackgroundJob;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 可靠任务队列服务 —— 用一张数据库表实现的"消息队列"（08 篇的心脏）。
 *
 * <p><b>它要解决的全是分布式系统的经典问题</b>，四个机制各对应一道面试题：</p>
 *
 * <p><b>① 幂等入队（enqueue）——"任务会不会重复创建？"</b>
 * 上传接口可能因用户重复点击/网络重试被调两次，但 idempotency_key
 * （通常是 documentId 派生）有唯一索引兜底：先查同键任务，存在直接复用。
 * 这是和 01 篇上传幂等、V7 财务导入幂等同源的思路——<b>所有入口都要假设
 * 调用方会重复敲门</b>。</p>
 *
 * <p><b>② 原子领取（claimDueJobs）——"两个 Worker 抢同一个任务怎么办？"</b>
 * selectDueJobs 选出候选（FOR UPDATE SKIP LOCKED：跳过已被别的Worker锁住的行，
 * 不等待不阻塞），然后 update ... where status=QUEUED 做条件更新抢认领——
 * 更新影响行数 = 1 才算抢到。即便两个实例同时轮询，同一任务也只会被一个认领。
 * SKIP LOCKED 是 PostgreSQL 实现轻量任务队列的关键招式，比引入 MQ 便宜得多。</p>
 *
 * <p><b>③ 租约（lease_until / lease_owner）——"Worker 拿到任务后崩溃了怎么办？"</b>
 * 认领时写入 5 分钟后的租约到期时间；任务执行完必须凭自己的 workerId 置 SUCCEEDED。
 * 如果 Worker 中途挂了，租约到期后任务重新变回"到期可领取"，其他实例自动接管——
 * <b>实例崩溃自愈</b>。代价：任务还在跑但超过 5 分钟时，可能被第二个 Worker
 * 重复执行——所以业务执行必须幂等（ETL 开头先清理旧产物再重建，天然可重跑），
 * 这是"队列保证 at-least-once，业务层保证幂等"的教科书分工。</p>
 *
 * <p><b>④ 指数退避 + 死信（failOrRetry）——"一直失败的任务怎么办？"</b>
 * 按递增间隔 {1,5,15} 分钟退避重试（claim 预增 attempt_count，索引取 attempts-1 与之对齐），
 * 重试次数（maxAttempts，不含首次执行）耗尽进 DEAD_LETTER，给下游恢复的时间越来越长），
 * 3 次耗尽进 DEAD_LETTER：不再自动重试，但记录完整错误码/错误信息，等人工排查后
 * 处理。<b>死信的价值：让失败"可见且可控"，而不是无限重试里悄悄消失</b>。
 * 08 篇的"插入一条 DEAD_LETTER 观察"实验就是往这个机制里看的。</p>
 */
@Service
public class BackgroundJobService {

    private static final Logger log = LoggerFactory.getLogger(BackgroundJobService.class);
    /** 失败重试退避（分钟）：第 1/2/3 次失败后的等待时间。 */
    private static final int[] RETRY_BACKOFF_MINUTES = {1, 5, 15};
    /** 默认租约时长（分钟）：超过视为 Worker 已崩溃。 */
    private static final int LEASE_MINUTES = 5;

    private final BackgroundJobMapper jobMapper;

    public BackgroundJobService(BackgroundJobMapper jobMapper) {
        this.jobMapper = jobMapper;
    }

    /** 入队命令。 */
    public record NewBackgroundJobCommand(
            String jobType,
            String resourceType,
            Long resourceId,
            String idempotencyKey,
            String payloadJson,
            int maxAttempts) {
    }

    /** 失败信息。 */
    public record JobFailure(String errorCode, String errorMessage) {
    }

    /**
     * 幂等入队：同 idempotency_key 返回既有任务，不重复创建——但仅限"进行中"的任务。
     * <p>
     * 命中已有任务时分三种情况（新手最容易漏第三种）：
     * </p>
     * <ul>
     *   <li><b>QUEUED / RUNNING</b>：同一任务还在排队或执行中，直接复用（幂等命中），
     *       防止重复点击/事件重放导致重复执行；</li>
     *   <li><b>SUCCEEDED</b>：任务已成功完成，同样复用返回——上传链路的幂等键
     *       基于 documentId 派生，文档没变就不该再跑一遍 ETL；</li>
     *   <li><b>DEAD_LETTER</b>：历史任务已死信（重试耗尽），但调用方又来入队了——
     *       典型场景：用户对失败文档点"重试上传"（DocumentDeleteService 重新触发）。
     *       此时<b>不能返回死信任务</b>（否则永远不会再执行），也不能插入新行
     *       （幂等键唯一索引会挡住），正确动作是把老任务<b>复活</b>：
     *       重置为 QUEUED、清空错误与租约、重置尝试次数、立即调度。</li>
     * </ul>
     * <p>并发说明：两个请求同时给同一死信键复活，条件 UPDATE 的 status 守卫
     * （只认 DEAD_LETTER）保证只有一行生效，另一行影响数为 0 走返回分支，安全。</p>
     */
    @Transactional
    public BackgroundJob enqueue(NewBackgroundJobCommand command) {
        BackgroundJob existing = jobMapper.selectByIdempotencyKey(command.idempotencyKey());
        if (existing != null) {
            if (BackgroundJob.STATUS_DEAD_LETTER.equals(existing.getStatus())) {
                log.info("死信任务重新入队（复活）: jobId={}, key={}",
                        existing.getId(), command.idempotencyKey());
                reviveDeadLetter(existing, command);
                return existing;
            }
            log.info("任务幂等命中，复用既有任务: jobId={}, key={}", existing.getId(), command.idempotencyKey());
            return existing;
        }
        BackgroundJob job = new BackgroundJob();
        job.setJobType(command.jobType());
        job.setResourceType(command.resourceType());
        job.setResourceId(command.resourceId());
        job.setIdempotencyKey(command.idempotencyKey());
        job.setPayloadJson(command.payloadJson());
        job.setStatus(BackgroundJob.STATUS_QUEUED);
        job.setAttemptCount(0);
        job.setMaxAttempts(Math.max(1, command.maxAttempts()));
        job.setNextRunAt(LocalDateTime.now());
        job.setCreatedAt(LocalDateTime.now());
        jobMapper.insert(job);
        log.info("创建后台任务: jobId={}, type={}, resource={}",
                job.getId(), command.jobType(), command.resourceId());
        return job;
    }

    /**
     * 复活死信任务：重置为 QUEUED 并立即调度。
     * <p>条件 UPDATE 只认 DEAD_LETTER 状态——若并发下任务已被其他请求复活
     * （status 已变），本次 UPDATE 影响 0 行，直接放弃，不做二次修改。</p>
     */
    private void reviveDeadLetter(BackgroundJob existing, NewBackgroundJobCommand command) {
        BackgroundJob reset = new BackgroundJob();
        reset.setId(existing.getId());
        reset.setStatus(BackgroundJob.STATUS_QUEUED);
        reset.setAttemptCount(0);
        reset.setMaxAttempts(Math.max(1, command.maxAttempts()));
        reset.setLeaseOwner(null);
        reset.setLeaseUntil(null);
        reset.setNextRunAt(LocalDateTime.now());
        reset.setLastErrorCode(null);
        reset.setLastErrorMessage(null);
        // MyBatis-Plus updateById 默认忽略 null 字段，无法清空错误信息：
        // 用 UpdateWrapper 显式 set 全部字段，条件限定 status=DEAD_LETTER 防并发双复活。
        int updated = jobMapper.update(null,
                new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<BackgroundJob>()
                        .eq("id", existing.getId())
                        .eq("status", BackgroundJob.STATUS_DEAD_LETTER)
                        .set("status", reset.getStatus())
                        .set("attempt_count", reset.getAttemptCount())
                        .set("max_attempts", reset.getMaxAttempts())
                        .set("lease_owner", null)
                        .set("lease_until", null)
                        .set("next_run_at", reset.getNextRunAt())
                        .set("last_error_code", null)
                        .set("last_error_message", null)
                        .set("finished_at", null));
        if (updated == 0) {
            log.info("死信复活竞争失败（已被其他请求处理）: jobId={}", existing.getId());
        }
    }

    /**
     * 领取到期任务（SKIP LOCKED）：候选行加锁后逐条原子认领，认领失败的行跳过。
     *
     * @param workerId   Worker 标识
     * @param batchSize  最多领取条数
     * @param leaseUntil 租约到期时间
     */
    @Transactional
    public List<BackgroundJob> claimDueJobs(String workerId, int batchSize, LocalDateTime leaseUntil) {
        List<BackgroundJob> due = jobMapper.selectDueJobs(LocalDateTime.now(), Math.max(1, batchSize));
        List<BackgroundJob> claimed = new ArrayList<>(due.size());
        for (BackgroundJob candidate : due) {
            if (candidate.getId() == null) {
                continue;
            }
            int updated = jobMapper.claim(candidate.getId(), BackgroundJob.STATUS_QUEUED,
                    BackgroundJob.STATUS_RUNNING, leaseUntil);
            if (updated == 1) {
                jobMapper.updateLease(candidate.getId(), workerId, leaseUntil);
                candidate.setStatus(BackgroundJob.STATUS_RUNNING);
                candidate.setLeaseOwner(workerId);
                candidate.setLeaseUntil(leaseUntil);
                claimed.add(candidate);
            }
        }
        return claimed;
    }

    /** 完成任务：只有当前租约持有者可置 SUCCEEDED。 */
    public void complete(Long jobId, String workerId) {
        jobMapper.complete(jobId, workerId, LocalDateTime.now());
    }

    /**
     * 失败处理：按声明的 {1,5,15} 分钟序列退避；重试次数耗尽进入 DEAD_LETTER。
     * <p>
     * 语义约定：{@code maxAttempts} 是<b>重试次数</b>（不含首次执行），总执行次数 = maxAttempts + 1。
     * claim 时 attempt_count 已预增（首次执行失败时读到 1），因此退避索引用 {@code attempts - 1}：
     * 第一次失败 → 1 分钟、第二次 → 5 分钟、第三次 → 15 分钟、第四次失败（重试耗尽）→ 死信。
     * 历史缺陷：索引曾写作 {@code min(attempts, len-1)} 导致首档 1 分钟不可达、且旧边界
     * {@code > maxAttempts} 让 15 分钟档也走不到——由对抗性代码审查发现，先按设计意图重写
     * 测试（红）再修复（绿），见 {@code BackgroundJobServiceTest#failOrRetryFirstBackoffIsOneMinute}。
     * </p>
     */
    public void failOrRetry(Long jobId, String workerId, JobFailure failure) {
        BackgroundJob job = jobMapper.selectById(jobId);
        if (job == null) {
            return;
        }
        int attempts = job.getAttemptCount() == null ? 1 : job.getAttemptCount();
        int maxAttempts = job.getMaxAttempts() == null ? 3 : job.getMaxAttempts();
        int newAttemptCount = attempts + 1;
        job.setAttemptCount(newAttemptCount);
        job.setLeaseOwner(null);
        job.setLeaseUntil(null);
        job.setLastErrorCode(truncate(failure.errorCode(), 64));
        job.setLastErrorMessage(truncate(failure.errorMessage(), 512));
        if (newAttemptCount > maxAttempts + 1) {
            job.setStatus(BackgroundJob.STATUS_DEAD_LETTER);
            job.setNextRunAt(null);
            log.warn("任务进入死信队列: jobId={}, attempts={}, error={}", jobId, newAttemptCount, failure.errorCode());
        } else {
            int backoffIndex = Math.min(attempts - 1, RETRY_BACKOFF_MINUTES.length - 1);
            job.setStatus(BackgroundJob.STATUS_QUEUED);
            job.setNextRunAt(LocalDateTime.now().plusMinutes(RETRY_BACKOFF_MINUTES[backoffIndex]));
        }
        jobMapper.updateById(job);
    }

    /** 任务负载 JSON（Worker 执行时解析）。 */
    public BackgroundJob getById(Long jobId) {
        return jobMapper.selectById(jobId);
    }

    private static String truncate(String value, int limit) {
        if (value == null || value.length() <= limit) {
            return value;
        }
        return value.substring(0, limit);
    }
}
