package com.argus.rag.metrics.collector;

import com.argus.rag.metrics.model.dto.LlmUsageRecordDTO;
import com.argus.rag.metrics.model.entity.LlmUsageRecordEntity;
import com.argus.rag.metrics.mapper.LlmUsageRecordMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * LLM 用量采集器实现 —— 每次调用大模型，都在这里"记账"。
 *
 * <p><b>它在系统里的位置</b>：问答、助手、检索改写等所有会用大模型的地方，
 * 调用完成后都把"这次用了多少 token、花了多少钱、耗时多久、成功没有"
 * 交给 {@link LlmUsageCollector} 接口，本实现负责把这条账目写进
 * {@code llm_usage_records} 表。运维页面的用量统计（09 篇的监控大盘）
 * 读的就是这张表。</p>
 *
 * <p><b>关键设计：异步 + 绝不拖垮主流程</b>。方法标了 {@code @Async}
 * （调用方立刻返回，写库在别的线程做），内部再套一层 try-catch——
 * 就算记账失败，也只记一条错误日志，用户的问答请求完全不受影响。
 * 这是一条通用的可靠性原则：<b>统计、审计这类"锦上添花"的操作，
 * 决不能让业务主流程为它买单</b>。</p>
 */
@Service
@Slf4j
public class LlmUsageCollectorImpl implements LlmUsageCollector {

    private final LlmUsageRecordMapper llmUsageRecordMapper;

    public LlmUsageCollectorImpl(LlmUsageRecordMapper llmUsageRecordMapper) {
        this.llmUsageRecordMapper = llmUsageRecordMapper;
    }

    @Async
    @Override
    public void record(LlmUsageRecordDTO record) {
        try {
            LlmUsageRecordEntity entity = toEntity(record);
            llmUsageRecordMapper.insert(entity);
            log.debug("LLM 用量记录已保存: module={}, endpoint={}, totalTokens={}",
                    entity.getModule(), entity.getEndpoint(), entity.getTotalTokens());
        } catch (Exception e) {
            // 统计记录不应影响业务主流程，捕获异常后仅记录日志
            log.error("LLM 用量记录保存失败: {}", e.getMessage(), e);
        }
    }

    private LlmUsageRecordEntity toEntity(LlmUsageRecordDTO dto) {
        LlmUsageRecordEntity entity = new LlmUsageRecordEntity();
        entity.setUserId(dto.getUserId());
        entity.setGroupId(dto.getGroupId());
        entity.setModule(dto.getModule());
        entity.setEndpoint(dto.getEndpoint());
        entity.setSessionId(dto.getSessionId());
        entity.setPromptTokens(dto.getPromptTokens());
        entity.setCompletionTokens(dto.getCompletionTokens());
        entity.setTotalTokens(dto.getTotalTokens());
        entity.setIsEstimated(dto.getIsEstimated());
        entity.setCostAmount(dto.getCostAmount());
        entity.setCostCurrency("CNY");
        entity.setLatencyMs(dto.getLatencyMs());
        entity.setSuccess(dto.getSuccess());
        entity.setErrorMessage(dto.getErrorMessage());
        entity.setModelName(dto.getModelName());
        entity.setCreatedAt(LocalDateTime.now());
        return entity;
    }
}
