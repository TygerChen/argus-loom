package com.argus.rag.document.service;

import com.argus.rag.auth.CurrentUserService;
import com.argus.rag.common.exception.BusinessException;
import com.argus.rag.common.exception.ErrorCodes;
import com.argus.rag.common.util.FileHasher;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
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
import com.argus.rag.group.service.GroupMembershipService;
import com.argus.rag.document.storage.DocumentFileStore;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * 大文件分片上传服务。
 *
 * <p>支持将文件（最大 256MB）拆分为多个分片上传，每个分片最大 10MB。
 * 上传流程：初始化会话（init） -> 逐个上传分片（upload chunks） -> 合并完成（complete）。
 * 支持文件哈希去重（秒传复用）和可续传会话恢复。
 *
 * <p>上传会话在 24 小时后过期。
 *
 * @author Argus-RAG Team
 * @since 1.0.0
 */
@Service
@Slf4j
public class DocumentUploadService {

    /** 分片上传最大文件大小：256MB */
    private static final long MAX_FILE_SIZE = 256L * 1024 * 1024;
    /** 单个分片的最大大小：10MB */
    private static final long MAX_CHUNK_SIZE = 10L * 1024 * 1024;
    /** 上传会话过期时长：24 小时 */
    private static final long SESSION_EXPIRE_HOURS = 24L;
    /** 上传会话状态：已初始化 */
    private static final String UPLOAD_STATUS_INIT = "INIT";
    /** 上传会话状态：上传中 */
    private static final String UPLOAD_STATUS_UPLOADING = "UPLOADING";
    /** 上传会话状态：正在合并 */
    private static final String UPLOAD_STATUS_COMPLETING = "COMPLETING";
    /** 上传会话状态：已完成 */
    private static final String UPLOAD_STATUS_COMPLETED = "COMPLETED";
    /** 分片默认 MIME 类型 */
    private static final String OCTET_STREAM = "application/octet-stream";
    /** 直接上传最大文件大小：10MB */
    private static final long MAX_DIRECT_FILE_SIZE = 10L * 1024 * 1024;
    /** 重复文件策略：秒传复用已有文档 */
    private static final String DUPLICATE_POLICY_REUSE = "REUSE";
    /** 重复文件策略：作为新版本重新入库 */
    private static final String DUPLICATE_POLICY_CREATE_NEW = "CREATE_NEW";

    /** 文档数据访问 */
    private final DocumentMapper documentMapper;
    /** 上传会话数据访问 */
    private final DocumentUploadSessionMapper documentUploadSessionMapper;
    /** 上传分片数据访问 */
    private final DocumentUploadChunkMapper documentUploadChunkMapper;
    /** 群组成员权限服务 */
    private final GroupMembershipService groupMembershipService;
    /** 文档对象存储专职组件（key 构造 / 分片写入 / 合并 / 补偿） */
    private final DocumentFileStore fileStore;
    /** 向量导入服务 */
    private final com.argus.rag.ingestion.vector.VectorIngestionService vectorIngestionService;
    /** Elasticsearch chunk 索引服务 */
    private final com.argus.rag.engine.elasticsearch.ElasticsearchChunkIndexService elasticsearchChunkIndexService;
    /** Spring 事件发布器 */
    private final org.springframework.context.ApplicationEventPublisher applicationEventPublisher;
    /** 文档元数据持久化 + 外部索引登记（专职组件） */
    private final UploadedDocumentFinalizer finalizer;
    /** 摄入任务记录服务（上传落库后登记 ingestion_jobs 记录） */
    private final com.argus.rag.ingestion.service.IngestionJobService ingestionJobService;
    /** 可靠任务队列服务（ETL 任务真正入队的地方，08 篇） */
    private final com.argus.rag.job.service.BackgroundJobService backgroundJobService;

