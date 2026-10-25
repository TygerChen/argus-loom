package com.argus.rag.assistant.memory.longterm;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 用户长期记忆数据访问接口。
 *
 * <p>所有查询都必须携带 userId 和 tenantId，不可先检索后过滤。</p>
 */
@Mapper
public interface UserMemoryMapper {

    /**
     * 插入一条新的记忆记录。
     *
     * @param entity 记忆实体，不能为 null
     * @return 受影响的行数
     */
    int insert(UserMemoryEntity entity);

    /**
     * 按 ID 查询记忆。
     *
     * @param id 主键
     * @return 记忆实体，不存在返回 null
     */
    UserMemoryEntity selectById(@Param("id") Long id);

    /**
     * 按用户+租户+主题+状态查询当前有效的记忆。
     * 用于同主题冲突检测和替换。
     *
     * @param userId   用户 ID
     * @param tenantId 租户 ID
     * @param topic    主题 Key
     * @param status   状态（通常为 ACTIVE）
     * @return 匹配的记忆实体，不存在返回 null
     */
    UserMemoryEntity selectByTopic(
            @Param("userId") Long userId,
            @Param("tenantId") Long tenantId,
            @Param("topic") String topic,
            @Param("status") String status
    );

    /**
     * 按用户+租户查询所有活跃记忆，用于内存中的检索排序。
     * 仅用于数据量较小时的简单场景，大规模数据建议使用向量检索。
     *
     * @param userId   用户 ID
     * @param tenantId 租户 ID
     * @return 活跃记忆列表，按更新时间降序
     */
    List<UserMemoryEntity> selectActiveByUser(
            @Param("userId") Long userId,
            @Param("tenantId") Long tenantId
    );

    /**
     * 更新记忆的状态字段（乐观锁辅助）。
     *
     * @param entity 待更新的记忆实体
     * @return 受影响的行数
     */
    int update(UserMemoryEntity entity);

    /**
     * 将已过期的记忆批量标记为 EXPIRED。
     *
     * @return 受影响的行数
     */
    int expireOutdated();

    /**
     * 按用户+租户+状态统计记忆数量。
     */
    Long countByUserTenantStatus(
            @Param("userId") Long userId,
            @Param("tenantId") Long tenantId,
            @Param("status") String status
    );
}