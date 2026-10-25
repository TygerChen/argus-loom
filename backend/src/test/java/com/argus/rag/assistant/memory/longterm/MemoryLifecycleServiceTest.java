package com.argus.rag.assistant.memory.longterm;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link MemoryLifecycleService} 长期记忆生命周期契约（mock UserMemoryMapper，零 DB，纯 JVM）：
 * - create：空 status/confidence/importance 填默认 ACTIVE/0.50/0.50，设 createdAt/updatedAt，
 *   成功返回含 id 实体；insert 非 1 或 id 仍为 null→IllegalStateException「记忆写入失败」；
 * - createCandidate：强制 status=CANDIDATE 后走 create；
 * - confirm：缺实体→IllegalArgumentException「记忆不存在」、非候选/待确认→「只能确认候选状态的记忆」、
 *   合法→ACTIVE + confidence 0.98 + update；
 * - revoke → REVOKED / delete → DELETED（均先 requireEntity + update）；
 * - upsertWithSupersede：主题非空且存在同主题 ACTIVE → 旧 SUPERSEDED + 新 ACTIVE + supersededBy 指新 +
 *   二次 update；主题空白或无旧记忆 → 仅 create；
 * - expireOutdated：委托 mapper.expireOutdated() 并原样返回。
 */
@DisplayName("长期记忆生命周期（MemoryLifecycleService）")
class MemoryLifecycleServiceTest {

    private UserMemoryMapper mapper;
    private MemoryLifecycleService svc;

    @BeforeEach
    void setUp() {
        mapper = mock(UserMemoryMapper.class);
        svc = new MemoryLifecycleService(mapper);
    }

    // ---------- helpers ----------

    private static UserMemoryEntity mem(Long id, String status) {
        UserMemoryEntity e = new UserMemoryEntity();
        e.setId(id);
        e.setUserId(7L);
        e.setTenantId(1L);
        e.setTopic("报价偏好");
        e.setStatus(status);
        return e;
    }

    // ---------- create ----------

    @Test
    void createAppliesDefaultsAndPersists() {
        when(mapper.insert(any(UserMemoryEntity.class))).thenReturn(1);
        UserMemoryEntity input = mem(42L, null); // status 空 → 默认
        UserMemoryEntity created = svc.create(input);
        assertSame(input, created);
        assertEquals(MemoryStatus.ACTIVE.name(), created.getStatus());
        assertEquals(new BigDecimal("0.50"), created.getConfidence());
        assertEquals(new BigDecimal("0.50"), created.getImportance());
        assertNotNull(created.getCreatedAt());
        assertNotNull(created.getUpdatedAt());
        assertEquals(42L, created.getId());
        verify(mapper).insert(input);
    }

    @Test
    void createKeepsProvidedStatus() {
        when(mapper.insert(any(UserMemoryEntity.class))).thenReturn(1);
        UserMemoryEntity target = mem(42L, MemoryStatus.CANDIDATE.name());
        svc.create(target);
        assertEquals(MemoryStatus.CANDIDATE.name(), target.getStatus());
    }

