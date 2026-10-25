package com.argus.rag.qa.controller;

import com.argus.rag.common.logging.OperationLog;
import com.argus.rag.common.ratelimit.RateLimit;
import com.argus.rag.common.exception.GlobalExceptionHandler;
import com.argus.rag.qa.model.dto.AskQuestionRequest;
import com.argus.rag.qa.model.vo.AskQuestionResponse;
import com.argus.rag.qa.service.QaChatService;
import com.argus.rag.qa.service.QaService;
import com.argus.rag.qa.support.CitationAssembler;
import com.argus.rag.qa.support.EvidenceOverviewAssembler;
import com.argus.rag.common.security.AuthenticatedUser;
import com.argus.rag.common.security.UserContext;
import com.argus.rag.common.util.SseEventWriter;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.IOException;
import java.io.OutputStream;
import java.util.List;
import java.util.Map;

/**
 * 知识问答控制器。
 * <p>
 * 提供基于 RAG（检索增强生成）的知识库问答 API。
 * 用户在指定群组的知识库范围内提问，系统检索相关文档并由大模型生成回答。
 * </p>
 *
 * <p><b>两个端点两种响应方式</b>：/ask 一次性返回完整 JSON（简单、好调试）；
 * /stream-ask 用 SSE（Server-Sent Events）逐 token 推送——用户看到打字机效果，
 * 体验差距巨大。<b>SSE 的三个工程要点</b>这个类里都能看到：
 * ① 线程上下文传递：StreamingResponseBody 在另一个线程执行，
 * SecurityContext 不会自动带过去，所以先取出 AuthenticatedUser 手动 set；
 * ② 错误也要走 SSE：中途出错不能突然断流，而是发一个 error 事件让前端优雅提示；
 * ③ @RateLimit 限流：大模型调用又贵又慢，每分钟 20 次（09 篇的取号机）。</p>
 */
@RestController
@RequestMapping("/api/qa")
@Tag(name = "知识库问答", description = "群组内提问，混合检索驱动 LLM 生成可溯源回答")
@OperationLog
public class QaController {

    private static final Logger log = LoggerFactory.getLogger(QaController.class);

    private final QaService qaService;
    private final CitationAssembler citationAssembler;
    private final EvidenceOverviewAssembler evidenceOverviewAssembler;
    private final ObjectMapper objectMapper;

    public QaController(
            QaService qaService,
            CitationAssembler citationAssembler,
            EvidenceOverviewAssembler evidenceOverviewAssembler,
            ObjectMapper objectMapper
    ) {
        this.qaService = qaService;
        this.citationAssembler = citationAssembler;
        this.evidenceOverviewAssembler = evidenceOverviewAssembler;
        this.objectMapper = objectMapper;
    }

    /**
     * 提问接口：在指定群组的知识库中检索并回答用户问题。
     */
    @PostMapping("/ask")
    @RateLimit(limit = 20, window = 60, scope = "知识库问答")
    public AskQuestionResponse askQuestion(
            @Valid @RequestBody AskQuestionRequest askQuestionRequest,
            HttpServletRequest request
    ) {
        return qaService.ask(request, askQuestionRequest);
    }

    /**
     * 流式提问接口：使用 SSE 逐 token 推送大模型回答。
     */
    @PostMapping(path = "/stream-ask", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @RateLimit(limit = 20, window = 60, scope = "知识库流式问答")
    public ResponseEntity<StreamingResponseBody> streamAsk(
            @Valid @RequestBody AskQuestionRequest askQuestionRequest,
            HttpServletRequest request
    ) {
        AuthenticatedUser authenticatedUser = UserContext.get();
        StreamingResponseBody responseBody = outputStream -> {
            if (authenticatedUser != null) {
                UserContext.set(authenticatedUser);
            }
            try {
                QaChatService.StreamContext streamContext = qaService.askStream(request, askQuestionRequest);
                writeStream(outputStream, streamContext);
            } catch (Exception exception) {
                log.error("流式问答初始化失败", exception);
                try {
                    writeJsonEvent(outputStream, "error", Map.of(
                            "message",
                            GlobalExceptionHandler.userFacingMessage(exception, "请求处理失败")
                    ));
                    outputStream.flush();
                    outputStream.close();
                } catch (IOException ignored) {
                    // 客户端已断开，无需继续写入。
                }
            } finally {
                UserContext.clear();
            }
        };
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .cacheControl(CacheControl.noStore())
                .body(responseBody);
    }

    private void writeStream(OutputStream outputStream, QaChatService.StreamContext streamContext) throws IOException {
        streamContext.tokenStream()
                .doOnNext(token -> {
                    try {
                        writeEvent(outputStream, "token", token);
                    } catch (IOException exception) {
                        throw new IllegalStateException("SSE 发送 token 失败", exception);
                    }
                })
                .doOnComplete(() -> {
                    try {
                        List<AskQuestionResponse.Citation> citations = citationAssembler
                                .assembleDocuments(streamContext.documents());
                        if (!citations.isEmpty()) {
                            writeJsonEvent(outputStream, "citations", citations);
                        }
                        AskQuestionResponse.EvidenceOverview evidenceOverview = evidenceOverviewAssembler
                                .assemble(streamContext.documents());
                        if (evidenceOverview != null) {
                            writeJsonEvent(outputStream, "evidence-overview", evidenceOverview);
                        }
                        Long recordId = streamContext.recordId();
                        if (recordId != null) {
                            writeJsonEvent(outputStream, "record", Map.of("recordId", recordId));
                        }
                    } catch (IOException exception) {
                        throw new IllegalStateException("SSE 发送收尾事件失败", exception);
                    }
                })
                .doOnError(error -> log.error("流式问答 token 流异常", error))
                .blockLast();
        outputStream.flush();
        outputStream.close();
    }

    private void writeJsonEvent(OutputStream output, String name, Object data) throws IOException {
        SseEventWriter.write(output, name, objectMapper.writeValueAsString(data));
    }

    private void writeEvent(OutputStream output, String name, String data) throws IOException {
        SseEventWriter.write(output, name, data);
    }
}
