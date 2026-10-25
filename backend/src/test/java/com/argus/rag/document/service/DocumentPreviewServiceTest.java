package com.argus.rag.document.service;

import com.argus.rag.common.enums.DocumentStatus;
import com.argus.rag.common.enums.SystemRole;
import com.argus.rag.common.exception.BusinessException;
import com.argus.rag.common.exception.ErrorCodes;
import com.argus.rag.document.mapper.DocumentMapper;
import com.argus.rag.document.model.entity.DocumentEntity;
import com.argus.rag.document.model.vo.DocumentPreviewVO;
import com.argus.rag.document.security.DocumentRoleGate;
import com.argus.rag.engine.storage.ObjectStorageService;
import com.argus.rag.group.service.GroupMembershipService;
import com.argus.rag.ingestion.service.pipeline.parser.DocumentParserFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link DocumentPreviewService} 预览契约：
 * 只锁 service 在 roleGate 返回值上表现出的输出/异常分支，mock 放行判定实现（不触碰内部判定逻辑）；
 * 侧重纯校验（非法 groupId/文档ID、不存在、非 READY、门槛拒绝 → 文档不存在同等隐瞒）与 md 原始内容直读、
 * 无文本报错。非 md 解析器管道（依赖 {@code StoredObjectDocumentReader}/对象存储解析）同 FinancePdfRenderer
 * 先例不做单测。
 */
@DisplayName("文档预览（DocumentPreviewService）")
class DocumentPreviewServiceTest {

    private DocumentMapper documentMapper;
    private GroupMembershipService groupMembershipService;
    private ObjectStorageService storageService;
    private DocumentParserFactory parserFactory;
    private DocumentRoleGate roleGate;
    private DocumentPreviewService service;

    @BeforeEach
    void setUp() {
        documentMapper = mock(DocumentMapper.class);
        groupMembershipService = mock(GroupMembershipService.class);
        storageService = mock(ObjectStorageService.class);
        parserFactory = mock(DocumentParserFactory.class);
        roleGate = mock(DocumentRoleGate.class);
        when(roleGate.currentRole()).thenReturn(SystemRole.USER);
        service = new DocumentPreviewService(documentMapper, groupMembershipService,
                storageService, parserFactory, roleGate);
    }

    /** 构造一份 md 实体，用于 raw 原文路径 */
    private DocumentEntity mdDocument(Long id, String status) {
        DocumentEntity doc = new DocumentEntity();
        doc.setId(id);
        doc.setStatus(status);
        doc.setFileName("a.md");
        doc.setFileExt("md");
        doc.setStorageBucket("bkt");
        doc.setStorageObjectKey("obj");
        doc.setRequiredRole(null);
        return doc;
    }

    private DocumentEntity readyDocument() {
        return mdDocument(1L, DocumentStatus.READY.name());
    }

    @Test
    void previewRejectsIllegalGroupId() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.previewDocument(7L, 0L, 1L));
        assertEquals("groupId 非法", ex.getMessage());
    }

    @Test
    void previewRejectsIllegalDocumentId() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.previewDocument(7L, 1L, 0L));
        assertEquals("文档ID非法", ex.getMessage());
    }

    @Test
    void previewRejectsMissingDocument() {
        when(documentMapper.selectByIdAndGroupId(1L, 1L)).thenReturn(null);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.previewDocument(7L, 1L, 1L));
        assertEquals(ErrorCodes.DOCUMENT_NOT_FOUND, ex.getCode());
    }

    @Test
    void previewRejectsNotReady() {
        when(documentMapper.selectByIdAndGroupId(1L, 1L))
                .thenReturn(mdDocument(1L, DocumentStatus.PROCESSING.name()));
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.previewDocument(7L, 1L, 1L));
        assertEquals(ErrorCodes.DOCUMENT_NOT_READY, ex.getCode());
    }

    @Test
    void previewRejectsWhenRoleGateDeniesRead() {
        when(documentMapper.selectByIdAndGroupId(1L, 1L)).thenReturn(readyDocument());
        when(roleGate.canRead(any(), any())).thenReturn(false);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.previewDocument(7L, 1L, 1L));
        // 读侧未达门槛与文档不存在同等对待，防存在性泄露
        assertEquals(ErrorCodes.DOCUMENT_NOT_FOUND, ex.getCode());
    }

    @Test
    void previewMdReturnsRawSource() throws IOException {
        when(documentMapper.selectByIdAndGroupId(1L, 1L)).thenReturn(readyDocument());
        when(roleGate.canRead(any(), any())).thenReturn(true);
        String raw = "# 标题\n\n**加粗**\n- 列表项";
        when(storageService.getObject("bkt", "obj")).thenReturn(
                new ByteArrayInputStream(raw.getBytes(StandardCharsets.UTF_8)));

        DocumentPreviewVO preview = service.previewDocument(7L, 1L, 1L);

        // 保留原始 Markdown，不做任何语法剥离
        assertEquals(raw, preview.getPreviewText());
        assertEquals("a.md", preview.getFileName());
        assertEquals(1L, preview.getDocumentId());
    }

    @Test
    void previewUsesDocumentBucketWhenProvided() throws IOException {
        when(documentMapper.selectByIdAndGroupId(1L, 1L)).thenReturn(readyDocument());
        when(roleGate.canRead(any(), any())).thenReturn(true);
        when(storageService.getObject("bkt", "obj"))
                .thenReturn(new ByteArrayInputStream("内容".getBytes(StandardCharsets.UTF_8)));

        service.previewDocument(7L, 1L, 1L);

        // 显式 bucket 命中，不需回退默认桶
        when(roleGate.canRead(any(), any())).thenReturn(true);
        org.mockito.Mockito.verify(storageService).getObject("bkt", "obj");
    }

    @Test
    void previewRejectsBlankText() throws IOException {
        when(documentMapper.selectByIdAndGroupId(1L, 1L)).thenReturn(readyDocument());
        when(roleGate.canRead(any(), any())).thenReturn(true);
        // md 原始文件读回为空 → 解析后无文本 → 明确失败
        when(storageService.getObject("bkt", "obj")).thenReturn(
                new ByteArrayInputStream(new byte[0]));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.previewDocument(7L, 1L, 1L));
        assertEquals(ErrorCodes.DOCUMENT_PROCESSING_FAILED, ex.getCode());
    }
}