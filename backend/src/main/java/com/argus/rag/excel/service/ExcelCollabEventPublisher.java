package com.argus.rag.excel.service;

import com.argus.rag.excel.entity.ExcelCollabOutbox;
import com.argus.rag.excel.mapper.ExcelCollabOutboxMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * 协同事件发布器（任务 9）。
 *
 * <p>定时清扫 Outbox 中 PENDING 事件，逐条广播到 {@code /topic/excel/{documentId}}
 * 并标记 SENT。单条发送失败只影响该条（保持 PENDING 等待下次重发），
 * 不会中断同批其他事件的发布。</p>
 */
@Service
public class ExcelCollabEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(ExcelCollabEventPublisher.class);

    /** 每轮清扫最多处理的事件数，避免一次广播风暴阻塞调度线程。 */
    private static final int BATCH_LIMIT = 50;

    /** 负载只做 JSON 字符串编解码，无需随环境定制，用类内共享实例即可。 */
    private static final ObjectMapper JSON = new ObjectMapper();

    private final ExcelCollabOutboxMapper outboxMapper;
    private final SimpMessagingTemplate messagingTemplate;

    public ExcelCollabEventPublisher(ExcelCollabOutboxMapper outboxMapper,
                                     SimpMessagingTemplate messagingTemplate) {
        this.outboxMapper = outboxMapper;
        this.messagingTemplate = messagingTemplate;
    }

    /** 定时清扫：广播待发送事件（提交后异步、失败可重发）。 */
    @Scheduled(fixedDelayString = "${argus.collab.outbox-publish-interval-ms:500}")
    public void publishPendingEvents() {
        for (ExcelCollabOutbox event : outboxMapper.selectPending(BATCH_LIMIT)) {
            try {
                messagingTemplate.convertAndSend("/topic/excel/" + event.getDocumentId(), toPayload(event));
                event.setStatus(ExcelCollabOutbox.STATUS_SENT);
                outboxMapper.updateById(event);
            } catch (Exception e) {
                log.warn("Outbox 事件广播失败，保持 PENDING 等待重发: id={}, error={}",
                        event.getId(), e.getMessage());
            }
        }
    }

    /** 把存储的 JSON 负载还原为广播 Map；解析失败降级为只带类型的最小消息。 */
    private Map<String, Object> toPayload(ExcelCollabOutbox event) {
        try {
            return JSON.readValue(event.getPayloadJson(), Map.class);
        } catch (Exception e) {
            log.error("Outbox 负载解析失败: id={}, error={}", event.getId(), e.getMessage());
            return Map.of("type", event.getEventType() == null ? "" : event.getEventType());
        }
    }
}
