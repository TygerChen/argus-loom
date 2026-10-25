package com.argus.rag.qa.route;

import com.argus.rag.document.mapper.DocumentMapper;
import com.argus.rag.group.service.GroupMembershipService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link KnowledgeGroupRouter} 轻量路由契约 —— 容器外可锁的守卫支路。
 * 注意：route() 的完整计分支路会构造 {@code LambdaQueryWrapper<DocumentEntity>()}（MP 纯 JVM
 * 构造即抛），故本测试只覆盖在触达该 wrapper 前就能短路的分支，
 * 且断言这些分支绝不触碰 documentMapper：
 * <ul>
 *   <li>enabled=false → 恒空，且不查可见组、不碰 mapper；</li>
 *   <li>可见组为空 → 空，不碰 mapper；</li>
 *   <li>显式候选全部被过滤掉（非法/不可见）→ 空，不碰 mapper。</li>
 * </ul>
 */
@DisplayName("知识库轻量路由（KnowledgeGroupRouter）守卫")
class KnowledgeGroupRouterTest {

    private final GroupMembershipService membership = mock(GroupMembershipService.class);
    private final DocumentMapper documentMapper = mock(DocumentMapper.class);

    private KnowledgeGroupRouter router(boolean enabled) {
        return new KnowledgeGroupRouter(membership, documentMapper, enabled, 3, 100);
    }

    private GroupMembershipService.VisibleGroup group(Long id, String name) {
        return new GroupMembershipService.VisibleGroup(id, "CODE" + id, name, "-", 0L, "2026-01-01");
    }

    @Test
    void disabledRoutesEmptyWithoutTouchingAnything() {
        KnowledgeGroupRouter r = router(false);
        List<KnowledgeGroupRouter.RoutedKnowledgeGroup> out = r.route(1L, "报销 模板 怎么填", null);
        assertTrue(out.isEmpty());
        verify(membership, never()).listVisibleGroupsForUser(anyLong());
        verify(documentMapper, never()).selectList(any());
    }

    @Test
    void disabledIgnoresExplicitCandidates() {
        KnowledgeGroupRouter r = router(false);
        List<KnowledgeGroupRouter.RoutedKnowledgeGroup> out = r.route(1L, "查询", List.of(7L, 8L));
        assertTrue(out.isEmpty());
        verify(membership, never()).listVisibleGroupsForUser(anyLong());
        verify(documentMapper, never()).selectList(any());
    }

    @Test
    void enabledWithNoVisibleGroupsReturnsEmptyWithoutQueryingDocs() {
        when(membership.listVisibleGroupsForUser(1L)).thenReturn(List.of());
        KnowledgeGroupRouter r = router(true);
        List<KnowledgeGroupRouter.RoutedKnowledgeGroup> out = r.route(1L, "报销", null);
        assertTrue(out.isEmpty());
        verify(documentMapper, never()).selectList(any());
    }

    @Test
    void enabledFiltersOutCandidatesThatAreNotVisible() {
        when(membership.listVisibleGroupsForUser(1L))
                .thenReturn(List.of(group(42L, "财务制度")));
        KnowledgeGroupRouter r = router(true);
        // 唯一显式候选 999 不在可见列表 → 被过滤 → 无候选 → 空，且不碰 mapper
        List<KnowledgeGroupRouter.RoutedKnowledgeGroup> out = r.route(1L, "报销", List.of(999L));
        assertTrue(out.isEmpty());
        verify(documentMapper, never()).selectList(any());
    }

    @Test
    void disabledWidgetWildcardCoverage() {
        KnowledgeGroupRouter r = router(false);
        assertEquals(0, r.route(null, null, null).size());
    }
}