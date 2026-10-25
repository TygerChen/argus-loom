package com.argus.rag.qa.service;

import com.argus.rag.auth.CurrentUserService;
import com.argus.rag.common.exception.BusinessException;
import com.argus.rag.common.exception.ErrorCodes;
import com.argus.rag.group.service.GroupMembershipService;
import com.argus.rag.metrics.LlmEndpoint;
import com.argus.rag.metrics.LlmModule;
import com.argus.rag.metrics.collector.LlmUsageCollector;
import com.argus.rag.metrics.cost.LlmCostCalculator;
import com.argus.rag.metrics.model.dto.LlmUsageRecordDTO;
import com.argus.rag.qa.model.EvidenceLevel;
import com.argus.rag.qa.model.dto.AskQuestionRequest;
import com.argus.rag.qa.model.vo.AskQuestionResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.ai.document.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 知识问答入口服务 —— RAG 链路的"总指挥"（04 篇的编排层）。
 *
 * <p><b>它在整条链路的位置</b>：Controller（QaController，管 HTTP/SSE 细节）
 * → <b>本类（管"该不该答、答完之后"）</b> → QaChatService（管"怎么检索、怎么生成"）。
 * 三层各干一件事：Controller 不懂业务规则，本类不懂检索细节，
 * QaChatService 不懂权限和记账——面试被问"你的分层怎么划分"时，
 * 用这一句就能答清 qa 包内部的职责切分。</p>
 *
 * <p><b>本类自己干的三件事</b>：</p>
 * <ol>
 *   <li><b>权限闸门（在业务最前面）</b>：requireGroupReadable 校验"你是这个群组的成员吗"。
 *       为什么放在 service 而不是 Controller？Controller 只管协议转换，规则校验统一在
 *       service 层——这样无论以后加多少个入口（定时任务、内部 RPC），规则都只有一份；
 *       为什么放在检索之前？不先拦住，后面的向量检索、大模型调用全是白花钱。
 *       这是第一道权限（群组成员资格），检索层还有第二道（04 篇 Q20 的 DocumentRoleGate
 *       按文档角色过滤）——两道闸门缺一不可，单靠任何一道都有绕过风险；</li>
 *   <li><b>问答落库（qa_records / qa_record_citations）</b>：同步路径答完就存；
 *       流式路径在 token 流上挂 doOnComplete/doOnError 回调，流结束（无论成败）才落库。
 *       失败也分两类记录：业务拒答（RETRIEVAL_NOT_GROUNDED）算 success=true——
 *       因为系统按设计正确工作了；系统异常才是 success=false。这个区分让
 *       "拒答率"和"故障率"在监控里是两条独立的线，混在一起就没法排查了；</li>
 *   <li><b>LLM 用量计量（recordUsage）</b>：每次调用记 token 数和成本到 metrics 模块。
 *       注意 try-catch 包住了整个记账——记账失败只打 warn 不抛出，
 *       因为"没记上账"不该让用户已经拿到的回答作废；但落库失败是要抛的（业务数据完整性）。
 *       哪些失败可以容忍、哪些必须失败，是服务拆层后必做的判断题。</li>
 * </ol>
 *
 * <p><b>流式路径的两个面试级细节（都在 askStream 里）</b>：</p>
 * <ul>
 *   <li><b>AtomicReference 跨线程传结果</b>：token 流的回调在 Reactor 线程执行，
 *       方法主线程拿不到回调里的局部变量，所以用 AtomicReference 当"跨线程信箱"，
 *       流结束后主流程从信箱取 usage 落库；</li>
 *   <li><b>cache() 幂等护栏</b>：Flux 是冷流，上层若断线重连重试订阅，
 *       会把"调 LLM + 落库 + 计量"整套副作用再跑一遍——重复扣费、重复记录。
 *       cache() 把首次订阅的产出固化，后续订阅只重放缓存，副作用只发生一次。
 *       面试官问"流式接口怎么做幂等"时，这就是标准答案素材。</li>
 * </ul>
 */
@Service
public class QaService {

    private static final Logger log = LoggerFactory.getLogger(QaService.class);
    @Value("${argus.rag.qa.model-name:qwen-plus}")
    private String qaModelName;
    private static final String INSUFFICIENT_EVIDENCE_CODE = ErrorCodes.RETRIEVAL_NOT_GROUNDED;
    private static final String SYSTEM_ERROR_CODE = "SYSTEM_ERROR";

