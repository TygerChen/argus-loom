package com.argus.rag.common.task;

import com.argus.rag.common.task.entity.EntryLogEntity;
import com.argus.rag.common.task.mapper.EntryLogMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

/**
 * 入口审计存储的 MyBatis-Plus 适配器 —— {@link EntryAuditSink} 的生产实现。
 * <p>把存储方言（MyBatis-Plus 的 mapper 调用）隔离在适配器里：
 * 审计服务与测试都只认识 {@link EntryAuditSink} 端口，未来换存储只换这里。</p>
 */
@Repository
public class EntryAuditSinkMybatis implements EntryAuditSink {

    private static final Logger log = LoggerFactory.getLogger(EntryAuditSinkMybatis.class);

    private final EntryLogMapper entryLogMapper;

    public EntryAuditSinkMybatis(EntryLogMapper entryLogMapper) {
        this.entryLogMapper = entryLogMapper;
    }

    @Override
    public void accept(EntryLogEntity entity) {
        entryLogMapper.insert(entity);
    }
}