    public DocumentUploadService(
            DocumentMapper documentMapper,
            DocumentUploadSessionMapper documentUploadSessionMapper,
            DocumentUploadChunkMapper documentUploadChunkMapper,
            GroupMembershipService groupMembershipService,
            DocumentFileStore fileStore,
            com.argus.rag.ingestion.vector.VectorIngestionService vectorIngestionService,
            com.argus.rag.engine.elasticsearch.ElasticsearchChunkIndexService elasticsearchChunkIndexService,
            org.springframework.context.ApplicationEventPublisher applicationEventPublisher,
            com.argus.rag.ingestion.service.IngestionJobService ingestionJobService,
            com.argus.rag.job.service.BackgroundJobService backgroundJobService
    ) {
        this.documentMapper = documentMapper;
        this.documentUploadSessionMapper = documentUploadSessionMapper;
        this.documentUploadChunkMapper = documentUploadChunkMapper;
        this.groupMembershipService = groupMembershipService;
        this.fileStore = fileStore;
        this.vectorIngestionService = vectorIngestionService;
        this.elasticsearchChunkIndexService = elasticsearchChunkIndexService;
        this.applicationEventPublisher = applicationEventPublisher;
        this.ingestionJobService = ingestionJobService;
        this.backgroundJobService = backgroundJobService;
        this.finalizer = new UploadedDocumentFinalizer(
                documentMapper, applicationEventPublisher, vectorIngestionService,
                elasticsearchChunkIndexService, ingestionJobService, backgroundJobService);
    }

    /**
     * 初始化分片上传会话。
     *
     * <p>按优先级依次尝试：
     * <ol>
     *   <li>秒传：存在相同哈希的 READY 文档则直接复用</li>
     *   <li>断点续传：存在相同哈希的未过期会话则恢复</li>
     *   <li>新建会话：创建全新的上传会话</li>
     * </ol>
     * 需要调用者是群组管理员。
     *
     * @param request       HTTP 请求（用于提取当前用户信息）
     * @param uploadRequest 上传初始化请求（文件名、大小、哈希、分片参数等）
     * @return 上传初始化响应（秒传返回 documentId，续传/新建返回 uploadId 和分片参数）
     * @throws BusinessException 参数校验失败、文件类型不支持、大小超限、无权限时抛出
     */
    @Transactional
    public UploadInitResponse initUpload(HttpServletRequest request, UploadInitRequest uploadRequest) {
        NormalizedInitRequest normalizedRequest = validateInitRequest(uploadRequest);
        Long groupId = normalizedRequest.groupId();
        CurrentUserService.CurrentUser currentUser = groupMembershipService.requireGroupOwner(groupId);
        DocumentEntity existingDocument = documentMapper.selectByGroupIdAndFileHash(groupId, normalizedRequest.fileHash());
        if (existingDocument != null && "READY".equals(existingDocument.getStatus())) {
            if (DUPLICATE_POLICY_CREATE_NEW.equals(normalizedRequest.duplicatePolicy())) {
                log.info("分片上传-同哈希文件按新版本入库: groupId={}, userId={}, fileHash={}",
                        groupId, currentUser.userId(), normalizedRequest.fileHash());
            } else {
            log.info("分片上传-秒传复用: groupId={}, userId={}, fileName={}, fileHash={}, reusedDocumentId={}",
                    groupId, currentUser.userId(), normalizedRequest.fileName(), normalizedRequest.fileHash(), existingDocument.getId());
            Long documentId = createInstantUploadedDocument(
                    groupId,
                    currentUser.userId(),
                    existingDocument,
                    normalizedRequest.fileName()
            );
            return UploadInitResponse.instant(documentId);
            }
        }
        DocumentUploadSessionEntity existingSession = documentUploadSessionMapper.selectLatestReusableSession(
                groupId,
                currentUser.userId(),
                normalizedRequest.fileHash()
        );
        if (existingSession != null) {
            log.info("分片上传-续传恢复: groupId={}, userId={}, uploadId={}, fileName={}, fileHash={}, chunkSize={}, chunkCount={}",
                    groupId, currentUser.userId(), existingSession.getUploadId(), normalizedRequest.fileName(),
                    normalizedRequest.fileHash(), existingSession.getChunkSize(), existingSession.getChunkCount());
            List<Integer> uploadedChunks = documentUploadChunkMapper.selectByUploadId(existingSession.getUploadId()).stream()
                    .map(DocumentUploadChunkEntity::getChunkIndex)
                    .toList();
            return UploadInitResponse.uploadSession(
                    existingSession.getUploadId(),
                    uploadedChunks,
                    existingSession.getChunkSize(),
                    existingSession.getChunkCount()
            );
        }
        DocumentUploadSessionEntity session = buildUploadSession(groupId, currentUser.userId(), normalizedRequest);
        documentUploadSessionMapper.insert(session);
        log.info("分片上传-新建会话: groupId={}, userId={}, uploadId={}, fileName={}, fileHash={}, fileSize={}, chunkSize={}, chunkCount={}",
                groupId, currentUser.userId(), session.getUploadId(), normalizedRequest.fileName(),
                normalizedRequest.fileHash(), normalizedRequest.fileSize(), normalizedRequest.chunkSize(), normalizedRequest.chunkCount());
        return UploadInitResponse.uploadSession(session.getUploadId(), session.getChunkSize(), session.getChunkCount());
    }

