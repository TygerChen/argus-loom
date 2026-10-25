package com.argus.rag.qa.service;

import com.argus.rag.auth.CurrentUserService;
import com.argus.rag.common.enums.SystemRole;
import com.argus.rag.document.mapper.DocumentMapper;
import com.argus.rag.document.model.entity.DocumentEntity;
import com.argus.rag.qa.mapper.QaRecordCitationMapper;
import com.argus.rag.qa.mapper.QaRecordMapper;
import com.argus.rag.qa.model.entity.QaRecordCitationEntity;
import com.argus.rag.qa.model.entity.QaRecordEntity;
import com.argus.rag.qa.model.vo.AskQuestionResponse;
import com.argus.rag.qa.model.vo.QaRecordDetailVO;
import com.argus.rag.qa.support.EvidenceOverviewAssembler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("QA record query service")
class QaRecordQueryServiceTest {

    @Mock
    private CurrentUserService currentUserService;

    @Mock
    private QaRecordMapper qaRecordMapper;

    @Mock
    private QaRecordCitationMapper qaRecordCitationMapper;

    @Mock
    private DocumentMapper documentMapper;

    @Test
    @DisplayName("should return evidence overview when loading history detail")
    void shouldReturnEvidenceOverviewWhenLoadingHistoryDetail() {
        when(currentUserService.getRequiredCurrentUser()).thenReturn(
                new CurrentUserService.CurrentUser(5L, "u001", "Alice", SystemRole.USER, false));
        when(qaRecordMapper.selectVisibleRecord(77L, 5L, false)).thenReturn(record());
        when(qaRecordCitationMapper.selectByRecordId(77L)).thenReturn(List.of(
                citation(10L, 101L, 0, "product.md", 0.91D, "BOTH", "product goals"),
                citation(20L, 201L, 1, "deploy.md", 0.84D, "KEYWORD", "deploy steps")
        ));
        QaRecordQueryService service = new QaRecordQueryService(
                currentUserService,
                qaRecordMapper,
                qaRecordCitationMapper,
                documentMapper,
                new com.argus.rag.document.security.DocumentRoleGate(null),
                new EvidenceOverviewAssembler());

        QaRecordDetailVO detail = service.getDetail(77L);

        AskQuestionResponse.EvidenceOverview overview = detail.evidenceOverview();
        assertThat(overview).isNotNull();
        assertThat(overview.documentCount()).isEqualTo(2);
        assertThat(overview.evidenceCount()).isEqualTo(2);
        assertThat(overview.coverageMode()).isEqualTo("HISTORY_SNAPSHOT");
        assertThat(overview.groups()).extracting(AskQuestionResponse.DocumentEvidenceGroup::fileName)
                .containsExactly("product.md", "deploy.md");
        assertThat(overview.groups().getFirst().snippets())
                .extracting(AskQuestionResponse.EvidenceSnippet::chunkId)
                .containsExactly(101L);
    }

    @Test
    @DisplayName("should delete visible history record and citation snapshots")
    void shouldDeleteVisibleHistoryRecordAndCitationSnapshots() {
        when(currentUserService.getRequiredCurrentUser()).thenReturn(
                new CurrentUserService.CurrentUser(5L, "u001", "Alice", SystemRole.USER, false));
        when(qaRecordMapper.selectVisibleRecord(77L, 5L, false)).thenReturn(record());
        QaRecordQueryService service = new QaRecordQueryService(
                currentUserService,
                qaRecordMapper,
                qaRecordCitationMapper,
                documentMapper,
                new com.argus.rag.document.security.DocumentRoleGate(null),
                new EvidenceOverviewAssembler());

        service.delete(77L);

        verify(qaRecordCitationMapper).deleteByRecordId(77L);
        verify(qaRecordMapper).deleteById(77L);
    }

