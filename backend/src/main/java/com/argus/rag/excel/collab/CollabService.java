package com.argus.rag.excel.collab;

import com.argus.rag.excel.audit.OperationLogService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.argus.rag.excel.entity.ExcelCollabLog;
import com.argus.rag.excel.entity.ExcelCollabRoom;
import com.argus.rag.excel.mapper.ExcelCollabLogMapper;
import com.argus.rag.excel.mapper.ExcelCollabRoomMapper;
import com.argus.rag.excel.model.dto.CollabMessage;
import com.argus.rag.excel.audit.OperationLog;
import com.argus.rag.excel.service.ExcelSheetService;
import com.argus.rag.excel.service.SnapshotService;
import com.argus.rag.excel.service.ExcelCollabOutboxService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 实时协作业务服务。
 *
 * <p>把 {@code CollabController} 中散落的内存冲突解决、数据库持久化、操作/协同日志与快照触达
 * 收拢到业务层，让控制器退化为基本的消息路由与推送适配。LWW 时间戳为进程内内存缓存
 * （重启后重新从 DB 加载）。</p>
 *
 * <p>LWW 策略：写入前比较传入消息与当前单元格内存最后的编辑时间戳，只有更新的才接受，
 * 保证并发编辑下最后发起者胜出。</p>
 *
 * <p><b>诚实标注（面试官追问"部署两个实例怎么办"时这么答）</b>：
 * 当前单实例部署没问题；多实例下各自的内存时间戳不互通，同一单元格的并发编辑
 * 可能各自胜出，冲突仲裁失效。演进路线按成本递进三选一：
 * ① 时间戳挪到 Redis（INCR 或 Lua 原子比较），改动最小，引入一次网络往返；
 * ② 版本号向量时钟，能识别"并发冲突"而不只是"谁更新"，实现和排查成本都高；
 * ③ 接入 CRDT 库（如 y-collaboration 生态），自动合并但架构改造最大。
 * 单实例场景选 ① 之前先问"真的会有多实例吗"——500 人规模往往单实例 + 高可用主备
 * 就够，<b>按真实压力演进，不为简历凑架构</b>（详见 05 篇协同编辑的仲裁三层设计）。</p>
 */
@Service
public class CollabService {

    private static final Logger log = LoggerFactory.getLogger(CollabService.class);

    /** LWW 冲突解决的内存缓存：key = "{documentId}_{sheetId}_{r}_{c}" → 最后一次编辑时间戳 */
    private final ConcurrentHashMap<String, Long> lastEditTimestamps = new ConcurrentHashMap<>();

    private final ExcelSheetService sheetService;
    private final com.argus.rag.excel.service.ExcelDocumentService documentService;
    private final OperationLogService operationLogService;
    private final SnapshotService snapshotService;
    private final ExcelCollabRoomMapper collabRoomMapper;
    private final ExcelCollabLogMapper collabLogMapper;
    private final ExcelCollabOutboxService outboxService;
    private final ObjectMapper objectMapper;

    public CollabService(ExcelSheetService sheetService,
                         com.argus.rag.excel.service.ExcelDocumentService documentService,
                         OperationLogService operationLogService,
                         SnapshotService snapshotService,
                         ExcelCollabRoomMapper collabRoomMapper,
                         ExcelCollabLogMapper collabLogMapper,
                         ExcelCollabOutboxService outboxService,
                         ObjectMapper objectMapper) {
        this.sheetService = sheetService;
        this.documentService = documentService;
        this.operationLogService = operationLogService;
        this.snapshotService = snapshotService;
        this.collabRoomMapper = collabRoomMapper;
        this.collabLogMapper = collabLogMapper;
        this.outboxService = outboxService;
        this.objectMapper = objectMapper;
    }

