package com.argus.rag.common.task;

import com.argus.rag.common.task.entity.EntryLogEntity;

/**
 * 入口审计存储端口（Port）—— 审计服务与具体存储实现解耦的可测性设计。
 *
 * <p><b>为什么抽这个口子</b>：审计服务（异步提交 + fail-soft）与"怎么写进
 * entry_log"是两件事。抽成单方法端口后：</p>
 * <ul>
 *   <li>测试可以注入内存假实现（无需数据库、无需 mock 线程池），确定性断言；</li>
 *   <li>未来换存储（如写日志文件/发送审计管道）只换实现，服务与过滤器零改动；</li>
 *   <li>MyBatis 实现里的 {@code mapper.insert} 调用细节留在适配器内，
 *       服务层与测试层都不出现存储方言。</li>
 * </ul>
 *
 * <p>运行器视角：这是"运行器负责留痕"的第一个存储端口，后续治理层的
 * 审计流水会扩展它而不是另起炉灶。</p>
 */
public interface EntryAuditSink {

    /**
     * 持久化一条入口审计记录。实现必须自行处理存储异常（服务层的 fail-soft
     * 语义建立在本方法不抛运行时异常之外的存储不可用场景——实现抛出的异常
     * 由服务层 persistQuietly 统一吞掉告警）。
     *
     * @param entity 审计记录实体（非 null）
     */
    void accept(EntryLogEntity entity);
}