    @Test
    void createInsertZeroRowsThrows() {
        when(mapper.insert(any(UserMemoryEntity.class))).thenReturn(0);
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> svc.create(mem(1L, null)));
        assertEquals("记忆写入失败", e.getMessage());
    }

    @Test
    void createInsertedWithoutBackfilledIdThrows() {
        when(mapper.insert(any(UserMemoryEntity.class))).thenReturn(1);
        // id 为 null（模拟 DB 未回填）→ 视为失败
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> svc.create(mem(null, "ACTIVE")));
        assertEquals("记忆写入失败", e.getMessage());
    }

    @Test
    void createCandidateForcesCandidateStatus() {
        when(mapper.insert(any(UserMemoryEntity.class))).thenReturn(1);
        UserMemoryEntity target = mem(7L, "PENDING_CONFIRMATION");
        svc.createCandidate(target);
        assertEquals(MemoryStatus.CANDIDATE.name(), target.getStatus());
    }

    // ---------- confirm ----------

    @Test
    void confirmPromotesCandidateToActiveWithHighConfidence() {
        UserMemoryEntity existing = mem(5L, MemoryStatus.CANDIDATE.name());
        when(mapper.selectById(5L)).thenReturn(existing);
        UserMemoryEntity confirmed = svc.confirm(5L);
        assertSame(existing, confirmed);
        assertEquals(MemoryStatus.ACTIVE.name(), confirmed.getStatus());
        assertEquals(new BigDecimal("0.98"), confirmed.getConfidence());
        assertNotNull(confirmed.getUpdatedAt());
        verify(mapper).update(existing);
    }

    @Test
    void confirmAcceptsPendingConfirmation() {
        UserMemoryEntity existing = mem(6L, MemoryStatus.PENDING_CONFIRMATION.name());
        when(mapper.selectById(6L)).thenReturn(existing);
        svc.confirm(6L);
        assertEquals(MemoryStatus.ACTIVE.name(), existing.getStatus());
        verify(mapper).update(existing);
    }

    @Test
    void confirmRejectsActiveMemory() {
        UserMemoryEntity existing = mem(8L, MemoryStatus.ACTIVE.name());
        when(mapper.selectById(8L)).thenReturn(existing);
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> svc.confirm(8L));
        assertEquals("只能确认候选状态的记忆", e.getMessage());
        verify(mapper, never()).update(any());
    }

    @Test
    void confirmRejectsMissingEntity() {
        when(mapper.selectById(9L)).thenReturn(null);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> svc.confirm(9L));
        assertEquals("记忆不存在: 9", e.getMessage());
    }

    // ---------- revoke / delete ----------

    @Test
    void revokeMarksRevoked() {
        UserMemoryEntity existing = mem(3L, MemoryStatus.ACTIVE.name());
        when(mapper.selectById(3L)).thenReturn(existing);
        svc.revoke(3L);
        assertEquals(MemoryStatus.REVOKED.name(), existing.getStatus());
        assertNotNull(existing.getUpdatedAt());
        verify(mapper).update(existing);
    }

    @Test
    void revokeMissingThrows() {
        when(mapper.selectById(4L)).thenReturn(null);
        assertThrows(IllegalArgumentException.class, () -> svc.revoke(4L));
    }

    @Test
    void deleteMarksDeleted() {
        UserMemoryEntity existing = mem(1L, MemoryStatus.ACTIVE.name());
        when(mapper.selectById(1L)).thenReturn(existing);
        svc.delete(1L);
        assertEquals(MemoryStatus.DELETED.name(), existing.getStatus());
        assertNotNull(existing.getUpdatedAt());
        verify(mapper).update(existing);
    }

    // ---------- upsertWithSupersede ----------

    @Test
    void upsertSupersedesExistingActiveWhenTopicMatches() {
        UserMemoryEntity oldMem = mem(10L, MemoryStatus.ACTIVE.name());
        when(mapper.selectByTopic(7L, 1L, "报价偏好", MemoryStatus.ACTIVE.name())).thenReturn(oldMem);
        when(mapper.insert(any(UserMemoryEntity.class))).thenReturn(1);

        UserMemoryEntity newMem = mem(11L, null); // status 将默认 ACTIVE
        UserMemoryEntity created = svc.upsertWithSupersede(newMem);

        assertSame(newMem, created);
        assertEquals(MemoryStatus.ACTIVE.name(), created.getStatus());
        assertEquals(MemoryStatus.SUPERSEDED.name(), oldMem.getStatus());
        assertEquals(11L, oldMem.getSupersededBy());
        // 旧记忆被更新两次（标记 SUPERSEDED + 回写 supersededBy）
        verify(mapper, times(2)).update(oldMem);
        verify(mapper, times(1)).insert(newMem);
    }

    @Test
    void upsertNoExistingCreatesPlain() {
        when(mapper.selectByTopic(anyLong(), anyLong(), anyString(), anyString())).thenReturn(null);
        when(mapper.insert(any(UserMemoryEntity.class))).thenReturn(1);
        UserMemoryEntity newMem = mem(21L, "ACTIVE");
        UserMemoryEntity created = svc.upsertWithSupersede(newMem);
        assertSame(newMem, created);
        verify(mapper, never()).update(any());
    }

    @Test
    void upsertBlankTopicSkipsSelection() {
        when(mapper.insert(any(UserMemoryEntity.class))).thenReturn(1);
        UserMemoryEntity newMem = mem(1L, "ACTIVE");
        newMem.setTopic("   ");
        svc.upsertWithSupersede(newMem);
        verify(mapper, never()).selectByTopic(anyLong(), anyLong(), anyString(), anyString());
        verify(mapper, times(1)).insert(newMem);
    }

    // ---------- expireOutdated ----------

    @Test
    void expireOutdatedDelegatesAndReturnsCount() {
        when(mapper.expireOutdated()).thenReturn(5);
        assertEquals(5, svc.expireOutdated());
        verify(mapper).expireOutdated();
    }
}