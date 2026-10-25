package com.argus.rag.excel.service;

import com.argus.rag.excel.entity.ExcelCollabOutbox;
import com.argus.rag.excel.mapper.ExcelCollabOutboxMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * 协同事件 Outbox 写入服务（任务 9）。
 *
 * <p>只负责在业务成功路径上落一行 PENDING 事件，绝不直接广播；
 * 广播由 {@link ExcelCollabEventPublisher} 在其后的发布周期完成，
 * 保证"先持久化、后广播"，广播失败也能靠清扫重发补偿。</p>
 */
@Service
public class ExcelCollabOutboxService {

    private static final Logger log = LoggerFactory.getLogger(ExcelCollabOutboxService.class);

    /** 负载只做 JSON 字符串编解码，无需随环境定制，用类内共享实例即可。 */
    private static final ObjectMapper JSON = new ObjectMapper();

    private final ExcelCollabOutboxMapper outboxMapper;

    public ExcelCollabOutboxService(ExcelCollabOutboxMapper outboxMapper) {
        this.outboxMapper = outboxMapper;
    }

    /**
     * 记录一条待广播事件。
     *
     * @param documentId 事件所属文档
     * @param eventType  事件类型（如 CELL_EDIT）
     * @param payload    广播负载（Map 形式，序列化为 JSON 存储）
     */
    public void record(Long documentId, String eventType, Map<String, Object> payload) {
        ExcelCollabOutbox event = new ExcelCollabOutbox();
        event.setDocumentId(documentId);
        event.setEventType(eventType);
        event.setPayloadJson(toJson(payload));
        event.setStatus(ExcelCollabOutbox.STATUS_PENDING);
        outboxMapper.insert(event);
    }

    /** 负载序列化失败不应阻断业务主流程：降级为空对象 JSON，由广播端补齐基本字段。 */
    private String toJson(Map<String, Object> payload) {
        try {
            return JSON.writeValueAsString(payload);
        } catch (Exception e) {
            log.error("Outbox 负载序列化失败: {}", e.getMessage());
            return "{}";
        }
    }
}