    private final GroupMembershipService groupMembershipService;
    private final QaChatService qaChatService;
    private final CurrentUserService currentUserService;
    private final LlmUsageCollector llmUsageCollector;
    private final LlmCostCalculator llmCostCalculator;
    private final QaRecordPersistenceService qaRecordPersistenceService;

    /**
     * 构造函数。
     *
     * @param groupMembershipService 群组成员关系服务，用于校验用户权限
     * @param qaChatService          问答对话服务，执行实际的检索和大模型问答
     * @param currentUserService     当前用户服务，用于获取当前登录用户
     * @param llmUsageCollector      LLM 用量采集器
     * @param llmCostCalculator      LLM 费用计算器
     */
    public QaService(
            GroupMembershipService groupMembershipService,
            QaChatService qaChatService,
            CurrentUserService currentUserService,
            LlmUsageCollector llmUsageCollector,
            LlmCostCalculator llmCostCalculator,
            QaRecordPersistenceService qaRecordPersistenceService) {
        this.groupMembershipService = groupMembershipService;
        this.qaChatService = qaChatService;
        this.currentUserService = currentUserService;
        this.llmUsageCollector = llmUsageCollector;
        this.llmCostCalculator = llmCostCalculator;
        this.qaRecordPersistenceService = qaRecordPersistenceService;
    }

    /**
     * 处理用户提问请求。
     * <p>
     * 1. 校验当前用户对目标群组的读取权限（非成员将抛出异常）。<br>
     * 2. 委托 {@link QaChatService} 执行检索和回答生成。<br>
     * 3. 记录 LLM 用量。
     * </p>
     *
     * @param request            HTTP 请求对象，用于提取用户身份
     * @param askQuestionRequest 问答请求 DTO
     * @return 问答响应
     */
    public AskQuestionResponse ask(HttpServletRequest request, AskQuestionRequest askQuestionRequest) {
        Long groupId = askQuestionRequest.getGroupId();
        groupMembershipService.requireGroupReadable(groupId);
        Long userId = currentUserService.getRequiredCurrentUser().userId();

        QaChatService.AskResult result = qaChatService.askWithUsage(groupId, askQuestionRequest.getQuestion());

        Long recordId = saveQaRecord(
                userId,
                groupId,
                askQuestionRequest.getQuestion(),
                LlmEndpoint.QA_ASK,
                result.response(),
                result.evidenceLevel(),
                result.usage(),
                result.documents(),
                true,
                null
        );
        recordUsage(userId, groupId, LlmEndpoint.QA_ASK, result.usage(), true, null);

        return result.response().withRecordId(recordId);
    }

    /** 统一保存同步和流式 QA 记录，隐藏持久化服务的命令对象组装细节。 */
    private Long saveQaRecord(Long userId, Long groupId, String question, String endpoint,
                              AskQuestionResponse response,
                              com.argus.rag.qa.model.EvidenceLevel evidenceLevel,
                              QaChatService.UsageInfo usage,
                              List<Document> documents,
                              boolean success,
                              String errorMessage) {
        return qaRecordPersistenceService.saveCompleted(new QaRecordPersistenceService.SaveCommand(
                userId,
                groupId,
                question,
                endpoint,
                qaModelName,
                response,
                evidenceLevel,
                usage,
                documents == null ? List.of() : documents,
                success,
                errorMessage
        ));
    }

