package com.argus.rag.job.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 可靠后台任务（background_job 表）。
 * <p>文档入库、Excel 导出、财务导入等可靠异步工作统一入队：
 * Worker 用 FOR UPDATE SKIP LOCKED 批量领取，租约保护下执行，崩溃后由其他 Worker 接管。</p>
 */
@Data
@TableName("background_job")
public class BackgroundJob {

    public static final String STATUS_QUEUED = "QUEUED";
    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_SUCCEEDED = "SUCCEEDED";
    public static final String STATUS_DEAD_LETTER = "DEAD_LETTER";

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 任务类型：INGEST_DOCUMENT / EXCEL_EXPORT / FINANCE_IMPORT 等。 */
    private String jobType;
    /** 资源类型：DOCUMENT / EXCEL_DOCUMENT / FINANCE_BATCH 等。 */
    private String resourceType;
    /** 资源 ID。 */
    private Long resourceId;
    /** 幂等键（唯一索引）：同键入队返回既有任务。 */
    private String idempotencyKey;
    /** QUEUED / RUNNING / SUCCEEDED / DEAD_LETTER。 */
    private String status;
    /** 任务负载 JSON（业务参数，不放大对象）。 */
    private String payloadJson;
    /** 已尝试次数（含当前执行）。 */
    private Integer attemptCount;
    /** 最大尝试次数，达到后进入 DEAD_LETTER。 */
    private Integer maxAttempts;
    /** 当前租约持有者（worker 标识）。 */
    private String leaseOwner;
    /** 租约到期时间；到期后可被其他 Worker 接管。 */
    private LocalDateTime leaseUntil;
    /** 下次可执行时间（退避调度）。 */
    private LocalDateTime nextRunAt;
    /** 最近一次失败错误码。 */
    private String lastErrorCode;
    /** 最近一次失败详情。 */
    private String lastErrorMessage;
    private LocalDateTime createdAt;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
}
