package com.argus.rag.job.mapper;

import com.argus.rag.job.model.entity.BackgroundJob;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 可靠后台任务 Mapper。
 * <p>领取 SQL 使用 FOR UPDATE SKIP LOCKED（见 mappers/job/BackgroundJobMapper.xml），
 * 保证多 Worker 并发领取时一个任务只被一个 Worker 拿到。</p>
 */
@Mapper
public interface BackgroundJobMapper extends BaseMapper<BackgroundJob> {

    /** 按幂等键查任务（幂等入队）。 */
    BackgroundJob selectByIdempotencyKey(@Param("idempotencyKey") String idempotencyKey);

    /** 查询到期可执行任务：QUEUED 且 next_run_at 已到，或 RUNNING 且租约已过期。 */
    List<BackgroundJob> selectDueJobs(@Param("now") LocalDateTime now, @Param("limit") int limit);

    /** 原子认领：状态置 RUNNING、推进尝试次数。返回 0 表示已被其他 Worker 抢先；租约由服务层回写。 */
    int claim(@Param("id") Long id, @Param("fromStatus") String fromStatus,
              @Param("toStatus") String toStatus, @Param("leaseUntil") LocalDateTime leaseUntil);

    /** 完成：校验租约持有者后置 SUCCEEDED。 */
    int complete(@Param("id") Long id, @Param("workerId") String workerId,
                 @Param("finishedAt") LocalDateTime finishedAt);

    /** 领取后回写租约信息（服务层编排用）。 */
    int updateLease(@Param("id") Long id, @Param("leaseOwner") String leaseOwner,
                    @Param("leaseUntil") LocalDateTime leaseUntil);
}
