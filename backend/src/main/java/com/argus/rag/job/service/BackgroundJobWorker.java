package com.argus.rag.job.service;

import com.argus.rag.ingestion.service.DocumentIngestionAsyncService;
import com.argus.rag.job.model.entity.BackgroundJob;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.Executor;

/**
 * 后台任务 Worker —— 任务队列的"取件员"（08 篇异步链路的消费端）。
 *
 * <p><b>为什么需要它</b>：HTTP 请求必须秒回（用户上传完就该看到"处理中"而不是
 * 转圈等几分钟），慢活（当前 = 文档解析/向量化；Excel 导出走专用线程池）
 * 写成任务行落库，由本 Worker 在后台按节奏取出来执行。这就是"生产者-消费者"模式，
 * 只不过消息队列换成了 PostgreSQL 表——<b>面试高频题："为什么不用 RabbitMQ/Kafka？"</b>
 * 答案：项目已强依赖 PostgreSQL，任务量级（百级/天）远够用，数据库事务还能让
 * "领取任务"和"业务数据"保持强一致；引入 MQ 是运维成本，等任务量真上来再换，
 * 表结构语义和 MQ 消息几乎一一对应（见 BackgroundJobService），迁移路径是平滑的。</p>
 *
 * <p><b>三个面试级设计点</b>：</p>
 * <ul>
 *   <li><b>fixedDelay 而不是 fixedRate</b>：fixedDelay 是"上一轮跑完后隔 15 秒再跑"，
 *       fixedRate 是"每 15 秒触发一次不管上一轮完没完"。轮询必须用前者——
 *       否则一轮任务执行慢了，下一轮又会叠进来，多批任务同时跑；</li>
 *   <li><b>每次轮询新建 workerId</b>：这个 UUID 短 id 会写进任务的 lease_owner 字段，
 *       是"这个任务归我管"的凭证——complete/failOrRetry 都校验持有者，
 *       防止别的实例误改我认领的任务（租约机制的一部分，见 BackgroundJobService）；</li>
 *   <li><b>轮询失败只 warn 不抛</b>：数据库抖一下导致这一轮没取到任务，
 *       15 秒后下一轮自然恢复。定时任务的兜底原则：<b>这一轮空转不可怕，
 *       可怕的是把调度线程搞死</b>。</li>
 * </ul>
 *
 * <p><b>执行失败怎么办</b>：executeSafely 把一切异常转给 failOrRetry——
 * 执行层故障按退避序列重试（当前实现下实际生效 5/15 分钟两档——claim 预增 attempt_count 使首档 1 分钟不可达），超限进 DEAD_LETTER 死信状态等人工处理
 * （完整的失败语义在 BackgroundJobService）。未知任务类型也走死信而不是默默忽略：
 * "看不懂的任务"本身就是需要人看的异常。</p>
 */
@Component
public class BackgroundJobWorker {

    private static final Logger log = LoggerFactory.getLogger(BackgroundJobWorker.class);

    private final BackgroundJobService jobService;
    private final DocumentIngestionAsyncService ingestionService;
    private final Executor workerExecutor;

    public BackgroundJobWorker(BackgroundJobService jobService,
                               DocumentIngestionAsyncService ingestionService,
                               @Qualifier("taskExecutor") Executor workerExecutor) {
        this.jobService = jobService;
        this.ingestionService = ingestionService;
        this.workerExecutor = workerExecutor;
    }

    /** 每 15 秒轮询一次到期任务；领取与认领在同一短事务内完成（claimDueJobs @Transactional）。 */
    @Scheduled(fixedDelayString = "${argus.job.poll-interval-ms:15000}", initialDelayString = "${argus.job.poll-initial-delay-ms:20000}")
    public void pollAndDispatch() {
        String workerId = "worker-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        try {
            List<BackgroundJob> jobs = jobService.claimDueJobs(
                    workerId, 10, LocalDateTime.now().plusMinutes(5));
            for (BackgroundJob job : jobs) {
                workerExecutor.execute(() -> executeSafely(job, workerId));
            }
        } catch (Exception e) {
            log.warn("后台任务轮询失败: {}", e.getMessage());
        }
    }

    private void executeSafely(BackgroundJob job, String workerId) {
        try {
            switch (job.getJobType() == null ? "" : job.getJobType()) {
                case "INGEST_DOCUMENT" -> {
                    Long documentId = job.getResourceId();
                    Long groupId = readGroupId(job);
                    if (documentId != null && groupId != null) {
                        ingestionService.ingestDocument(documentId, groupId);
                        jobService.complete(job.getId(), workerId);
                    } else {
                        // 坏 payload 是数据问题：静默 complete 会把不可执行的任务伪装成成功，
                        // 死信路径让它可见、可排查（重试耗尽后等人工）。
                        log.warn("任务 payload 缺失关键字段，转失败路径: jobId={}, documentId={}, groupId={}",
                                job.getId(), documentId, groupId);
                        jobService.failOrRetry(job.getId(), workerId,
                                new BackgroundJobService.JobFailure("JOB_PAYLOAD_INVALID",
                                        "documentId=" + documentId + ", groupId=" + groupId
                                                + ", payload=" + job.getPayloadJson()));
                    }
                }
                default -> {
                    log.warn("未知任务类型，转死信: jobId={}, type={}", job.getId(), job.getJobType());
                    jobService.failOrRetry(job.getId(), workerId,
                            new BackgroundJobService.JobFailure("UNKNOWN_JOB_TYPE", "type=" + job.getJobType()));
                }
            }
        } catch (Exception e) {
            log.warn("后台任务执行失败: jobId={}, error={}", job.getId(), e.getMessage());
            jobService.failOrRetry(job.getId(), workerId,
                    new BackgroundJobService.JobFailure("EXECUTION_FAILED", String.valueOf(e.getMessage())));
        }
    }

    /** 从 payload JSON 里取 groupId；解析失败返回 null（由调用方兜底）。 */
    private Long readGroupId(BackgroundJob job) {
        try {
            com.fasterxml.jackson.databind.JsonNode payload =
                    new com.fasterxml.jackson.databind.ObjectMapper().readTree(
                            job.getPayloadJson() == null ? "{}" : job.getPayloadJson());
            return payload.hasNonNull("groupId") ? payload.get("groupId").asLong() : null;
        } catch (Exception e) {
            return null;
        }
    }
}
