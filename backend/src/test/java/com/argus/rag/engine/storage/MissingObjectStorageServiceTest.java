package com.argus.rag.engine.storage;

import com.argus.rag.common.exception.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.io.ByteArrayInputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link MissingObjectStorageService} 降级占位实现契约（纯 JVM，零真实存储/零网络）。
 * 未配置 MinIO 存储时由容器自动注入，bucket 取自配置、缺省回落 "argus-rag-documents"，
 * 且所有读/写/合并/删除操作恒抛 {@link BusinessException}（fail-fast，不静默假成功）。
 */
@DisplayName("对象存储降级（MissingObjectStorageService）")
class MissingObjectStorageServiceTest {

    private static final String NOT_CONFIGURED =
            "对象存储未配置，请在 application.yml 或环境变量中设置 "
            + "storage.minio.endpoint、storage.minio.access-key 和 storage.minio.secret-key";

    // ---------- bucket 解析 ----------

    @Test
    void bucketComesFromEnvironmentProperty() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("storage.minio.bucket", "my-custom-bucket");
        assertEquals("my-custom-bucket", new MissingObjectStorageService(env).getDefaultBucket());
    }

    @Test
    void bucketFallsBackToArgusDefaultWhenUnset() {
        MockEnvironment env = new MockEnvironment(); // 不设属性 → 走默认
        assertEquals("argus-rag-documents", new MissingObjectStorageService(env).getDefaultBucket());
    }

    // ---------- fail-fast：所有操作恒抛 ----------

    @Test
    void getObjectAlwaysThrowsWithConfigTip() {
        MissingObjectStorageService svc = new MissingObjectStorageService(new MockEnvironment());
        BusinessException e = assertThrows(BusinessException.class,
                () -> svc.getObject("b", "k"));
        assertEquals(NOT_CONFIGURED, e.getMessage());
    }

    @Test
    void putObjectAlwaysThrows() {
        MissingObjectStorageService svc = new MissingObjectStorageService(new MockEnvironment());
        BusinessException e = assertThrows(BusinessException.class,
                () -> svc.putObject("b", "k", new ByteArrayInputStream(new byte[]{1}), 1L, "text/plain"));
        assertTrue(e.getMessage().startsWith("对象存储未配置"));
    }

    @Test
    void composeObjectAlwaysThrows() {
        MissingObjectStorageService svc = new MissingObjectStorageService(new MockEnvironment());
        BusinessException e = assertThrows(BusinessException.class,
                () -> svc.composeObject("b", "target", List.of("a", "b"), "application/pdf"));
        assertTrue(e.getMessage().startsWith("对象存储未配置"));
    }

    @Test
    void deleteObjectAlwaysThrows() {
        MissingObjectStorageService svc = new MissingObjectStorageService(new MockEnvironment());
        BusinessException e = assertThrows(BusinessException.class,
                () -> svc.deleteObject("b", "k"));
        assertEquals(NOT_CONFIGURED, e.getMessage());
    }
}