    @Test
    @org.junit.jupiter.api.DisplayName("history citations to restricted documents are redacted for regular users")
    @MockitoSettings(strictness = Strictness.LENIENT)
    void restrictedCitationsAreRedactedForRegularUsers() {
        when(currentUserService.getRequiredCurrentUser()).thenReturn(
                new CurrentUserService.CurrentUser(5L, "u001", "Alice", SystemRole.USER, false));
        when(qaRecordMapper.selectVisibleRecord(77L, 5L, false)).thenReturn(record());
        when(documentMapper.selectBatchIds(org.mockito.ArgumentMatchers.anyCollection())).thenReturn(List.of(
                doc(10L, "USER"),
                doc(20L, "FINANCE")
        ));
        when(qaRecordCitationMapper.selectByRecordId(77L)).thenReturn(List.of(
                citation(10L, 101L, 0, "product.md", 0.91D, "BOTH", "product goals and roadmap"),
                citation(20L, 201L, 1, "finance-payroll.md", 0.84D, "KEYWORD", "payroll = 88888 yuan secret")
        ));
        QaRecordQueryService service = new QaRecordQueryService(
                currentUserService,
                qaRecordMapper,
                qaRecordCitationMapper,
                documentMapper,
                new com.argus.rag.document.security.DocumentRoleGate(null),
                new EvidenceOverviewAssembler());

        QaRecordDetailVO detail = service.getDetail(77L);

        QaRecordDetailVO.Citation readable = detail.citations().get(0);
        assertThat(readable.snippet()).isEqualTo("product goals and roadmap");
        QaRecordDetailVO.Citation restricted = detail.citations().get(1);
        assertThat(restricted.snippet()).doesNotContain("88888");
        assertThat(String.valueOf(restricted.snippet())).contains("受限");
        assertThat(detail.evidenceOverview().groups().get(1).snippets().get(0).snippet())
                .doesNotContain("88888");
    }

    @Test
    @org.junit.jupiter.api.DisplayName("admins see full citation snippets without redaction")
    @MockitoSettings(strictness = Strictness.LENIENT)
    void adminsSeeFullCitations() {
        when(currentUserService.getRequiredCurrentUser()).thenReturn(
                new CurrentUserService.CurrentUser(1L, "admin", "Admin", SystemRole.ADMIN, false));
        when(qaRecordMapper.selectVisibleRecord(77L, 1L, true)).thenReturn(record());
        when(qaRecordCitationMapper.selectByRecordId(77L)).thenReturn(List.of(
                citation(20L, 201L, 1, "finance-payroll.md", 0.84D, "KEYWORD", "payroll = 88888 yuan secret")
        ));
        QaRecordQueryService service = new QaRecordQueryService(
                currentUserService,
                qaRecordMapper,
                qaRecordCitationMapper,
                documentMapper,
                new com.argus.rag.document.security.DocumentRoleGate(null),
                new EvidenceOverviewAssembler());

        QaRecordDetailVO detail = service.getDetail(77L);

        assertThat(detail.citations().get(0).snippet()).contains("88888");
    }

    private DocumentEntity doc(Long id, String requiredRole) {
        DocumentEntity entity = new DocumentEntity();
        entity.setId(id);
        entity.setRequiredRole(requiredRole);
        return entity;
    }

    private QaRecordEntity record() {
        QaRecordEntity record = new QaRecordEntity();
        record.setId(77L);
        record.setUserId(5L);
        record.setGroupId(1L);
        record.setQuestion("summarize all docs");
        record.setAnswer("summary");
        record.setAnswered(true);
        record.setCitationCount(2);
        record.setEndpoint("qa/ask");
        record.setSuccess(true);
        record.setCreatedAt(LocalDateTime.now());
        return record;
    }

    private QaRecordCitationEntity citation(
            Long documentId,
            Long chunkId,
            Integer chunkIndex,
            String fileName,
            Double score,
            String retrievalSource,
            String snippet) {
        QaRecordCitationEntity citation = new QaRecordCitationEntity();
        citation.setDocumentId(documentId);
        citation.setChunkId(chunkId);
        citation.setChunkIndex(chunkIndex);
        citation.setStartChunkIndex(chunkIndex);
        citation.setEndChunkIndex(chunkIndex);
        citation.setFileName(fileName);
        citation.setScore(score);
        citation.setRetrievalSource(retrievalSource);
        citation.setSnippet(snippet);
        return citation;
    }
}
