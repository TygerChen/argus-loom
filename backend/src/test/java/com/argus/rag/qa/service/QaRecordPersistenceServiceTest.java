package com.argus.rag.qa.service;

import com.argus.rag.qa.mapper.QaRecordCitationMapper;
import com.argus.rag.qa.mapper.QaRecordMapper;
import com.argus.rag.qa.model.EvidenceLevel;
import com.argus.rag.qa.model.entity.QaRecordCitationEntity;
import com.argus.rag.qa.model.entity.QaRecordEntity;
import com.argus.rag.qa.model.vo.AskQuestionResponse;
import com.argus.rag.auth.CurrentUserService;
import com.argus.rag.common.enums.SystemRole;
import com.argus.rag.common.exception.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.document.Document;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static java.util.Map.entry;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("QA 问答记录持久化服务")
class QaRecordPersistenceServiceTest {

    @Mock
    private QaRecordMapper qaRecordMapper;

    @Mock
    private QaRecordCitationMapper qaRecordCitationMapper;

    @Mock
    private com.argus.rag.auth.CurrentUserService currentUserService;

    @Test
    @DisplayName("应保存成功回答和原始证据引用快照")
    void shouldPersistAnsweredRecordWithRawEvidenceCitations() {
        when(qaRecordMapper.insert(any(QaRecordEntity.class))).thenAnswer(invocation -> {
            QaRecordEntity entity = invocation.getArgument(0);
            entity.setId(77L);
            return 1;
        });

        QaRecordPersistenceService service = new QaRecordPersistenceService(
                qaRecordMapper, qaRecordCitationMapper, currentUserService);
        AskQuestionResponse response = AskQuestionResponse.answered(
                "上传流程分为初始化、上传分片和完成合并。",
                List.of(new AskQuestionResponse.Citation(10L, 100L, 2, "guide.pdf", 0.88D, null))
        );
        Document evidence = Document.builder()
                .id("E1")
                .text("文件名：guide.pdf\n上传流程分为初始化、上传分片和完成合并。")
                .metadata(Map.ofEntries(
                        entry("documentId", 10L),
                        entry("chunkId", 100L),
                        entry("chunkIndex", 2),
                        entry("fileName", "guide.pdf"),
                        entry("score", 0.88D),
                        entry("retrievalSource", "BOTH"),
                        entry("vectorScore", 0.61D),
                        entry("keywordScore", 0.73D),
                        entry("hybridScore", 1.25D),
                        entry("startChunkIndex", 1),
                        entry("endChunkIndex", 3)
                ))
                .build();

        Long recordId = service.saveCompleted(new QaRecordPersistenceService.SaveCommand(
                5L,
                1L,
                "文档上传流程是什么？",
                "qa/ask",
                "qwen-plus",
                response,
                EvidenceLevel.SUFFICIENT,
                new QaChatService.UsageInfo(100, 40, 140, false, 1234L),
                List.of(evidence),
                true,
                null
        ));

        assertThat(recordId).isEqualTo(77L);

        ArgumentCaptor<QaRecordEntity> recordCaptor = ArgumentCaptor.forClass(QaRecordEntity.class);
        verify(qaRecordMapper).insert(recordCaptor.capture());
        QaRecordEntity record = recordCaptor.getValue();
        assertThat(record.getUserId()).isEqualTo(5L);
        assertThat(record.getGroupId()).isEqualTo(1L);
        assertThat(record.getQuestion()).isEqualTo("文档上传流程是什么？");
        assertThat(record.getAnswer()).isEqualTo("上传流程分为初始化、上传分片和完成合并。");
        assertThat(record.getAnswered()).isTrue();
        assertThat(record.getEvidenceLevel()).isEqualTo(EvidenceLevel.SUFFICIENT.name());
        assertThat(record.getCitationCount()).isEqualTo(1);
        assertThat(record.getPromptTokens()).isEqualTo(100);
        assertThat(record.getCompletionTokens()).isEqualTo(40);
        assertThat(record.getTotalTokens()).isEqualTo(140);
        assertThat(record.getLatencyMs()).isEqualTo(1234L);

        ArgumentCaptor<QaRecordCitationEntity> citationCaptor = ArgumentCaptor.forClass(QaRecordCitationEntity.class);
        verify(qaRecordCitationMapper).insert(citationCaptor.capture());
        QaRecordCitationEntity citation = citationCaptor.getValue();
        assertThat(citation.getQaRecordId()).isEqualTo(77L);
        assertThat(citation.getDocumentId()).isEqualTo(10L);
        assertThat(citation.getChunkId()).isEqualTo(100L);
        assertThat(citation.getChunkIndex()).isEqualTo(2);
        assertThat(citation.getFileName()).isEqualTo("guide.pdf");
        assertThat(citation.getScore()).isEqualTo(0.88D);
        assertThat(citation.getRetrievalSource()).isEqualTo("BOTH");
        assertThat(citation.getVectorScore()).isEqualTo(0.61D);
        assertThat(citation.getKeywordScore()).isEqualTo(0.73D);
        assertThat(citation.getHybridScore()).isEqualTo(1.25D);
        assertThat(citation.getStartChunkIndex()).isEqualTo(1);
        assertThat(citation.getEndChunkIndex()).isEqualTo(3);
        assertThat(citation.getSnippet()).contains("上传流程");
    }

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("评分只能作用于可见记录：他人记录被拒绝")
    void ratingRestrictedToVisibleRecord() {
        var viewer = new CurrentUserService.CurrentUser(7L, "U7", "员工", SystemRole.USER, false);
        when(currentUserService.getRequiredCurrentUser()).thenReturn(viewer);
        // selectVisibleRecord 返回 null：模拟该记录不属于当前用户/群组。
        when(qaRecordMapper.selectVisibleRecord(9L, 7L, false)).thenReturn(null);

        QaRecordPersistenceService service = new QaRecordPersistenceService(
                qaRecordMapper, qaRecordCitationMapper, currentUserService);

        org.junit.jupiter.api.Assertions.assertThrows(BusinessException.class,
                () -> service.updateRating(9L, "THUMBS_UP"));
        verify(qaRecordMapper, never()).updateById(any(QaRecordEntity.class));
    }

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("可见记录可以正常评分")
    void ratingAppliesToVisibleRecord() {
        var viewer = new CurrentUserService.CurrentUser(7L, "U7", "员工", SystemRole.USER, false);
        when(currentUserService.getRequiredCurrentUser()).thenReturn(viewer);
        QaRecordEntity record = new QaRecordEntity();
        record.setId(9L);
        when(qaRecordMapper.selectVisibleRecord(9L, 7L, false)).thenReturn(record);
        when(qaRecordMapper.updateById(any(QaRecordEntity.class))).thenReturn(1);

        QaRecordPersistenceService service = new QaRecordPersistenceService(
                qaRecordMapper, qaRecordCitationMapper, currentUserService);
        service.updateRating(9L, "THUMBS_UP");

        org.junit.jupiter.api.Assertions.assertEquals("THUMBS_UP", record.getUserRating());
        verify(qaRecordMapper).updateById(record);
    }
}
