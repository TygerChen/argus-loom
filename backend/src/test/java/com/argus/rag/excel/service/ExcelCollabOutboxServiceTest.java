package com.argus.rag.excel.service;

import com.argus.rag.excel.entity.ExcelCollabOutbox;
import com.argus.rag.excel.mapper.ExcelCollabOutboxMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 协同事件 Outbox 契约（任务 9）。
 * <p>事务内只落 Outbox；提交后由发布器广播并标记 SENT；发送失败保留 PENDING 由清扫重发。</p>
 */
@DisplayName("协同事件 Outbox（ExcelCollabOutboxService）")
class ExcelCollabOutboxServiceTest {

    private ExcelCollabOutboxMapper outboxMapper;
    private SimpMessagingTemplate messagingTemplate;
    private ExcelCollabEventPublisher publisher;
    private ExcelCollabOutboxService service;

    @BeforeEach
    void setUp() {
        outboxMapper = mock(ExcelCollabOutboxMapper.class);
        messagingTemplate = mock(SimpMessagingTemplate.class);
        publisher = new ExcelCollabEventPublisher(outboxMapper, messagingTemplate);
        service = new ExcelCollabOutboxService(outboxMapper);
    }

    @Test
    @DisplayName("record 只落 Outbox 行，不直接广播")
    void recordOnlyPersistsOutboxRow() {
        service.record(10L, "CELL_EDIT", Map.of("r", 1, "c", 2));

        ArgumentCaptor<ExcelCollabOutbox> cap = ArgumentCaptor.forClass(ExcelCollabOutbox.class);
        verify(outboxMapper).insert(cap.capture());
        assertThat(cap.getValue().getDocumentId()).isEqualTo(10L);
        assertThat(cap.getValue().getStatus()).isEqualTo("PENDING");
        assertThat(cap.getValue().getEventType()).isEqualTo("CELL_EDIT");
        verify(messagingTemplate, never()).convertAndSend(any(String.class), any(Object.class));
    }

    @Test
    @DisplayName("发布器：提交后发送 PENDING 事件并标记 SENT")
    void publisherSendsPendingAndMarksSent() {
        ExcelCollabOutbox pending = new ExcelCollabOutbox();
        pending.setId(1L);
        pending.setDocumentId(10L);
        pending.setEventType("CELL_EDIT");
        pending.setPayloadJson("{\"r\":1}");
        pending.setStatus("PENDING");
        when(outboxMapper.selectPending(50)).thenReturn(List.of(pending));

        publisher.publishPendingEvents();

        verify(messagingTemplate).convertAndSend(eq("/topic/excel/10"), any(Map.class));
        ArgumentCaptor<ExcelCollabOutbox> cap = ArgumentCaptor.forClass(ExcelCollabOutbox.class);
        verify(outboxMapper).updateById(cap.capture());
        assertThat(cap.getValue().getStatus()).isEqualTo("SENT");
    }

    @Test
    @DisplayName("发送失败的事件保持 PENDING，等待下次清扫重发")
    void failedSendStaysPending() {
        ExcelCollabOutbox pending = new ExcelCollabOutbox();
        pending.setId(2L);
        pending.setDocumentId(11L);
        pending.setEventType("CELL_EDIT");
        pending.setPayloadJson("{}");
        pending.setStatus("PENDING");
        when(outboxMapper.selectPending(50)).thenReturn(List.of(pending));
        org.mockito.Mockito.doThrow(new RuntimeException("broker down"))
                .when(messagingTemplate).convertAndSend(any(String.class), any(Object.class));

        publisher.publishPendingEvents();

        verify(outboxMapper, never()).updateById(any(ExcelCollabOutbox.class));
    }
}
