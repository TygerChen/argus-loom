package com.argus.rag.document.service;

import com.argus.rag.auth.CurrentUserService;
import com.argus.rag.common.exception.BusinessException;
import com.argus.rag.common.exception.ErrorCodes;
import com.argus.rag.document.mapper.DocumentMapper;
import com.argus.rag.document.mapper.DocumentUploadChunkMapper;
import com.argus.rag.document.mapper.DocumentUploadSessionMapper;
import com.argus.rag.document.model.dto.UploadChunkRequest;
import com.argus.rag.document.model.dto.UploadDocumentRequest;
import com.argus.rag.document.model.dto.UploadInitRequest;
import com.argus.rag.document.model.entity.DocumentEntity;
import com.argus.rag.document.model.entity.DocumentUploadChunkEntity;
import com.argus.rag.document.model.entity.DocumentUploadSessionEntity;
import com.argus.rag.document.model.vo.UploadInitResponse;
import com.argus.rag.document.model.vo.UploadStatusResponse;
import com.argus.rag.document.storage.DocumentFileStore;
import com.argus.rag.engine.elasticsearch.ElasticsearchChunkIndexService;
import com.argus.rag.engine.storage.ObjectStorageService;
import com.argus.rag.group.service.GroupMembershipService;
import com.argus.rag.ingestion.service.IngestionJobService;
import com.argus.rag.ingestion.vector.VectorIngestionService;
import com.argus.rag.job.service.BackgroundJobService;
import com.argus.rag.common.util.FileHasher;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import jakarta.servlet.http.HttpServletRequest;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.mock.web.MockMultipartFile;

import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link DocumentUploadService} 行为契约测试（拆分前守卫）。
 * <p>分片上传链路（init→chunk→complete→status + 直接上传）是全项目最重、此前无测试守护的高危类。
 * 用例锁住：秒传/续传/新建三态、权限归属、分片校验、合并、补偿、相关错误码，
 * 供「门面 + 上传会话状态机 + 对象存储组装」三拆时保持行为等价。</p>
 */
@DisplayName("分片上传服务（DocumentUploadService）")
class DocumentUploadServiceTest {

    private static final Long OWNER = 11L;
    private static final String BUCKET = "argus-docs";

    private DocumentMapper documentMapper;
    private DocumentUploadSessionMapper sessionMapper;
    private DocumentUploadChunkMapper chunkMapper;
    private GroupMembershipService groupMembershipService;
    private ObjectStorageService objectStorageService;
    private VectorIngestionService vectorIngestionService;
    private ElasticsearchChunkIndexService esIndexService;
    private ApplicationEventPublisher eventPublisher;
    private IngestionJobService ingestionJobService;
    private BackgroundJobService backgroundJobService;
    private HttpServletRequest request;
    private DocumentUploadService service;

    @BeforeEach
    void setUp() {
        // 纯 mock 环境无 MyBatis 上下文：LambdaUpdateWrapper 需要实体 TableInfo 缓存，手动注册
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                DocumentUploadSessionEntity.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                DocumentUploadChunkEntity.class);

        documentMapper = mock(DocumentMapper.class);
        sessionMapper = mock(DocumentUploadSessionMapper.class);
        chunkMapper = mock(DocumentUploadChunkMapper.class);
        groupMembershipService = mock(GroupMembershipService.class);
        objectStorageService = mock(ObjectStorageService.class);
        vectorIngestionService = mock(VectorIngestionService.class);
        esIndexService = mock(ElasticsearchChunkIndexService.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        ingestionJobService = mock(IngestionJobService.class);
        backgroundJobService = mock(BackgroundJobService.class);
        request = mock(HttpServletRequest.class);

        CurrentUserService.CurrentUser owner = mock(CurrentUserService.CurrentUser.class);
        when(owner.userId()).thenReturn(OWNER);
        when(groupMembershipService.requireGroupOwner(any())).thenReturn(owner);
        when(objectStorageService.getDefaultBucket()).thenReturn(BUCKET);

        DocumentFileStore fileStore = new DocumentFileStore(objectStorageService);
        service = new DocumentUploadService(
                documentMapper, sessionMapper, chunkMapper,
                groupMembershipService, fileStore,
                vectorIngestionService, esIndexService, eventPublisher, ingestionJobService,
                backgroundJobService);
    }

