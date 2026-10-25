package com.argus.rag.document.security;

import com.argus.rag.common.enums.SystemRole;
import com.argus.rag.common.security.UserContext;
import com.argus.rag.document.mapper.DocumentMapper;
import com.argus.rag.document.model.entity.DocumentEntity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * DocumentRoleGate 单元测试：角色优先级、fail-closed 缺省、canRead 判定矩阵。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("文档角色门槛（单一授权点）")
class DocumentRoleGateTest {

    @Mock
    private DocumentMapper documentMapper;

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    private DocumentRoleGate gate() {
        return new DocumentRoleGate(documentMapper);
    }

    private DocumentEntity doc(Long id, String requiredRole) {
        DocumentEntity d = new DocumentEntity();
        d.setId(id);
        d.setRequiredRole(requiredRole);
        return d;
    }

    @Test
    @DisplayName("角色优先级：USER<FINANCE<ADMIN 单向叠加覆盖")
    void precedence() {
        assertThat(gate().allowedRoleNames(SystemRole.USER)).containsExactly("USER");
        assertThat(gate().allowedRoleNames(SystemRole.FINANCE)).containsExactly("USER", "FINANCE");
        assertThat(gate().allowedRoleNames(SystemRole.ADMIN)).containsExactly("USER", "FINANCE", "ADMIN");
    }

    @Test
    @DisplayName("fail-closed：角色缺失时按最低权限 USER 处理")
    void precedenceNull() {
        assertThat(gate().allowedRoleNames(null)).containsExactly("USER");
    }

    @Test
    @DisplayName("canRead 判定矩阵：未达门槛一律拒（角色提升后放行）")
    void canReadMatrix() {
        DocumentRoleGate g = gate();
        assertThat(g.canRead(SystemRole.USER, "USER")).isTrue();
        assertThat(g.canRead(SystemRole.USER, "FINANCE")).isFalse();
        assertThat(g.canRead(SystemRole.USER, "ADMIN")).isFalse();
        assertThat(g.canRead(SystemRole.FINANCE, "FINANCE")).isTrue();
        assertThat(g.canRead(SystemRole.FINANCE, "ADMIN")).isFalse();
        assertThat(g.canRead(SystemRole.ADMIN, "ADMIN")).isTrue();
        assertThat(g.canRead(SystemRole.USER, null)).isTrue();       // 空门槛 = 公开
        assertThat(g.canRead(null, "FINANCE")).isFalse();            // 角色缺失 = 拒绝
    }

    @Test
    @DisplayName("角色缺失/未认证的 currentRole 为 USER（fail-closed，不做乐观放行）")
    void currentRoleFailClosed() {
        UserContext.set(null);
        assertThat(gate().currentRole()).isEqualTo(SystemRole.USER);
    }

    @Test
    @DisplayName("非法群组返回空集（不抛错、无可检索）")
    void invalidGroup() {
        assertThat(gate().allowedDocumentIds(null, SystemRole.USER)).isEmpty();
        assertThat(gate().allowedDocumentIds(-1L, SystemRole.USER)).isEmpty();
    }

    @Test
    @DisplayName("allowedDocumentIds 正确收拢为可读文档 ID 集合")
    void mapsAllowedIds() {
        when(documentMapper.selectList(any())).thenReturn(List.of(doc(10L, "USER"), doc(11L, "USER")));
        assertThat(gate().allowedDocumentIds(5L, SystemRole.USER))
                .containsExactlyInAnyOrder(10L, 11L);
    }

    @Test
    @DisplayName("空库（无可读）→ 空权限集，绝不乐观放行")
    void emptyAllowed() {
        when(documentMapper.selectList(any())).thenReturn(List.of());
        assertThat(gate().allowedDocumentIds(5L, SystemRole.USER)).isEmpty();
    }
}