    /**
     * 上传单个分片。
     *
     * <p>校验分片序号和大小，将分片上传至对象存储，记录分片元数据到数据库。
     * 使用 upsert 支持同一分片重复上传（幂等）。
     * 需要上传会话属于当前用户且未过期、未完成。
     *
     * @param request       HTTP 请求（用于提取当前用户信息）
     * @param uploadRequest 分片上传请求（uploadId、chunkIndex、分片数据和哈希）
     * @return 当前已上传的分片索引列表
     * @throws BusinessException 会话无效、分片越界、分片数据为空或超限时抛出
     */
    @Transactional
    public List<Integer> uploadChunk(HttpServletRequest request, UploadChunkRequest uploadRequest) {
        DocumentUploadSessionEntity session = requireOwnedActiveSession(request, uploadRequest.uploadId());
        MultipartFile chunk = requireChunk(uploadRequest, session);
        String chunkHash = UploadInputGuard.normalizeFileHash(uploadRequest.chunkHash());
        String objectKey = fileStore.chunkKey(session.getGroupId(), session.getUploadId(), uploadRequest.chunkIndex());
        log.debug("分片上传-接收分片: uploadId={}, chunkIndex={}/{}, chunkSize={}",
                uploadRequest.uploadId(), uploadRequest.chunkIndex(), session.getChunkCount(), chunk.getSize());
        LocalDateTime now = LocalDateTime.now();
        byte[] chunkData;
        try {
            chunkData = chunk.getBytes();
        } catch (IOException exception) {
            throw new BusinessException(ErrorCodes.UPLOAD_INCOMPLETE, "读取分片数据失败");
        }
        String computedHash = FileHasher.sha256Hex(chunkData);
        if (!computedHash.equals(chunkHash)) {
            throw new BusinessException(ErrorCodes.UPLOAD_INCOMPLETE, "分片校验失败，数据可能已损坏");
        }
        try {
            fileStore.putChunk(session.getStorageBucket(), objectKey, chunkData);
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new BusinessException(ErrorCodes.UPLOAD_INCOMPLETE, "分片上传失败");
        }

        DocumentUploadChunkEntity uploadChunk = new DocumentUploadChunkEntity();
        uploadChunk.setUploadId(session.getUploadId());
        uploadChunk.setChunkIndex(uploadRequest.chunkIndex());
        uploadChunk.setChunkSize((long) chunkData.length);
        uploadChunk.setChunkHash(chunkHash);
        uploadChunk.setStorageBucket(session.getStorageBucket());
        uploadChunk.setStorageObjectKey(objectKey);
        uploadChunk.setUploadedAt(now);
        uploadChunk.setCreatedAt(now);
        uploadChunk.setUpdatedAt(now);
        try {
            documentUploadChunkMapper.upsert(uploadChunk);
        } catch (RuntimeException exception) {
            fileStore.deleteQuietly(session.getStorageBucket(), objectKey);
            throw exception;
        }
        documentUploadSessionMapper.update(null, new LambdaUpdateWrapper<DocumentUploadSessionEntity>()
                .eq(DocumentUploadSessionEntity::getUploadId, session.getUploadId())
                .set(DocumentUploadSessionEntity::getStatus, UPLOAD_STATUS_UPLOADING)
                .set(DocumentUploadSessionEntity::getMergedObjectKey, null)
                .set(DocumentUploadSessionEntity::getUpdatedAt, now)
        );
        List<Integer> uploadedChunkIndexes = documentUploadChunkMapper.selectByUploadId(session.getUploadId()).stream()
                .map(DocumentUploadChunkEntity::getChunkIndex)
                .toList();
        if (uploadedChunkIndexes.size() == session.getChunkCount()) {
            log.info("分片上传-全部接收完成: uploadId={}, fileName={}, totalChunks={}",
                    session.getUploadId(), session.getFileName(), session.getChunkCount());
        }
        return uploadedChunkIndexes;
    }

