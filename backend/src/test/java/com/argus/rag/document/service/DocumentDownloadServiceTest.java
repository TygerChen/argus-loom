package com.argus.rag.document.service;

import com.argus.rag.common.enums.DocumentStatus;
import com.argus.rag.common.enums.SystemRole;
import com.argus.rag.common.exception.BusinessException;
import com.argus.rag.common.exception.ErrorCodes;
import com.argus.rag.document.mapper.DocumentMapper;
import com.argus.rag.document.model.entity.DocumentEntity;
import com.argus.rag.document.model.vo.DocumentDownloadVO;
import com.argus.rag.document.security.DocumentRoleGate;
import com.argus.rag.engine.storage.ObjectStorageService;
import com.argus.rag.group.service.GroupMembershipService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link DocumentDownloadService} 下载契约（roleGate 仅 mock 放行，不触碰判定实现）：
 * - 幂守护：非法 groupId / 非法文档ID / 文档不存在；
 * - 读侧门槛与"文档不存在"同等隐瞒（{@code DOCUMENT_NOT_FOUND}）；
 * - 仅 READY 可下载、存储信息缺失 → {@code DOCUMENT_NOT_READY}；
 * - 成功取流并携带 fileName/contentType/fileSize 元数据。
 * 无 DB / 无字体，roleGate 判定实现不触碰。
 */
@DisplayName("文档下载（DocumentDownloadService）")
class DocumentDownloadServiceTest {

    private DocumentMapper documentMapper;
    private GroupMembershipService groupMembershipService;
    private ObjectStorageService objectStorageService;
    private DocumentRoleGate roleGate;
    private DocumentDownloadService service;

    @BeforeEach
    void setUp() {
        documentMapper = mock(DocumentMapper.class);
        groupMembershipService = mock(GroupMembershipService.class);
        objectStorageService = mock(ObjectStorageService.class);
        roleGate = mock(DocumentRoleGate.class);
        when(roleGate.currentRole()).thenReturn(SystemRole.USER);
        when(roleGate.canRead(any(), any())).thenReturn(true); // 放行，不触判定实现
        service = new DocumentDownloadService(documentMapper, groupMembershipService,
                objectStorageService, roleGate);
    }

    private DocumentEntity readyDocument() {
        DocumentEntity doc = new DocumentEntity();
        doc.setId(1L);
        doc.setGroupId(1L);
        doc.setStatus(DocumentStatus.READY.name());
        doc.setFileName("报告.pdf");
        doc.setContentType("application/pdf");
        doc.setFileSize(2048L);
        doc.setStorageBucket("bkt");
        doc.setStorageObjectKey("obj");
        doc.setRequiredRole(null);
        return doc;
    }

    @Test
    void downloadRejectsIllegalGroupId() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.downloadDocument(7L, 0L, 1L));
        assertEquals("groupId 非法", ex.getMessage());
    }

    @Test
    void downloadRejectsIllegalDocumentId() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.downloadDocument(7L, 1L, 0L));
        assertEquals("文档ID非法", ex.getMessage());
    }

    @Test
    void downloadRejectsMissingDocument() {
        when(documentMapper.selectByIdAndGroupId(1L, 1L)).thenReturn(null);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.downloadDocument(7L, 1L, 1L));
        assertEquals(ErrorCodes.DOCUMENT_NOT_FOUND, ex.getCode());
        assertEquals("文档不存在或已删除", ex.getMessage());
    }

    @Test
    void downloadHidesExistenceWhenRoleGateDenies() {
        when(documentMapper.selectByIdAndGroupId(1L, 1L)).thenReturn(readyDocument());
        when(roleGate.canRead(any(), any())).thenReturn(false);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.downloadDocument(7L, 1L, 1L));
        // 读侧未达门槛与文档不存在同等对待，防存在性泄露
        assertEquals(ErrorCodes.DOCUMENT_NOT_FOUND, ex.getCode());
    }

    @Test
    void downloadRejectsNotReady() {
        DocumentEntity doc = readyDocument();
        doc.setStatus(DocumentStatus.PROCESSING.name());
        when(documentMapper.selectByIdAndGroupId(1L, 1L)).thenReturn(doc);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.downloadDocument(7L, 1L, 1L));
        assertEquals(ErrorCodes.DOCUMENT_NOT_READY, ex.getCode());
        assertEquals("文档尚未就绪，暂不可下载", ex.getMessage());
    }

    @Test
    void downloadRejectsMissingStorageInfo() {
        DocumentEntity doc = readyDocument();
        doc.setStorageObjectKey(null);
        when(documentMapper.selectByIdAndGroupId(1L, 1L)).thenReturn(doc);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.downloadDocument(7L, 1L, 1L));
        assertEquals(ErrorCodes.DOCUMENT_NOT_READY, ex.getCode());
        assertEquals("文档存储信息缺失", ex.getMessage());
    }

    @Test
    void downloadReturnsStreamAndMetadata() {
        when(documentMapper.selectByIdAndGroupId(1L, 1L)).thenReturn(readyDocument());
        InputStream stream = new ByteArrayInputStream("内容".getBytes(StandardCharsets.UTF_8));
        when(objectStorageService.getObject("bkt", "obj")).thenReturn(stream);

        DocumentDownloadVO vo = service.downloadDocument(7L, 1L, 1L);

        assertSame(stream, vo.getInputStream());
        assertEquals("报告.pdf", vo.getFileName());
        assertEquals("application/pdf", vo.getContentType());
        assertEquals(2048L, vo.getFileSize());
        verify(objectStorageService).getObject("bkt", "obj");
    }
}