    private UploadInitRequest initRequest() {
        return new UploadInitRequest(
                1L, "report.pdf", 4096L, "application/pdf", "h1", 1024L, 4, null);
    }

    private DocumentUploadSessionEntity activeSession(String uploadId, int chunkCount, String status) {
        DocumentUploadSessionEntity s = new DocumentUploadSessionEntity();
        s.setUploadId(uploadId);
        s.setGroupId(1L);
        s.setUploaderUserId(OWNER);
        s.setFileName("report.pdf");
        s.setFileExt("pdf");
        s.setContentType("application/pdf");
        s.setFileSize(4096L);
        s.setFileHash("hash1");
        s.setChunkSize(1024L);
        s.setChunkCount(chunkCount);
        s.setStatus(status);
        s.setStorageBucket(BUCKET);
        s.setExpiresAt(LocalDateTime.now().plusHours(1));
        s.setCreatedAt(LocalDateTime.now());
        s.setUpdatedAt(LocalDateTime.now());
        return s;
    }

    private void givenDocumentId(long id) {
        when(documentMapper.insert(any(DocumentEntity.class))).thenAnswer(inv -> {
            ((DocumentEntity) inv.getArgument(0)).setId(id);
            return 1;
        });
    }

    private String shaHex(byte[] data) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(data);
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ─── init 三态 ───

    @Test
    void initUploadCreatesNewSessionWhenNoDedupOrReusable() {
        when(documentMapper.selectByGroupIdAndFileHash(any(), any())).thenReturn(null);
        when(sessionMapper.selectLatestReusableSession(any(), any(), any())).thenReturn(null);

        UploadInitResponse resp = service.initUpload(request, initRequest());

        assertFalse(resp.instantUpload());
        assertNotNull(resp.uploadId());
        assertEquals(4, resp.chunkCount());
        verify(sessionMapper).insert(any(DocumentUploadSessionEntity.class));
    }

    @Test
    void initUploadInstantReuseWhenReadyDocumentExists() {
        DocumentEntity existing = readyDocument();
        when(documentMapper.selectByGroupIdAndFileHash(any(), any())).thenReturn(existing);
        givenDocumentId(99L);

        UploadInitResponse resp = service.initUpload(request, initRequest());

        assertTrue(resp.instantUpload(), "同哈希 READY 文档应秒传");
        assertEquals(99L, resp.documentId());
    }

    @Test
    void initUploadSkipsInstantWhenClientChoosesNewVersion() {
        DocumentEntity existing = readyDocument();
        when(documentMapper.selectByGroupIdAndFileHash(any(), any())).thenReturn(existing);

        UploadInitRequest requestWithNewVersion = new UploadInitRequest(
                1L, "report.pdf", 4096L, "application/pdf", "h1", 1024L, 4, "CREATE_NEW");
        UploadInitResponse resp = service.initUpload(request, requestWithNewVersion);

        assertFalse(resp.instantUpload(), "客户端选择新建版本时不秒传");
        verify(ingestionJobService, never()).registerIngestDocumentJob(anyLong(), anyLong());
    }

    @Test
    void checkDuplicateReportsExistingReadyDocument() {
        DocumentEntity existing = readyDocument();
        when(documentMapper.selectByGroupIdAndFileHash(any(), any())).thenReturn(existing);

        DocumentUploadService.DuplicateCheckResult result = service.checkDuplicate(request, 1L, "h1");

        assertTrue(result.exists());
        assertEquals(50L, result.existingDocumentId());
    }

    @Test
    void checkDuplicateReturnsAbsentWhenNoMatch() {
        when(documentMapper.selectByGroupIdAndFileHash(any(), any())).thenReturn(null);

        DocumentUploadService.DuplicateCheckResult result = service.checkDuplicate(request, 1L, "h-none");

        assertFalse(result.exists());
    }