    /**
     * 完成分片上传，合并所有分片为最终文档。
     *
     * <p>流程：
     * <ol>
     *   <li>校验所有分片已按序就位</li>
     *   <li>调用对象存储合并分片为单一对象</li>
     *   <li>委托 {@link DocumentService#finalizeUploadedDocument} 创建文档记录</li>
     *   <li>将上传会话标记为 COMPLETED</li>
     * </ol>
     * 合并失败时会尝试删除已合并的临时对象。
     * 需要上传会话属于当前用户且未过期、未完成。
     *
     * @param request  HTTP 请求（用于提取当前用户信息）
     * @param uploadId 上传会话 ID
     * @return 新创建的文档 ID
     * @throws BusinessException 会话无效、分片缺失、合并失败、文档持久化失败时抛出
     */
    @Transactional
    public Long completeUpload(HttpServletRequest request, String uploadId) {
        DocumentUploadSessionEntity session = requireOwnedActiveSession(request, uploadId);
        List<DocumentUploadChunkEntity> chunks = documentUploadChunkMapper.selectByUploadId(uploadId).stream()
                .sorted(Comparator.comparing(DocumentUploadChunkEntity::getChunkIndex))
                .toList();
        ensureAllChunksPresent(session, chunks);
        log.info("分片上传-开始合并: uploadId={}, fileName={}, fileSize={}, chunkCount={}",
                uploadId, session.getFileName(), session.getFileSize(), chunks.size());
        String objectKey = fileStore.finalObjectKey(session.getGroupId(), session.getUploaderUserId(), session.getFileExt());
        LocalDateTime now = LocalDateTime.now();
        int updated = documentUploadSessionMapper.update(null, new LambdaUpdateWrapper<DocumentUploadSessionEntity>()
                .eq(DocumentUploadSessionEntity::getUploadId, uploadId)
                .eq(DocumentUploadSessionEntity::getStatus, UPLOAD_STATUS_UPLOADING)
                .set(DocumentUploadSessionEntity::getStatus, UPLOAD_STATUS_COMPLETING)
                .set(DocumentUploadSessionEntity::getMergedObjectKey, null)
                .set(DocumentUploadSessionEntity::getUpdatedAt, now)
        );
        if (updated == 0) {
            throw new BusinessException(ErrorCodes.UPLOAD_SESSION_INVALID, "上传会话状态异常，无法完成上传");
        }
        try {
            fileStore.compose(session.getStorageBucket(), objectKey,
                    chunks.stream().map(DocumentUploadChunkEntity::getStorageObjectKey).toList(),
                    session.getContentType());
            Long documentId = finalizeUploadedDocument(
                    session.getGroupId(),
                    session.getUploaderUserId(),
                    session.getFileName(),
                    session.getFileExt(),
                    session.getContentType(),
                    session.getFileSize(),
                    session.getFileHash(),
                    session.getStorageBucket(),
                    objectKey
            );
            documentUploadSessionMapper.update(null, new LambdaUpdateWrapper<DocumentUploadSessionEntity>()
                    .eq(DocumentUploadSessionEntity::getUploadId, uploadId)
                    .set(DocumentUploadSessionEntity::getStatus, UPLOAD_STATUS_COMPLETED)
                    .set(DocumentUploadSessionEntity::getMergedObjectKey, objectKey)
                    .set(DocumentUploadSessionEntity::getUpdatedAt, LocalDateTime.now())
            );
            log.info("分片上传-合并完成: uploadId={}, fileName={}, documentId={}, objectKey={}",
                    uploadId, session.getFileName(), documentId, objectKey);
            return documentId;
        } catch (RuntimeException exception) {
            log.error("分片上传-合并失败: uploadId={}, fileName={}, reason={}",
                    uploadId, session.getFileName(), exception.getMessage(), exception);
            fileStore.deleteQuietly(session.getStorageBucket(), objectKey);
            for (DocumentUploadChunkEntity chunk : chunks) {
                fileStore.deleteQuietly(session.getStorageBucket(), chunk.getStorageObjectKey());
            }
            throw exception;
        }
    }

