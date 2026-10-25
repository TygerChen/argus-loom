package com.argus.rag.document.storage;

import com.argus.rag.engine.storage.ObjectStorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.UUID;

/**
 * 文档对象存储专职组件（隔离上传/文档对底层 {@code ObjectStorageService} 的细节）。
 *
 * <p>把「分片 key 构造、final/direct key 构造、分片写入、合并、对象删除补偿」从
 * {@code DocumentUploadService} 中拆出，服务侧只关心业务编排，存储的形状/路径规则收口在此。</p>
 */
@Component
public class DocumentFileStore {

    private static final Logger log = LoggerFactory.getLogger(DocumentFileStore.class);

    private static final String OCTET_STREAM = "application/octet-stream";

    private final ObjectStorageService storage;

    public DocumentFileStore(ObjectStorageService storage) {
        this.storage = storage;
    }

    public String defaultBucket() {
        return storage.getDefaultBucket();
    }

    /** 分片对象 key：uploads/{groupId}/{uploadId}/chunks/{chunkIndex} */
    public String chunkKey(Long groupId, String uploadId, int chunkIndex) {
        return "uploads/%d/%s/chunks/%d".formatted(groupId, uploadId, chunkIndex);
    }

    /** 合并后最终文档 key：groups/{groupId}/users/{userId}/{uuid}.{ext} */
    public String finalObjectKey(Long groupId, Long userId, String fileExt) {
        String fileId = UUID.randomUUID().toString().replace("-", "");
        return "groups/%d/users/%d/%s.%s".formatted(groupId, userId, fileId, fileExt);
    }

    /** 直接上传（小文件）对象 key，与 {@link #finalObjectKey} 同形。 */
    public String directObjectKey(Long groupId, Long userId, String fileExt) {
        String fileId = UUID.randomUUID().toString().replace("-", "");
        return "groups/%d/users/%d/%s.%s".formatted(groupId, userId, fileId, fileExt);
    }

    /** 写入单个分片对象。 */
    public void putChunk(String bucket, String key, byte[] chunkData) {
        storage.putObject(bucket, key, new ByteArrayInputStream(chunkData), chunkData.length, OCTET_STREAM);
    }

    /** 写入直接上传（或分片合计）的完整文件对象。 */
    public void putDirect(String bucket, String key, InputStream in, long size, String contentType) {
        storage.putObject(bucket, key, in, size, contentType);
    }

    /** 合并多个分片对象为单一对象。 */
    public void compose(String bucket, String finalKey, List<String> chunkKeys, String contentType) {
        storage.composeObject(bucket, finalKey, chunkKeys, contentType);
    }

    /** 删除对象（补偿清理用），失败静默。 */
    public void deleteQuietly(String bucket, String key) {
        try {
            storage.deleteObject(bucket, key);
        } catch (RuntimeException e) {
            log.warn("对象存储补偿删除失败: bucket={}, key={}", bucket, key, e);
        }
    }
}