    @Test
    void initUploadRejectsIllegalDuplicatePolicy() {
        UploadInitRequest bad = new UploadInitRequest(
                1L, "report.pdf", 4096L, "application/pdf", "h1", 1024L, 4, "DELETE_ALL");

        BusinessException ex = assertThrows(BusinessException.class, () -> service.initUpload(request, bad));

        assertEquals("duplicatePolicy 非法（仅支持 REUSE / CREATE_NEW）", ex.getMessage());
    }

    @Test
    void initUploadResumesReusableSessionWithUploadedChunks() {
        when(documentMapper.selectByGroupIdAndFileHash(any(), any())).thenReturn(null);
        DocumentUploadSessionEntity s = activeSession("up1", 4, "INIT");
        when(sessionMapper.selectLatestReusableSession(any(), any(), any())).thenReturn(s);
        DocumentUploadChunkEntity c0 = new DocumentUploadChunkEntity(); c0.setChunkIndex(0);
        DocumentUploadChunkEntity c1 = new DocumentUploadChunkEntity(); c1.setChunkIndex(1);
        when(chunkMapper.selectByUploadId("up1")).thenReturn(List.of(c0, c1));

        UploadInitResponse resp = service.initUpload(request, initRequest());

        assertFalse(resp.instantUpload());
        assertEquals("up1", resp.uploadId());
        assertEquals(List.of(0, 1), resp.uploadedChunks());
    }

    // ─── uploadChunk ───

    @Test
    void uploadChunkPersistsObjectAndUpsertsChunk() {
        DocumentUploadSessionEntity active = activeSession("up1", 1, "INIT");
        when(sessionMapper.selectByUploadId("up1")).thenReturn(active);
        when(chunkMapper.selectByUploadId("up1")).thenReturn(List.of());
        when(sessionMapper.update(any(), any())).thenReturn(1);

        byte[] bytes = "hello-chunk-bytes".getBytes();
        MockMultipartFile chunk = new MockMultipartFile("chunk", "part0", "application/octet-stream", bytes);
        UploadChunkRequest req = new UploadChunkRequest("up1", 0, shaHex(bytes), chunk);

        List<Integer> res = service.uploadChunk(request, req);

        assertNotNull(res);
        verify(objectStorageService).putObject(any(), any(), any(), anyLong(), any());
        verify(chunkMapper).upsert(any(DocumentUploadChunkEntity.class));
    }