    /**
     * 查询上传会话的当前状态。
     *
     * <p>返回会话状态、已上传分片索引列表、已上传分片数量、总分片数。
     * 需要上传会话属于当前用户且未过期、未完成。
     *
     * @param request  HTTP 请求（用于提取当前用户信息）
     * @param uploadId 上传会话 ID
     * @return 上传状态信息
     * @throws BusinessException 会话无效时抛出
     */
    public UploadStatusResponse getUploadStatus(HttpServletRequest request, String uploadId) {
        DocumentUploadSessionEntity session = requireOwnedActiveSession(request, uploadId);
        List<Integer> uploadedChunks = documentUploadChunkMapper.selectByUploadId(uploadId).stream()
                .map(DocumentUploadChunkEntity::getChunkIndex)
                .toList();
        return new UploadStatusResponse(
                session.getStatus(),
                uploadedChunks,
                uploadedChunks.size(),
                session.getChunkCount()
        );
    }

    /**
     * 校验并规范化上传初始化请求中的所有参数。
     *
     * @param uploadRequest 原始请求
     * @return 规范化后的请求参数
     * @throws BusinessException 请求为 null 或任一参数非法时抛出
     */
    private NormalizedInitRequest validateInitRequest(UploadInitRequest uploadRequest) {
        if (uploadRequest == null) {
            throw new BusinessException("上传初始化请求不能为空");
        }
        Long groupId = requireGroupId(uploadRequest.groupId());
        String fileName = UploadInputGuard.sanitizeFileName(uploadRequest.fileName());
        String fileExt = UploadInputGuard.extractFileExt(fileName);
        long fileSize = UploadInputGuard.requirePositive(uploadRequest.fileSize(), "fileSize 非法");
        if (fileSize > MAX_FILE_SIZE) {
            throw new BusinessException("上传文件超过大小限制");
        }
        String contentType = UploadInputGuard.normalizeContentType(uploadRequest.contentType());
        String fileHash = UploadInputGuard.normalizeFileHash(uploadRequest.fileHash());
        long chunkSize = UploadInputGuard.requirePositive(uploadRequest.chunkSize(), "chunkSize 非法");
        if (chunkSize > MAX_CHUNK_SIZE) {
            throw new BusinessException("chunkSize 超过限制");
        }
        int chunkCount = UploadInputGuard.requirePositive(uploadRequest.chunkCount(), "chunkCount 非法");
        long expectedChunkCount = (fileSize + chunkSize - 1) / chunkSize;
        if (chunkCount != expectedChunkCount) {
            throw new BusinessException("chunkCount 与文件大小不匹配");
        }
        String duplicatePolicy = uploadRequest.duplicatePolicy();
        if (duplicatePolicy != null && !duplicatePolicy.isBlank()
                && !DUPLICATE_POLICY_REUSE.equals(duplicatePolicy)
                && !DUPLICATE_POLICY_CREATE_NEW.equals(duplicatePolicy)) {
            throw new BusinessException("duplicatePolicy 非法（仅支持 REUSE / CREATE_NEW）");
        }
        return new NormalizedInitRequest(groupId, fileName, fileExt, fileSize, contentType, fileHash,
                chunkSize, chunkCount, duplicatePolicy);
    }

    /**
     * 校验 groupId 必须为正数。
     *
     * @param groupId 群组 ID
     * @return 合法的 groupId
     * @throws BusinessException groupId 为 null 或 <= 0 时抛出
     */
    private static Long requireGroupId(Long groupId) {
        if (groupId == null || groupId <= 0) {
            throw new BusinessException("groupId 非法");
        }
        return groupId;
    }

    

    

    

    

    

    

