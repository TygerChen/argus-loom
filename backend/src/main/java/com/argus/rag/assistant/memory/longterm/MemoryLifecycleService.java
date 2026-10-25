package com.argus.rag.assistant.memory.longterm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 长期记忆生命周期管理服务。
 *
 * <p>理论依据：《记忆系统设计》第 3.8 节 — 记忆生命周期，
 * 以及第 5.5 节 — 主题冲突处理。</p>
 *
 * <p>管理记忆的完整生命周期：候选 → 确认 → 活跃 → 替代/过期/撤销/删除。</p>
 */
@Service
public class MemoryLifecycleService {

    private static final Logger log = LoggerFactory.getLogger(MemoryLifecycleService.class);

    private final UserMemoryMapper userMemoryMapper;

    public MemoryLifecycleService(UserMemoryMapper userMemoryMapper) {
        this.userMemoryMapper = userMemoryMapper;
    }

    /**
     * 创建一条新的活跃记忆。
     * 调用前应已通过 {@link MemoryPolicy} 评估。
     *
     * @param entity 已填充基本字段的记忆实体
     * @return 插入后的记忆实体（含自增 ID）
     */
    @Transactional
    public UserMemoryEntity create(UserMemoryEntity entity) {
        if (entity.getStatus() == null) {
            entity.setStatus(MemoryStatus.ACTIVE.name());
        }
        if (entity.getConfidence() == null) {
            entity.setConfidence(new BigDecimal("0.50"));
        }
        if (entity.getImportance() == null) {
            entity.setImportance(new BigDecimal("0.50"));
        }
        LocalDateTime now = LocalDateTime.now();
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);

        int affected = userMemoryMapper.insert(entity);
        if (affected != 1 || entity.getId() == null) {
            throw new IllegalStateException("记忆写入失败");
        }

        log.debug("长期记忆已创建: id={}, userId={}, tenantId={}, type={}, topic={}",
                entity.getId(), entity.getUserId(), entity.getTenantId(),
                entity.getMemoryType(), entity.getTopic());
        return entity;
    }

    /**
     * 创建候选记忆（MODEL_INFERENCE 来源，需用户确认）。
     *
     * @return 创建后的候选记忆实体
     */
    @Transactional
    public UserMemoryEntity createCandidate(UserMemoryEntity entity) {
        entity.setStatus(MemoryStatus.CANDIDATE.name());
        return create(entity);
    }

    /**
     * 用户确认候选记忆，将其状态转为 ACTIVE。
     *
     * @param memoryId 候选记忆 ID
     * @return 更新后的记忆实体
     */
    @Transactional
    public UserMemoryEntity confirm(Long memoryId) {
        UserMemoryEntity entity = requireEntity(memoryId);
        if (!MemoryStatus.CANDIDATE.name().equals(entity.getStatus())
                && !MemoryStatus.PENDING_CONFIRMATION.name().equals(entity.getStatus())) {
            throw new IllegalStateException("只能确认候选状态的记忆");
        }
        entity.setStatus(MemoryStatus.ACTIVE.name());
        entity.setConfidence(new BigDecimal("0.98"));
        entity.setUpdatedAt(LocalDateTime.now());
        userMemoryMapper.update(entity);
        return entity;
    }

    /**
     * 用户撤销记忆（REVOKED）。
     */
    @Transactional
    public void revoke(Long memoryId) {
        UserMemoryEntity entity = requireEntity(memoryId);
        entity.setStatus(MemoryStatus.REVOKED.name());
        entity.setUpdatedAt(LocalDateTime.now());
        userMemoryMapper.update(entity);
    }

    /**
     * 替换同主题的旧记忆。
     *
     * <p>当同 (user_id, tenant_id, topic) 存在 ACTIVE 旧记忆时：
     * 1. 将旧记忆标记为 SUPERSEDED
     * 2. 旧记忆的 supersededBy 指向新记忆
     * 3. 创建新记忆为 ACTIVE</p>
     *
     * @param newEntity 新的记忆实体
     * @return 创建后的新记忆实体
     */
    @Transactional
    public UserMemoryEntity upsertWithSupersede(UserMemoryEntity newEntity) {
        String topic = newEntity.getTopic();
        if (topic != null && !topic.isBlank()) {
            UserMemoryEntity existing = userMemoryMapper.selectByTopic(
                    newEntity.getUserId(),
                    newEntity.getTenantId(),
                    topic,
                    MemoryStatus.ACTIVE.name()
            );
            if (existing != null) {
                existing.setStatus(MemoryStatus.SUPERSEDED.name());
                existing.setUpdatedAt(LocalDateTime.now());
                userMemoryMapper.update(existing);

                // 等新记忆插入后再设置 supersededBy
                UserMemoryEntity created = create(newEntity);
                existing.setSupersededBy(created.getId());
                userMemoryMapper.update(existing);

                log.debug("长期记忆已替换: oldId={}, newId={}, topic={}",
                        existing.getId(), created.getId(), topic);
                return created;
            }
        }

        return create(newEntity);
    }

    /**
     * 将已过期的记忆标记为 EXPIRED。
     * 建议通过定时任务调用。
     */
    @Transactional
    public int expireOutdated() {
        return userMemoryMapper.expireOutdated();
    }

    /**
     * 逻辑删除（标记为 DELETED）。
     */
    @Transactional
    public void delete(Long memoryId) {
        UserMemoryEntity entity = requireEntity(memoryId);
        entity.setStatus(MemoryStatus.DELETED.name());
        entity.setUpdatedAt(LocalDateTime.now());
        userMemoryMapper.update(entity);
    }

    private UserMemoryEntity requireEntity(Long id) {
        UserMemoryEntity entity = userMemoryMapper.selectById(id);
        if (entity == null) {
            throw new IllegalArgumentException("记忆不存在: " + id);
        }
        return entity;
    }
}