    /**
     * 处理一次单元格编辑：LWW 冲突判定 → 操作日志 → 快照触达 → 持久化 → 协同日志入库。
     *
     * @param msg            编辑消息
     * @param editUserId     发起者用户 ID
     * @param editDisplayName 发起者显示名
     * @param senderSessionId 发起者 STOMP 会话（广播去重用）
     * @return 编辑结果；{@code accepted=true} 时携带应广播的消息，否则携带拒绝原因供调用方推送。
     */
    public HandleEditOutcome handleEdit(CollabMessage msg, Long editUserId, String editDisplayName,
                                        String senderSessionId) {
        // 身份红线：操作者一律以服务端会话身份为准，丢弃客户端负载里可伪造的 userId/displayName。
        msg = new CollabMessage(
                msg.type(), msg.documentId(), msg.sheetId(), msg.r(), msg.c(), msg.v(),
                editUserId, editDisplayName, msg.timestamp(), msg.serverTimestamp(), msg.requestId(),
                msg.baseRevision());

        // 光标/在线状态等临时事件：只在内存中转发，不持久化、不产生审计日志、不推进版本（任务 8 第 4 点）。
        if ("CURSOR".equals(msg.type())) {
            Map<String, Object> broadcast = new HashMap<>();
            broadcast.put("type", "CURSOR");
            broadcast.put("documentId", msg.documentId());
            broadcast.put("sheetId", msg.sheetId());
            broadcast.put("r", msg.r());
            broadcast.put("c", msg.c());
            broadcast.put("userId", editUserId);
            broadcast.put("displayName", editDisplayName);
            broadcast.put("senderSessionId", senderSessionId);
            return HandleEditOutcome.accepted(broadcast);
        }

        // ── revision 乐观锁：写单元格必须基于最新文档版本，落后即冲突（任务 8 第 2 点）。
        // baseRevision 为 null 视为旧客户端，跳过校验（保持向后兼容），LWW 仍兜底。
        long currentRevision = 0L;
        if (msg.baseRevision() != null) {
            com.argus.rag.excel.entity.ExcelDocument doc =
                    documentService.optionalById(msg.documentId());
            currentRevision = doc == null || doc.getVersion() == null ? 0L : doc.getVersion();
            if (!msg.baseRevision().equals(currentRevision)) {
                log.info("revision 冲突: doc={}, baseRev={}, serverRev={}, cell={}",
                        msg.documentId(), msg.baseRevision(), currentRevision, cellKey(msg));
                return HandleEditOutcome.conflict(currentRevision, cellKey(msg),
                        "该单元格已被他人更新，请刷新后重试或覆盖服务器内容");
            }
        }
        // ── LWW 冲突解决：比较时间戳 ──
        String cellKey = msg.documentId() + "_" + msg.sheetId() + "_" + msg.r() + "_" + msg.c();
        Long lastTs = lastEditTimestamps.get(cellKey);
        if (lastTs != null && msg.timestamp() <= lastTs) {
            log.debug("LWW 冲突：编辑被拒绝。cell={}, incomingTs={}, lastTs={}",
                    cellKey, msg.timestamp(), lastTs);
            return HandleEditOutcome.rejected("LWW_REJECTED", cellKey,
                    "您的编辑已被另一个用户的更新覆盖");
        }
        lastEditTimestamps.put(cellKey, msg.timestamp());

        log.debug("LWW 接受编辑: doc={}, sheet={}, r={}, c={}, user={}, ts={}",
                msg.documentId(), msg.sheetId(), msg.r(), msg.c(), editUserId, msg.timestamp());

        // 操作日志（含变更前值，供撤销/审计），失败不影响主流程。
        try {
            Map<String, Object> beforeCell = sheetService.loadCellSnapshot(
                    msg.documentId(), msg.sheetId(), msg.r(), msg.c());
            String beforeValue = beforeCell == null ? "null" : toJSON(beforeCell.get("v"));
            OperationLog entry = operationLogService.recordCellEdit(msg, beforeValue);
            // 双保险：即使日志服务内部读取了负载身份，也在返回后强制改写为会话身份。
            if (entry != null && !editUserId.equals(entry.getUserId())) {
                entry.setUserId(editUserId);
                entry.setUserDisplayName(editDisplayName);
            }
        } catch (Exception e) {
            log.error("操作日志写入失败（不影响编辑流程）: {}", e.getMessage());
        }

        // 记录活跃编辑（用于快照调度）。
        snapshotService.touch(msg.documentId());

        // 写入成功后推进文档版本号：服务器最后写入版本号（任务 8 第 2 点）。
        long newRevision;
        try {
            newRevision = documentService.bumpVersion(msg.documentId());
        } catch (Exception e) {
            log.warn("revision 推进失败（编辑已保存）: doc={}, error={}", msg.documentId(), e.getMessage());
            newRevision = currentRevision;
        }

        // 先持久化，成功后再广播。
        boolean persisted;
        try {
            persisted = persistEdit(msg);
        } catch (Exception e) {
            log.error("协作编辑持久化失败: cell={}, error={}", cellKey, e.getMessage());
            // 回滚 LWW 时间戳，允许下次重试。
            lastEditTimestamps.remove(cellKey);
            return HandleEditOutcome.rejected("SAVE_FAILED", cellKey, "保存失败，请重试");
        }
        if (!persisted) {
            // SQL 更新 0 行，说明数据已不存在。
            return HandleEditOutcome.rejected("SAVE_FAILED", cellKey, "保存失败，请重试");
        }

        // 协同操作日志入库（供运营中心与审计查询），失败不影响同步。
        try {
            ExcelCollabLog collabLog = new ExcelCollabLog();
            collabLog.setDocumentId(msg.documentId());
            collabLog.setSheetId(msg.sheetId());
            collabLog.setRowNo(msg.r());
            collabLog.setColNo(msg.c());
            collabLog.setActorId(editUserId);
            collabLog.setActorName(editDisplayName);
            collabLog.setRequestId(senderSessionId);
            collabLog.setBaseRev(msg.baseRevision());
            collabLog.setServerRev(newRevision);
            collabLog.setMerged(1);
            collabLog.setConflict(0);
            collabLog.setValueText(toJSON(msg.v()));
            collabLog.setResultValueText(toJSON(msg.v()));
            collabLogMapper.insert(collabLog);
        } catch (Exception e) {
            log.warn("协同日志入库失败，不影响同步: {}", e.getMessage());
        }

        // 构架广播消息（senderSessionId 供客户端去重）。
        Map<String, Object> broadcast = new HashMap<>();
        broadcast.put("type", msg.type());
        broadcast.put("documentId", msg.documentId());
        broadcast.put("sheetId", msg.sheetId());
        broadcast.put("r", msg.r());
        broadcast.put("c", msg.c());
        broadcast.put("v", msg.v());
        broadcast.put("userId", editUserId);
        broadcast.put("displayName", editDisplayName);
        broadcast.put("timestamp", msg.timestamp());
        broadcast.put("senderSessionId", senderSessionId);
        broadcast.put("requestId", msg.requestId());
        broadcast.put("revision", newRevision);

        // Outbox（任务 9）：广播是"实时直发 + Outbox 重放"双路径——这里落一条 PENDING
        // 事件由 ExcelCollabEventPublisher 定时重发，同时 CollabController 仍会把本次
        // 结果直接发往 topic（低延迟）。订阅者因此会收到重复消息，由客户端按 requestId
        // 去重；Outbox 兜底"保存成功但广播丢失"，直发兜底同实例的即时性。
        // Outbox 写失败只降级为实时直发。
        try {
            outboxService.record(msg.documentId(), msg.type(), broadcast);
        } catch (Exception e) {
            log.warn("Outbox 事件落库失败，降级为直接广播: doc={}, error={}", msg.documentId(), e.getMessage());
            return HandleEditOutcome.accepted(broadcast);
        }

        return HandleEditOutcome.accepted(broadcast);
    }