    /**
     * 查询并校验上传会话：存在、属于当前用户、未过期、未完成。
     *
     * @param request  HTTP 请求（用于提取当前用户信息）
     * @param uploadId 上传会话 ID
     * @return 合法的上传会话实体
     * @throws BusinessException uploadId 为空、会话不存在、不属于当前用户、已过期、已完成时抛出
     */
    private DocumentUploadSessionEntity requireOwnedActiveSession(HttpServletRequest request, String uploadId) {
        if (!StringUtils.hasText(uploadId)) {
            throw new BusinessException("uploadId 非法");
        }
        DocumentUploadSessionEntity session = documentUploadSessionMapper.selectByUploadId(uploadId.trim());
        if (session == null) {
            throw new BusinessException(ErrorCodes.UPLOAD_SESSION_INVALID, "上传会话不存在");
        }
        CurrentUserService.CurrentUser currentUser = groupMembershipService.requireGroupOwner(session.getGroupId());
        if (!currentUser.userId().equals(session.getUploaderUserId())) {
            throw new BusinessException(ErrorCodes.UPLOAD_SESSION_INVALID, "上传会话不属于当前用户");
        }
        if (session.getExpiresAt() != null && session.getExpiresAt().isBefore(LocalDateTime.now())) {
            throw new BusinessException(ErrorCodes.UPLOAD_SESSION_INVALID, "上传会话已过期");
        }
        if (UPLOAD_STATUS_COMPLETED.equals(session.getStatus()) || UPLOAD_STATUS_COMPLETING.equals(session.getStatus())) {
            throw new BusinessException(ErrorCodes.UPLOAD_SESSION_INVALID, "上传会话已完成或正在合并");
        }
        return session;
    }

    /**
     * 校验分片上传请求：非空、chunkIndex 在合法范围内、分片数据不为空且不超限。
     *
     * @param uploadRequest 分片上传请求
     * @param session       对应的上传会话
     * @return 校验通过的分片 MultipartFile
     * @throws BusinessException 请求为空、chunkIndex 越界、分片为空或超限时抛出
     */
    private MultipartFile requireChunk(UploadChunkRequest uploadRequest, DocumentUploadSessionEntity session) {
        if (uploadRequest == null) {
            throw new BusinessException("分片上传请求不能为空");
        }
        if (uploadRequest.chunkIndex() == null
                || uploadRequest.chunkIndex() < 0
                || uploadRequest.chunkIndex() >= session.getChunkCount()) {
            throw new BusinessException("chunkIndex 非法");
        }
        MultipartFile chunk = uploadRequest.chunk();
        if (chunk == null || chunk.isEmpty()) {
            throw new BusinessException("上传分片不能为空");
        }
        if (chunk.getSize() > session.getChunkSize()) {
            throw new BusinessException("上传分片超过大小限制");
        }
        return chunk;
    }

    /**
     * 确保所有分片已按序就位，数量和索引必须匹配。
     *
     * @param session 上传会话
     * @param chunks  已上传的分片列表
     * @throws BusinessException 分片数量不匹配或有缺失时抛出
     */
    private void ensureAllChunksPresent(DocumentUploadSessionEntity session, List<DocumentUploadChunkEntity> chunks) {
        if (chunks.size() != session.getChunkCount()) {
            throw new BusinessException(ErrorCodes.UPLOAD_INCOMPLETE, "缺少分片，无法完成上传");
        }
        for (int index = 0; index < session.getChunkCount(); index++) {
            DocumentUploadChunkEntity chunk = chunks.get(index);
            if (chunk == null || !Integer.valueOf(index).equals(chunk.getChunkIndex())) {
                throw new BusinessException(ErrorCodes.UPLOAD_INCOMPLETE, "缺少分片，无法完成上传");
            }
        }
    }

    /**
     * 构建新的上传会话实体。
     *
     * @param groupId       群组 ID
     * @param userId        用户 ID
     * @param uploadRequest 规范化后的初始化请求
     * @return 初始化完成的上传会话实体（状态为 INIT，24 小时后过期）
     */
    private DocumentUploadSessionEntity buildUploadSession(Long groupId, Long userId, NormalizedInitRequest uploadRequest) {
        LocalDateTime now = LocalDateTime.now();
        DocumentUploadSessionEntity session = new DocumentUploadSessionEntity();
        session.setUploadId(UUID.randomUUID().toString().replace("-", ""));
        session.setGroupId(groupId);
        session.setUploaderUserId(userId);
        session.setFileName(uploadRequest.fileName());
        session.setFileExt(uploadRequest.fileExt());
        session.setContentType(uploadRequest.contentType());
        session.setFileSize(uploadRequest.fileSize());
        session.setFileHash(uploadRequest.fileHash());
        session.setChunkSize(uploadRequest.chunkSize());
        session.setChunkCount(uploadRequest.chunkCount());
        session.setStatus(UPLOAD_STATUS_INIT);
        session.setStorageBucket(fileStore.defaultBucket());
        session.setMergedObjectKey(null);
        session.setExpiresAt(now.plusHours(SESSION_EXPIRE_HOURS));
        session.setCreatedAt(now);
        session.setUpdatedAt(now);
        return session;
    }

