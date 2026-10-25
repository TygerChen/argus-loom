package com.argus.rag.document.model.vo;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link UploadInitResponse} 上传初始化响应 VO 契约（纯 JVM/零 mock）。
 * 三种初始化结果形状：instant 秒传（true+documentId，其余空/空列表）；uploadSession 新建
 * （false+uploadId+分片参数，已上传分片空）；uploadSession 断点续传（false+uploadId+已上传分
 * 片列表，其余分片参数）。record 不解耦 uploadedChunks（引用保留）。
 */
@DisplayName("上传初始化响应（UploadInitResponse）")
class UploadInitResponseTest {

    @Test
    void instantCarriesDocumentIdAndIsInstantOnly() {
        UploadInitResponse r = UploadInitResponse.instant(42L);
        assertTrue(r.instantUpload());
        assertEquals(42L, r.documentId());
        assertNull(r.uploadId());
        assertTrue(r.uploadedChunks().isEmpty());
        assertNull(r.chunkSize());
        assertNull(r.chunkCount());
    }

    @Test
    void newSessionCarriesUploadParamsAndEmptyChunks() {
        UploadInitResponse r = UploadInitResponse.uploadSession("uuid-1", 102400L, 5);
        assertFalse(r.instantUpload());
        assertNull(r.documentId());
        assertEquals("uuid-1", r.uploadId());
        assertTrue(r.uploadedChunks().isEmpty());
        assertEquals(102400L, r.chunkSize());
        assertEquals(5, r.chunkCount());
    }

    @Test
    void resumeCarriesAlreadyUploadedChunks() {
        List<Integer> uploaded = new java.util.ArrayList<>(List.of(0, 1, 3));
        UploadInitResponse r = UploadInitResponse.uploadSession("uuid-2", uploaded, 102400L, 8);
        assertFalse(r.instantUpload());
        assertNull(r.documentId());
        assertEquals("uuid-2", r.uploadId());
        assertEquals(List.of(0, 1, 3), r.uploadedChunks());
        assertEquals(102400L, r.chunkSize());
        assertEquals(8, r.chunkCount());
        // record 不拷贝列表引用——构造后外部修改可见
        uploaded.add(4);
        assertEquals(List.of(0, 1, 3, 4), r.uploadedChunks());
    }

    @Test
    void resumeWithEmptyChunkListIsAllowed() {
        UploadInitResponse r = UploadInitResponse.uploadSession("uuid-3", List.of(), 100L, 1);
        assertFalse(r.instantUpload());
        assertEquals("uuid-3", r.uploadId());
        assertEquals(List.of(), r.uploadedChunks());
    }
}