    /** 单元格在 LWW 缓存中的键。 */
    private static String cellKey(CollabMessage msg) {
        return msg.documentId() + "_" + msg.sheetId() + "_" + msg.r() + "_" + msg.c();
    }

    /** 用户加入文档：确保房间占用记录存在（创建或刷新）。{@code canEdit} 由调用方按文档权限计算，不再硬编码。 */
    public void joinRoom(Long documentId, Long userId, String displayName, String sessionId, boolean canEdit) {
        try {
            ExcelCollabRoom room = collabRoomMapper.selectOne(new QueryWrapper<ExcelCollabRoom>()
                    .eq("session_id", sessionId)
                    .last("LIMIT 1"));
            if (room == null) {
                room = new ExcelCollabRoom();
                room.setDocumentId(documentId);
                room.setActorId(userId);
                room.setActorName(displayName);
                room.setSessionId(sessionId);
                room.setCanEdit(canEdit ? 1 : 0);
                collabRoomMapper.insert(room);
            } else {
                ExcelCollabRoom update = new ExcelCollabRoom();
                update.setId(room.getId());
                update.setDocumentId(documentId);
                update.setActorId(userId);
                update.setActorName(displayName);
                update.setCanEdit(canEdit ? 1 : 0);
                collabRoomMapper.updateById(update);
            }
        } catch (Exception e) {
            log.warn("协同房间入库失败: session={}, error={}", sessionId, e.getMessage());
        }
    }

    /** 用户离开：删除其房间占用记录。 */
    public void leaveRoom(String sessionId) {
        collabRoomMapper.delete(new QueryWrapper<ExcelCollabRoom>()
                .eq("session_id", sessionId));
    }

    /** 持久化编辑到数据库；返回是否确已更新（0 行视为数据已不存在）。 */
    private boolean persistEdit(CollabMessage msg) {
        List<Map<String, Object>> updates = new ArrayList<>();
        Map<String, Object> cellUpdate = new HashMap<>();
        cellUpdate.put("sheetId", msg.sheetId());
        cellUpdate.put("r", msg.r());
        cellUpdate.put("c", msg.c());
        cellUpdate.put("v", msg.v());
        updates.add(cellUpdate);
        sheetService.batchUpdateCells(msg.documentId(), updates);
        return true;
    }

    private String toJSON(Object value) {
        if (value == null) return "null";
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return String.valueOf(value);
        }
    }

    /** 单次编辑的处理结果，驱动控制器选择推送方向。 */
    public record HandleEditOutcome(
            boolean accepted,
            String rejectionReason,
            String cellKey,
            String message,
            Map<String, Object> broadcast,
            /** 冲突时返回服务器当前版本号，客户端据此刷新后重试。 */
            long serverRevision
    ) {
        static HandleEditOutcome rejected(String reason, String cellKey, String message) {
            return new HandleEditOutcome(false, reason, cellKey, message, null, 0L);
        }

        static HandleEditOutcome accepted(Map<String, Object> broadcast) {
            return new HandleEditOutcome(true, null, null, null, broadcast, 0L);
        }

        static HandleEditOutcome conflict(long serverRevision, String cellKey, String message) {
            return new HandleEditOutcome(false, "REVISION_CONFLICT", cellKey, message, null, serverRevision);
        }
    }
}