    /**
     * 规范化后的上传初始化请求参数，所有字段均已校验。
     *
     * @param groupId     群组 ID
     * @param fileName    文件名
     * @param fileExt     文件扩展名
     * @param fileSize    文件大小（字节）
     * @param contentType MIME 类型
     * @param fileHash    文件哈希
     * @param chunkSize   每个分片大小（字节）
     * @param chunkCount  总分片数
     */
    private record NormalizedInitRequest(
            Long groupId,
            String fileName,
            String fileExt,
            Long fileSize,
            String contentType,
            String fileHash,
            Long chunkSize,
            Integer chunkCount,
            String duplicatePolicy
    ) {
    }

    // ────────────────────────────── 直接上传 ──────────────────────────────

    /**
     * 检查同群组内是否已有同哈希的 READY 文档（只查不建）。
     *
     * <p>供上传对话框在真正提交前探测重复文件：命中时由用户决定
     * "复用已有文件（秒传）"或"作为新版本重新入库"，避免静默秒传。</p>
     *
     * @param request  HTTP 请求（用于提取当前用户信息）
     * @param groupId  群组 ID
     * @param fileHash 文件 SHA-256 哈希
     * @return exists 是否命中；existingDocumentId 命中时的文档 ID
     */
    @Transactional(readOnly = true)
    public DuplicateCheckResult checkDuplicate(HttpServletRequest request, Long groupId, String fileHash) {
        Long validGroupId = requireGroupId(groupId);
        groupMembershipService.requireGroupOwner(validGroupId);
        String normalizedHash = UploadInputGuard.normalizeFileHash(fileHash);
        DocumentEntity existing = documentMapper.selectByGroupIdAndFileHash(validGroupId, normalizedHash);
        if (existing == null) {
            return new DuplicateCheckResult(false, null);
        }
        log.info("重复文件检查命中: groupId={}, fileHash={}, existingDocumentId={}",
                validGroupId, normalizedHash, existing.getId());
        return new DuplicateCheckResult(true, existing.getId());
    }

    /** 重复文件检查结果。 */
    public record DuplicateCheckResult(boolean exists, Long existingDocumentId) {
    }

    /** 直接上传文档（小文件模式），参数已由 Controller 提取 userId */
    @Transactional
    public Long uploadDocument(Long userId, UploadDocumentRequest uploadRequest) {
        Long groupId = requireGroupId(uploadRequest.getGroupId());
        groupMembershipService.requireGroupOwner(groupId);
        MultipartFile file = requireValidFile(uploadRequest.getFile());
        String fileName = extractDirectFileName(file);
        String fileExt = UploadInputGuard.extractFileExt(fileName);
        String bucket = fileStore.defaultBucket();
        String objectKey = fileStore.directObjectKey(groupId, userId, fileExt);
        String fileHash = calculateSha256(file);
        DocumentEntity document = null;
        log.info("直接上传文档: groupId={}, userId={}, fileName={}, size={}, objectKey={}",
                groupId, userId, fileName, file.getSize(), objectKey);
        uploadDirectFile(bucket, objectKey, file);
        log.info("对象存储上传完成: groupId={}, objectKey={}", groupId, objectKey);
        try {
            document = persistAndFinalizeUploadedDocument(new FinalizedUploadCommand(
                    groupId, userId, fileName, fileExt,
                    UploadInputGuard.normalizeContentType(file.getContentType()), file.getSize(),
                    fileHash, bucket, objectKey));
            return document.getId();
        } catch (RuntimeException exception) {
            log.error("文档上传链路失败: groupId={}, objectKey={}, reason={}",
                    groupId, objectKey, exception.getMessage(), exception);
            compensateExternalIndexes(document);
            fileStore.deleteQuietly(bucket, objectKey);
            throw exception;
        }
    }

