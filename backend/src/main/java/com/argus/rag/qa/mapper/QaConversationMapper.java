package com.argus.rag.qa.mapper;

import com.argus.rag.qa.model.entity.QaConversationEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * QA 多轮会话 Mapper。
 * <p>基础 CRUD 由 MyBatis-Plus 提供；摘要合并使用带乐观锁的显式 SQL，
 * 防止异步任务并发覆盖彼此的滚动摘要。</p>
 */
@Mapper
public interface QaConversationMapper extends BaseMapper<QaConversationEntity> {

    /**
     * 乐观锁更新滚动摘要。
     * <p>仅当 summary_version 与调用方读取到的版本一致时才写入（CAS），
     * 同时推进 summary_upto_record_id 游标并刷新更新时间；
     * 影响行数为 0 表示已被并发合并先行推进，调用方应放弃本次结果。</p>
     *
     * @param conversationId       会话 ID
     * @param expectedVersion      期望的摘要版本号（读取时所见）
     * @param newSummary           新摘要正文
     * @param summaryUptoRecordId  摘要已覆盖到的 qa_records 位置
     * @return 影响行数（1 = 成功；0 = 版本冲突）
     */
    @Update("UPDATE qa_conversations "
            + "SET summary = #{newSummary}, "
            + "summary_version = summary_version + 1, "
            + "summary_upto_record_id = #{summaryUptoRecordId}, "
            + "updated_at = NOW() "
            + "WHERE id = #{conversationId} AND summary_version = #{expectedVersion}")
    int updateSummaryWithVersion(
            @Param("conversationId") Long conversationId,
            @Param("expectedVersion") int expectedVersion,
            @Param("newSummary") String newSummary,
            @Param("summaryUptoRecordId") Long summaryUptoRecordId
    );

    /**
     * 会话延续：刷新 last_question_at 时间戳（仅归属用户可触发）。
     *
     * @param conversationId 会话 ID
     * @param userId         当前提问用户（必须与会话归属一致）
     * @return 影响行数
     */
    @Update("UPDATE qa_conversations "
            + "SET last_question_at = NOW(), updated_at = NOW() "
            + "WHERE id = #{conversationId} AND user_id = #{userId}")
    int touchLastQuestion(
            @Param("conversationId") Long conversationId,
            @Param("userId") Long userId
    );
}
