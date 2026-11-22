package com.argus.rag.qa.service;

import com.argus.rag.qa.mapper.QaConversationMapper;
import com.argus.rag.qa.mapper.QaRecordMapper;
import com.argus.rag.qa.model.entity.QaConversationEntity;
import com.argus.rag.qa.model.entity.QaRecordEntity;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * QA 多轮会话记忆服务：会话延续判定、近期原文窗口、滚动摘要合并。
 * <p>
 * 三层记忆中的两层在这里维护（第三层"本轮证据"由检索链路提供）：
 * <ul>
 *   <li><b>近期原文窗口</b>：取 qa_records 最近 N 轮原话（逐轮截断），提供精确细节锚点；</li>
 *   <li><b>滚动摘要</b>：懒触发增量合并——窗口外的历史压成结构化三段式
 *       （话题域/已确认/未决），回答完成后由 {@code taskExecutor} 异步执行，
 *       不阻塞用户拿答案。</li>
 * </ul>
 * <b>失败语义：整层 fail-soft</b>——任何异常只记 WARN 并保留旧状态，
 * 问答自动退化为单轮模式，绝不因记忆组件失败而失败。</p>
 * <p>
 * 摘要合并走 {@code taskExecutor} 线程池（显式提交，避免 @Async 自调用代理失效），
 * 并通过 {@link QaConversationMapper#updateSummaryWithVersion} 乐观锁防并发覆盖。
 * </p>
 */
@Slf4j
@Service
public class QaConversationMemoryService {

    private final QaConversationMapper qaConversationMapper;
    private final QaRecordMapper qaRecordMapper;
    private final ThreadPoolTaskExecutor taskExecutor;
    private final PromptTemplate qaConversationSummaryMergePromptTemplate;
    private final org.springframework.ai.chat.client.ChatClient chatClient;

    @Value("${argus.rag.qa.memory.enabled:true}")
    private boolean enabled;
    /** 会话延续时间窗（秒）：同用户同群组、窗内提问自动延续上一会话。 */
    @Value("${argus.rag.qa.memory.continue-window-seconds:300}")
    private int continueWindowSeconds;
    /** 近期原文窗口保留的轮数。 */
    @Value("${argus.rag.qa.memory.recent-turns:3}")
    private int recentTurns;
    /** 窗口内每轮问题/回答的截断长度（字符），防止单轮长输入吃光窗口。 */
    @Value("${argus.rag.qa.memory.turn-max-chars:400}")
    private int turnMaxChars;
    /** 累计多少轮未摘要后触发一次滚动摘要合并。 */
    @Value("${argus.rag.qa.memory.summary-trigger-turns:3}")
    private int summaryTriggerTurns;
    /** 单次合并最多携带的轮数（防止合并输入无限膨胀）。 */
    @Value("${argus.rag.qa.memory.summary-merge-turns:6}")
    private int summaryMergeTurns;
    /** 摘要软上限（字符），超出在提示词层面要求合并同类项。 */
    @Value("${argus.rag.qa.memory.summary-max-chars:400}")
    private int summaryMaxChars;

    public QaConversationMemoryService(
            QaConversationMapper qaConversationMapper,
            QaRecordMapper qaRecordMapper,
            @Qualifier("taskExecutor") ThreadPoolTaskExecutor taskExecutor,
            @Qualifier("qaConversationSummaryMergePromptTemplate") PromptTemplate qaConversationSummaryMergePromptTemplate,
            org.springframework.ai.chat.client.ChatClient.Builder chatClientBuilder
    ) {
        this.qaConversationMapper = qaConversationMapper;
        this.qaRecordMapper = qaRecordMapper;
        this.taskExecutor = taskExecutor;
        this.qaConversationSummaryMergePromptTemplate = qaConversationSummaryMergePromptTemplate;
        this.chatClient = chatClientBuilder.build();
    }

    /**
     * 解析本轮提问归属的会话 ID。
     * <p>
     * 规则：显式携带且归属/群组匹配、时间窗内 → 延续并刷新时间戳；
     * 其余情况（未携带、越权、超窗）→ 新建会话，标题取首问截断。
     * 延续判定失败不抛异常——记忆层永远给问答链路一个可用会话。
     * </p>
     */
    public Long resolveConversation(Long userId, Long groupId, Long requestedConversationId, String question) {
        if (!enabled) {
            return null;
        }
        if (requestedConversationId != null) {
            QaConversationEntity existing = qaConversationMapper.selectById(requestedConversationId);
            boolean owned = existing != null
                    && userId.equals(existing.getUserId())
                    && groupId.equals(existing.getGroupId())
                    && existing.getLastQuestionAt() != null
                    && existing.getLastQuestionAt()
                            .isAfter(LocalDateTime.now().minusSeconds(continueWindowSeconds));
            if (owned) {
                qaConversationMapper.touchLastQuestion(requestedConversationId, userId);
                return requestedConversationId;
            }
            log.info("会话延续失败，新建会话: requested={}, userId={}, groupId={}",
                    requestedConversationId, userId, groupId);
        }
        QaConversationEntity conversation = new QaConversationEntity();
        conversation.setUserId(userId);
        conversation.setGroupId(groupId);
        conversation.setTitle(truncate(question, 60));
        qaConversationMapper.insert(conversation);
        return conversation.getId();
    }

    /** 读取滚动摘要正文；无会话或无摘要返回 null。 */
    public String summaryOf(Long conversationId) {
        if (conversationId == null) {
            return null;
        }
        QaConversationEntity conversation = qaConversationMapper.selectById(conversationId);
        return conversation == null ? null : emptyToNull(conversation.getSummary());
    }

    /**
     * 近期原文窗口：最近 N 轮"用户：…/助手：…"文本，逐轮截断。
     * 用于检索改写与生成层记忆块；无会话返回 null。
     */
    public String recentTurnsText(Long conversationId) {
        if (conversationId == null) {
            return null;
        }
        List<QaRecordEntity> turns = recentAnsweredTurns(conversationId, recentTurns);
        if (turns.isEmpty()) {
            return null;
        }
        StringBuilder builder = new StringBuilder();
        for (QaRecordEntity turn : turns) {
            if (!builder.isEmpty()) {
                builder.append(System.lineSeparator());
            }
            builder.append("用户：").append(truncate(turn.getQuestion(), turnMaxChars))
                    .append(System.lineSeparator())
                    .append("助手：").append(truncate(turn.getAnswer(), turnMaxChars));
        }
        return emptyToNull(builder.toString());
    }

    /**
     * 回合完成回调（由 QaService 在记录落库后调用）。
     * <p>1. 刷新会话时间戳；2. 若未摘要轮数达到阈值，提交异步摘要合并任务。
     * 两次失败都只记 WARN。</p>
     */
    public void onTurnCompleted(Long conversationId, Long recordId) {
        if (conversationId == null || !enabled) {
            return;
        }
        try {
            QaConversationEntity touch = new QaConversationEntity();
            touch.setId(conversationId);
            touch.setLastQuestionAt(LocalDateTime.now());
            touch.setUpdatedAt(LocalDateTime.now());
            qaConversationMapper.updateById(touch);
        } catch (Exception exception) {
            log.warn("会话时间戳刷新失败（忽略）: conversationId={}", conversationId, exception);
        }
        try {
            QaConversationEntity conversation = qaConversationMapper.selectById(conversationId);
            if (conversation == null) {
                return;
            }
            long upto = conversation.getSummaryUptoRecordId() == null ? 0L : conversation.getSummaryUptoRecordId();
            long pending = qaRecordMapper.selectCount(new LambdaQueryWrapper<QaRecordEntity>()
                    .eq(QaRecordEntity::getConversationId, conversationId)
                    .eq(QaRecordEntity::getAnswered, true)
                    .gt(QaRecordEntity::getId, upto));
            if (pending >= summaryTriggerTurns) {
                taskExecutor.submit(() -> mergeSummarySafely(conversationId));
                log.info("滚动摘要合并已提交: conversationId={}, pendingTurns={}",
                        conversationId, pending);
            }
        } catch (Exception exception) {
            log.warn("摘要触发检查失败（忽略，下轮重试）: conversationId={}", conversationId, exception);
        }
    }

    /** 异步执行的摘要合并：读游标 → 取增量轮次 → LLM 合并 → 乐观锁写回。全程 fail-soft。 */
    void mergeSummarySafely(Long conversationId) {
        try {
            QaConversationEntity conversation = qaConversationMapper.selectById(conversationId);
            if (conversation == null) {
                return;
            }
            long upto = conversation.getSummaryUptoRecordId() == null ? 0L : conversation.getSummaryUptoRecordId();
            List<QaRecordEntity> pending = new java.util.ArrayList<>(recentAnsweredTurnsSince(conversationId, upto, summaryMergeTurns));
            if (pending.isEmpty()) {
                return;
            }
            java.util.Collections.reverse(pending); // 游标后按时间正序合并
            long maxRecordId = pending.get(pending.size() - 1).getId();
            String oldSummary = emptyToNull(conversation.getSummary());
            if (oldSummary == null) {
                oldSummary = "无";
            }
            String newTurns = formatTurns(pending);
            String merged = chatClient.prompt(qaConversationSummaryMergePromptTemplate.create(Map.of(
                            "oldSummary", oldSummary,
                            "newTurns", newTurns)))
                    .call()
                    .content();
            if (merged == null || merged.isBlank()) {
                log.warn("滚动摘要合并返回空，保留旧摘要: conversationId={}", conversationId);
                return;
            }
            String summary = truncate(merged.trim(), summaryMaxChars);
            int updated = qaConversationMapper.updateSummaryWithVersion(
                    conversationId,
                    conversation.getSummaryVersion() == null ? 0 : conversation.getSummaryVersion(),
                    summary,
                    maxRecordId);
            log.info("滚动摘要合并{}: conversationId={}, version={}, 覆盖轮数={}",
                    updated == 1 ? "成功" : "冲突放弃", conversationId,
                    conversation.getSummaryVersion(), pending.size());
        } catch (Exception exception) {
            log.warn("滚动摘要合并失败（保留旧摘要）: conversationId={}", conversationId, exception);
        }
    }

    /**
     * 组装"对话背景"记忆块（滚动摘要 + 近期原文），注入生成层。
     * 两层皆空返回 null（单轮模式，不注入任何记忆块）。
     */
    public String composeMemoryContext(String summary, String recentTurnsText) {
        boolean hasSummary = summary != null && !summary.isBlank();
        boolean hasTurns = recentTurnsText != null && !recentTurnsText.isBlank();
        if (!hasSummary && !hasTurns) {
            return null;
        }
        StringBuilder builder = new StringBuilder();
        builder.append("【对话背景】以下是本会话此前对话的摘要与近期原话，仅用于保持回答连贯性，")
               .append("不要引用其中内容作为事实依据，事实以本轮证据为准。");
        if (hasSummary) {
            builder.append(System.lineSeparator()).append("【历史摘要】").append(summary);
        }
        if (hasTurns) {
            builder.append(System.lineSeparator()).append("【近期对话】").append(System.lineSeparator()).append(recentTurnsText);
        }
        return builder.toString();
    }

    /** 摘要游标之后、已成功回答的最近轮次（时间倒序）。 */
    private List<QaRecordEntity> recentAnsweredTurnsSince(Long conversationId, long afterRecordId, int limit) {
        return qaRecordMapper.selectList(new LambdaQueryWrapper<QaRecordEntity>()
                .eq(QaRecordEntity::getConversationId, conversationId)
                .eq(QaRecordEntity::getAnswered, true)
                .gt(QaRecordEntity::getId, afterRecordId)
                .orderByDesc(QaRecordEntity::getId)
                .last("LIMIT " + Math.max(1, limit)));
    }

    /** 最近已回答轮次（时间倒序）。 */
    private List<QaRecordEntity> recentAnsweredTurns(Long conversationId, int limit) {
        return qaRecordMapper.selectList(new LambdaQueryWrapper<QaRecordEntity>()
                .eq(QaRecordEntity::getConversationId, conversationId)
                .eq(QaRecordEntity::getAnswered, true)
                .isNotNull(QaRecordEntity::getAnswer)
                .orderByDesc(QaRecordEntity::getId)
                .last("LIMIT " + Math.max(1, limit)));
    }

    /** 把记录列表格式化为"用户：…/助手：…"纯文本，逐轮截断。 */
    private String formatTurns(List<QaRecordEntity> turns) {
        StringBuilder builder = new StringBuilder();
        for (QaRecordEntity turn : turns) {
            if (turn == null) {
                continue;
            }
            if (!builder.isEmpty()) {
                builder.append(System.lineSeparator());
            }
            builder.append("用户：").append(truncate(turn.getQuestion(), turnMaxChars))
                    .append(System.lineSeparator())
                    .append("助手：").append(truncate(turn.getAnswer(), turnMaxChars));
        }
        return builder.isEmpty() ? "无" : builder.toString();
    }

    private String truncate(String text, int maxChars) {
        if (text == null) {
            return "";
        }
        String normalized = text.strip();
        return normalized.length() <= maxChars ? normalized : normalized.substring(0, maxChars) + "…";
    }

    private String emptyToNull(String text) {
        return text == null || text.isBlank() ? null : text;
    }
}
