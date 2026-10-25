package com.argus.rag.assistant.mapper;

import com.argus.rag.assistant.model.entity.AssistantSessionEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 助手会话数据访问接口。
 * <p>提供会话实体的 CRUD 操作，包括创建、查询、更新标题和最后消息时间、删除等。</p>
 */
@Mapper
public interface AssistantSessionMapper {

    /**
     * 插入一条新的会话记录
     *
     * @param assistantSessionEntity 会话实体，不能为 null
     * @return 受影响的行数，成功应返回 1
     */
    int insert(AssistantSessionEntity assistantSessionEntity);

    /**
     * 查询指定用户的所有会话，按最后消息时间降序排列
     * <p>SQL 在 {@code AssistantSessionMapper.xml} 中定义（filter 掉 DELETED），此处仅声明签名。</p>
     *
     * @param userId          用户ID，不能为空
     * @param includeArchived 是否包含已归档的会话
     * @return 会话实体列表，最近活跃的会话在前
     */
    List<AssistantSessionEntity> selectByUserIdOrderByLastMessageAtDesc(
            @Param("userId") Long userId,
            @Param("includeArchived") boolean includeArchived
    );

    /**
     * 根据会话ID和用户ID查询单个会话，同时校验会话归属
     *
     * @param sessionId 会话ID，不能为空
     * @param userId    用户ID，不能为空
     * @return 会话实体，若不存在或不属于该用户返回 null
     */
    AssistantSessionEntity selectByIdAndUserId(
            @Param("sessionId") Long sessionId,
            @Param("userId") Long userId
    );

    /**
     * 更新会话标题
     *
     * @param sessionId 会话ID，不能为空
     * @param userId    用户ID，校验会话归属
     * @param title     新标题，不能为空
     * @param updatedAt 更新时间
     * @return 受影响的行数，成功应返回 1
     */
    int updateTitle(
            @Param("sessionId") Long sessionId,
            @Param("userId") Long userId,
            @Param("title") String title,
            @Param("updatedAt") java.time.LocalDateTime updatedAt
    );

    /**
     * 更新会话的最后消息时间
     *
     * @param sessionId     会话ID，不能为空
     * @param userId        用户ID，校验会话归属
     * @param lastMessageAt 最后消息时间
     * @return 受影响的行数，成功应返回 1
     */
    int updateLastMessageAt(
            @Param("sessionId") Long sessionId,
            @Param("userId") Long userId,
            @Param("lastMessageAt") java.time.LocalDateTime lastMessageAt
    );

    /**
     * 根据会话ID和用户ID删除会话（逻辑或物理删除）
     *
     * @param sessionId 会话ID，不能为空
     * @param userId    用户ID，校验删除权限
     * @return 被删除的记录数
     */
    int deleteByIdAndUserId(
            @Param("sessionId") Long sessionId,
            @Param("userId") Long userId
    );

    /**
     * 更新会话归档状态
     *
     * @param id       会话ID，不能为空
     * @param archived 归档状态：true 表示已归档，false 表示取消归档
     * @return 受影响的行数，成功应返回 1
     */
    @Update("UPDATE assistant_sessions SET archived = #{archived} WHERE id = #{id}")
    int updateArchived(@Param("id") Long id, @Param("archived") Boolean archived);
}