    /**
     * 处理流式用户提问请求。
     * <p>
     * 1. 校验当前用户对目标群组的读取权限（非成员将抛出异常）。<br>
     * 2. 委托 {@link QaChatService#askStream(Long, String, java.util.function.Consumer)} 执行流式检索和回答生成。<br>
     * 3. 在流完成后记录 LLM 用量。
     * </p>
     * <p>
     * 返回的 {@link QaChatService.StreamContext} 包含：<br>
     * {@code tokenStream} — 大模型逐 token 输出的文本流；<br>
     * {@code documents} — 检索到的证据文档，供 SSE 调用方在流结束后组装引用来源。
     * </p>
     *
     * @param request            HTTP 请求对象，用于提取用户身份
     * @param askQuestionRequest 问答请求 DTO
     * @return 流式问答上下文
     */
    public QaChatService.StreamContext askStream(HttpServletRequest request, AskQuestionRequest askQuestionRequest) {
        Long groupId = askQuestionRequest.getGroupId();
        groupMembershipService.requireGroupReadable(groupId);
        Long userId = currentUserService.getRequiredCurrentUser().userId();

        AtomicReference<QaChatService.UsageInfo> usageRef = new AtomicReference<>(
                new QaChatService.UsageInfo(0, 0, 0, false, 0L));
        QaChatService.StreamContext rawContext = qaChatService.askStream(groupId, askQuestionRequest.getQuestion(), usage -> {
            usageRef.set(usage);
            recordUsage(userId, groupId, LlmEndpoint.QA_STREAM_ASK, usage, true, null);
        });
        StringBuilder answerBuilder = new StringBuilder();
        AtomicReference<Long> recordIdRef = new AtomicReference<>();
        // 包装原始 token 流：边推送边收集完整回答，结束后再落 QA 记录。
        Flux<String> persistedTokenStream = rawContext.tokenStream()
                .doOnNext(answerBuilder::append)
                .doOnComplete(() -> {
                    AskQuestionResponse response = AskQuestionResponse.answered(
                            answerBuilder.toString(),
                            List.of());
                    Long recordId = saveQaRecord(
                            userId,
                            groupId,
                            askQuestionRequest.getQuestion(),
                            LlmEndpoint.QA_STREAM_ASK,
                            response,
                            rawContext.evidenceLevel(),
                            usageRef.get(),
                            rawContext.documents(),
                            true,
                            null
                    );
                    recordIdRef.set(recordId);
                })
                .doOnError(error -> {
                    // 无证据属于业务拒答，其它异常记录为系统失败。
                    // 通过结构化 code 识别（而非依赖 message 文本前缀），语义更稳定。
                    String answerCode = (error instanceof BusinessException businessEx) ? businessEx.getCode() : null;
                    boolean insufficientEvidence = INSUFFICIENT_EVIDENCE_CODE.equals(answerCode);
                    String message = error.getMessage() != null ? error.getMessage() : error.getClass().getSimpleName();
                    AskQuestionResponse response = insufficientEvidence
                            ? AskQuestionResponse.unanswered(INSUFFICIENT_EVIDENCE_CODE, message, List.of())
                            : AskQuestionResponse.unanswered(SYSTEM_ERROR_CODE, message, List.of());
                    Long recordId = saveQaRecord(
                            userId,
                            groupId,
                            askQuestionRequest.getQuestion(),
                            LlmEndpoint.QA_STREAM_ASK,
                            response,
                            insufficientEvidence ? EvidenceLevel.NONE : rawContext.evidenceLevel(),
                            usageRef.get(),
                            rawContext.documents(),
                            insufficientEvidence,
                            insufficientEvidence ? null : message
                    );
                    recordIdRef.set(recordId);
                });
        // 幂等护栏：token 流是冷流，若上层重试/重连对同一上下文重复订阅，会再次调 LLM、
        // 再次插入 QA 记录、再次计量，造成重复扣费与重复落库。用 cache() 固化一次执行——
        // 首个订阅者完成 LLM + 落库 + usage, 之后的订阅只重放已产出的 token 缓存,
        // 不再重复调用上游副作用, 让「token 级重试」幂等。
        Flux<String> idempotentTokenStream = persistedTokenStream.cache();
        return new QaChatService.StreamContext(
                idempotentTokenStream,
                rawContext.documents(),
                rawContext.evidenceLevel(),
                recordIdRef);
    }

    private void recordUsage(Long userId, Long groupId, String endpoint,
                             QaChatService.UsageInfo usage, boolean success, String errorMessage) {
        try {
            BigDecimal cost = llmCostCalculator.calculate(qaModelName, usage.promptTokens(), usage.completionTokens());
            LlmUsageRecordDTO dto = LlmUsageRecordDTO.builder()
                    .userId(userId)
                    .groupId(groupId)
                    .module(LlmModule.QA)
                    .endpoint(endpoint)
                    .promptTokens(usage.promptTokens())
                    .completionTokens(usage.completionTokens())
                    .totalTokens(usage.totalTokens())
                    .isEstimated(usage.estimated())
                    .costAmount(cost)
                    .latencyMs(usage.latencyMs())
                    .success(success)
                    .errorMessage(errorMessage)
                    .modelName(qaModelName)
                    .build();
            llmUsageCollector.record(dto);
        } catch (Exception e) {
            log.warn("QA用量记录失败: userId={}, groupId={}, endpoint={}", userId, groupId, endpoint, e);
        }
    }
}
