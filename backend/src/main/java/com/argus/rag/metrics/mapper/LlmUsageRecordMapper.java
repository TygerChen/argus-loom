package com.argus.rag.metrics.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.argus.rag.metrics.model.entity.LlmUsageRecordEntity;
import com.argus.rag.metrics.model.vo.MetricsOverviewVO;
import com.argus.rag.metrics.model.vo.UsageRankItemVO;
import com.argus.rag.metrics.model.vo.UsageStatsVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * LLM 用量记录的数据访问层（MyBatis-Plus Mapper）。
 *
 * <p><b>你只需要知道两件事</b>：</p>
 * <ul>
 *   <li>继承 {@link BaseMapper} 后，insert/selectById/updateById/delete 这些
 *       单表基础操作自动就有了，一行 SQL 都不用写（MyBatis-Plus 的核心便利）；</li>
 *   <li>带 {@code @Param} 的方法都是自定义统计查询，SQL 写在对应的 XML 文件里
 *       （resources 下的 mapper XML）——复杂统计（按天分组、按模块排行、Top N）
 *       放 XML 比 Java 注解拼字符串好维护得多。</li>
 * </ul>
 *
 * <p>表结构见 Flyway 迁移脚本中的 {@code llm_usage_records}：
 * 一行 = 一次大模型调用的账目（谁、哪个模块、多少 token、多少钱、耗时、成败）。</p>
 */
@Mapper
public interface LlmUsageRecordMapper extends BaseMapper<LlmUsageRecordEntity> {
    // BaseMapper 提供 insert、selectById 等基础方法
    // 复杂统计查询放在 XML 中

    // 通用统计查询
    UsageStatsVO selectUsageStats(
            @Param("userId") Long userId,
            @Param("groupId") Long groupId,
            @Param("startTime") LocalDateTime startTime
    );

    // 每日趋势
    List<MetricsOverviewVO.DailyStats> selectDailyTrend(
            @Param("startTime") LocalDateTime startTime,
            @Param("module") String module
    );

    // 用户排行
    List<UsageRankItemVO> selectUserRank(
            @Param("startTime") LocalDateTime startTime,
            @Param("limit") int limit
    );

    // 群组排行
    List<UsageRankItemVO> selectGroupRank(
            @Param("startTime") LocalDateTime startTime,
            @Param("limit") int limit
    );
}