    @Test
    void uploadChunkRejectsHashMismatch() {
        DocumentUploadSessionEntity active = activeSession("up1", 2, "INIT");
        when(sessionMapper.selectByUploadId("up1")).thenReturn(active);
        byte[] bytes = "hello-chunk-bytes".getBytes();
        MockMultipartFile chunk = new MockMultipartFile("chunk", "part0", "application/octet-stream", bytes);
        UploadChunkRequest req = new UploadChunkRequest("up1", 0, "WRONG-HASH", chunk);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.uploadChunk(request, req));
        assertEquals(ErrorCodes.UPLOAD_INCOMPLETE, ex.getCode());
        verify(chunkMapper, org.mockito.Mockito.never()).upsert(any());
    }

    @Test
    void uploadChunkRejectsOutOfRangeIndex() {
        DocumentUploadSessionEntity active = activeSession("up1", 2, "INIT");
        when(sessionMapper.selectByUploadId("up1")).thenReturn(active);
        byte[] bytes = "x".getBytes();
        MockMultipartFile chunk = new MockMultipartFile("chunk", "p", "application/octet-stream", bytes);
        UploadChunkRequest req = new UploadChunkRequest("up1", 99, shaHex(bytes), chunk);
        assertThrows(BusinessException.class, () -> service.uploadChunk(request, req));
    }

    // ─── complete ───

    @Test
    void completeUploadComposesAndPersistsDocument() {
        DocumentUploadSessionEntity active = activeSession("up1", 1, "UPLOADING");
        when(sessionMapper.selectByUploadId("up1")).thenReturn(active);
        DocumentUploadChunkEntity c0 = new DocumentUploadChunkEntity();
        c0.setChunkIndex(0);
        c0.setStorageBucket(BUCKET);
        c0.setStorageObjectKey("uploads/1/up1/chunks/0");
        when(chunkMapper.selectByUploadId("up1")).thenReturn(List.of(c0));
        when(sessionMapper.update(any(), any())).thenReturn(1);
        givenDocumentId(77L);

        Long id = service.completeUpload(request, "up1");

        assertEquals(77L, id);
        verify(objectStorageService).composeObject(any(), any(), any(), any());
        verify(backgroundJobService).enqueue(any(BackgroundJobService.NewBackgroundJobCommand.class));
    }

    @Test
    void completeUploadRejectsMissingChunks() {
        DocumentUploadSessionEntity active = activeSession("up1", 2, "UPLOADING");
        when(sessionMapper.selectByUploadId("up1")).thenReturn(active);
        when(chunkMapper.selectByUploadId("up1")).thenReturn(List.of());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.completeUpload(request, "up1"));
        assertEquals(ErrorCodes.UPLOAD_INCOMPLETE, ex.getCode());
    }

    @Test
    void completeUploadRejectsWhenSessionNotUploading() {
        DocumentUploadSessionEntity active = activeSession("up1", 1, "COMPLETED");
        active.setMergedObjectKey("g/x.pdf");
        when(sessionMapper.selectByUploadId("up1")).thenReturn(active);
        // 已完成会话不可复用：requireOwnedActiveSession 即抛
        assertThrows(BusinessException.class, () -> service.completeUpload(request, "up1"));
    }

    // ─── status / 权限 ───

    @Test
    void getUploadStatusReportsUploadedChunks() {
        DocumentUploadSessionEntity active = activeSession("up1", 2, "UPLOADING");
        when(sessionMapper.selectByUploadId("up1")).thenReturn(active);
        DocumentUploadChunkEntity c0 = new DocumentUploadChunkEntity(); c0.setChunkIndex(0);
        when(chunkMapper.selectByUploadId("up1")).thenReturn(List.of(c0));

        UploadStatusResponse resp = service.getUploadStatus(request, "up1");

        assertEquals(2, resp.chunkCount());
        assertEquals(List.of(0), resp.uploadedChunks());
    }

    @Test
    void getUploadStatusRejectsForeignOwner() {
        DocumentUploadSessionEntity held = activeSession("up1", 1, "INIT");
        held.setUploaderUserId(999L);
        when(sessionMapper.selectByUploadId("up1")).thenReturn(held);
        assertThrows(BusinessException.class, () -> service.getUploadStatus(request, "up1"));
    }

    @Test
    void getUploadStatusRejectsExpiredSession() {
        DocumentUploadSessionEntity held = activeSession("up1", 1, "INIT");
        held.setExpiresAt(LocalDateTime.now().minusHours(2));
        when(sessionMapper.selectByUploadId("up1")).thenReturn(held);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.getUploadStatus(request, "up1"));
        assertEquals(ErrorCodes.UPLOAD_SESSION_INVALID, ex.getCode());
    }

    // ─── 直接上传 ───

    @Test
    void directUploadPersistsAndPublishesEvent() {
        givenDocumentId(33L);
        MockMultipartFile file = new MockMultipartFile("file", "a.pdf", "application/pdf", "pdf-bytes".getBytes());
        UploadDocumentRequest req = new UploadDocumentRequest();
        req.setGroupId(1L);
        req.setFile(file);

        Long id = service.uploadDocument(OWNER, req);

        assertEquals(33L, id);
        verify(backgroundJobService).enqueue(any(BackgroundJobService.NewBackgroundJobCommand.class));
    }

    private DocumentEntity readyDocument() {
        DocumentEntity d = new DocumentEntity();
        d.setId(50L);
        d.setStatus("READY");
        d.setFileExt("pdf");
        d.setContentType("application/pdf");
        d.setFileSize(4096L);
        d.setFileHash("h1");
        d.setStorageBucket(BUCKET);
        d.setStorageObjectKey("groups/1/users/11/x.pdf");
        return d;
    }
}