    /** 通过复用已有文档创建新文档记录（秒传） */
    @Transactional
    public Long createInstantUploadedDocument(Long groupId, Long userId,
                                               DocumentEntity existingDocument, String fileName) {
        if (existingDocument == null) {
            throw new BusinessException(ErrorCodes.DOCUMENT_NOT_FOUND, "复用文档不存在");
        }
        DocumentEntity document = persistAndFinalizeUploadedDocument(new FinalizedUploadCommand(
                requireGroupId(groupId),
                UploadInputGuard.requirePositiveUserId(userId),
                UploadInputGuard.validateReusableFileName(fileName),
                UploadInputGuard.requireText(existingDocument.getFileExt(), "文件扩展名非法"),
                UploadInputGuard.normalizeContentType(existingDocument.getContentType()),
                UploadInputGuard.requirePositiveFileSize(existingDocument.getFileSize()),
                existingDocument.getFileHash(),
                UploadInputGuard.requireText(existingDocument.getStorageBucket(), "对象存储桶非法"),
                UploadInputGuard.requireText(existingDocument.getStorageObjectKey(), "对象存储路径非法")
        ));
        return document.getId();
    }

    /** 完成分片上传后的文档持久化 */
    @Transactional
    public Long finalizeUploadedDocument(Long groupId, Long userId, String fileName,
                                          String fileExt, String contentType, Long fileSize,
                                          String fileHash, String bucket, String objectKey) {
        DocumentEntity document = persistAndFinalizeUploadedDocument(new FinalizedUploadCommand(
                requireGroupId(groupId),
                UploadInputGuard.requirePositiveUserId(userId),
                UploadInputGuard.sanitizeFileName(fileName),
                UploadInputGuard.requireText(fileExt, "文件扩展名非法"),
                UploadInputGuard.normalizeContentType(contentType),
                UploadInputGuard.requirePositiveFileSize(fileSize),
                fileHash,
                UploadInputGuard.requireText(bucket, "对象存储桶非法"),
                UploadInputGuard.requireText(objectKey, "对象存储路径非法")
        ));
        return document.getId();
    }

    // ──────────────────────── 直接上传辅助方法 ────────────────────────

    private static MultipartFile requireValidFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException("上传文件不能为空");
        }
        if (file.getSize() > MAX_DIRECT_FILE_SIZE) {
            throw new BusinessException("上传文件超过大小限制");
        }
        return file;
    }

    private static String extractDirectFileName(MultipartFile file) {
        String originalFileName = file.getOriginalFilename();
        return UploadInputGuard.sanitizeFileName(originalFileName);
    }

    private static String calculateSha256(MultipartFile file) {
        try {
            return FileHasher.sha256Hex(file.getBytes());
        } catch (IOException exception) {
            throw new BusinessException(ErrorCodes.UPLOAD_INCOMPLETE, "读取上传文件失败");
        }
    }

    private void uploadDirectFile(String bucket, String objectKey, MultipartFile file) {
        try (java.io.InputStream inputStream = file.getInputStream()) {
            fileStore.putDirect(bucket, objectKey, inputStream, file.getSize(),
                    UploadInputGuard.normalizeContentType(file.getContentType()));
        } catch (java.io.IOException exception) {
            throw new BusinessException(ErrorCodes.UPLOAD_INCOMPLETE, "读取上传文件失败");
        } catch (BusinessException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new BusinessException(ErrorCodes.UPLOAD_INCOMPLETE, "文档上传失败");
        }
    }

    private void compensateExternalIndexes(DocumentEntity document) {
        finalizer.compensateExternalIndexes(document);
    }

    private DocumentEntity persistAndFinalizeUploadedDocument(FinalizedUploadCommand command) {
        return finalizer.finalizeUploaded(new UploadedDocumentFinalizer.NewFileInput(
                command.groupId(), command.userId(), command.fileName(), command.fileExt(),
                command.contentType(), command.fileSize(), command.fileHash(),
                command.bucket(), command.objectKey()));
    }

    record FinalizedUploadCommand(
            Long groupId,
            Long userId,
            String fileName,
            String fileExt,
            String contentType,
            Long fileSize,
            String fileHash,
            String bucket,
            String objectKey
    ) {
    }
}
