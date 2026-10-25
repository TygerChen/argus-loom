package com.argus.rag.assistant.controller;

import com.argus.rag.assistant.model.dto.chat.AssistantChatRequest;
import com.argus.rag.assistant.model.vo.chat.AssistantChatResponse;
import com.argus.rag.assistant.model.vo.chat.AssistantChatStreamEvent;
import com.argus.rag.assistant.service.AssistantService;
import com.argus.rag.common.api.ApiResponse;
import com.argus.rag.common.exception.GlobalExceptionHandler;
import com.argus.rag.common.logging.OperationLog;
import com.argus.rag.common.ratelimit.RateLimit;
import com.argus.rag.common.security.AuthenticatedUser;
import com.argus.rag.common.security.UserContext;
import com.argus.rag.common.util.SseEventWriter;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
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

/**
 * 助手聊天控制器。
 * <p>提供同步聊天和流式聊天（SSE）的 RESTful 接口。</p>
 * <ul>
 *   <li>POST /api/assistant/chat - 同步聊天，一次请求完成对话</li>
 *   <li>POST /api/assistant/chat/stream - 流式聊天，通过 SSE 逐段推送回复内容</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/assistant")
@Tag(name = "AI 智能助手", description = "多轮对话 Agent，SSE 流式输出、工具编排、短期记忆")
public class AssistantChatController {

    private final AssistantService assistantService;
    private final ObjectMapper objectMapper;

    public AssistantChatController(AssistantService assistantService, ObjectMapper objectMapper) {
        this.assistantService = assistantService;
        this.objectMapper = objectMapper;
    }

    /**
     * 同步聊天。
     * <p>接收用户消息，同步调用助手服务完成对话，一次性返回完整的回复和引用列表。</p>
     *
     * @param requestBody 聊天请求体，包含会话 ID、工具模式、知识库组 ID 和用户消息
     * @param request     HTTP 请求对象，用于身份认证
     * @return 包含助手回复内容和引用列表的响应
     */
    @PostMapping("/chat")
    @OperationLog
    @RateLimit(limit = 30, window = 60, scope = "AI助手")
    public ApiResponse<AssistantChatResponse> chat(
            @Valid @RequestBody AssistantChatRequest requestBody,
            HttpServletRequest request
    ) {
        return ApiResponse.success(assistantService.chat(request, requestBody));
    }

    /**
     * 流式聊天（SSE）。
     * <p>按 SSE 格式逐段推送模型生成的文本增量，异常时发送 error 事件后结束响应。</p>
     *
     * @param requestBody 聊天请求体，包含会话 ID、工具模式、知识库组 ID 和用户消息
     * @param request     HTTP 请求对象，用于身份认证
     * @return 流式响应体
     */
    @PostMapping(path = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @OperationLog
    @RateLimit(limit = 30, window = 60, scope = "AI助手流式")
    public ResponseEntity<StreamingResponseBody> streamChat(
            @Valid @RequestBody AssistantChatRequest requestBody,
            HttpServletRequest request
    ) {
        // StreamingResponseBody 由容器在独立线程执行，UserContext 需要在此处提前抓取。
        AuthenticatedUser authenticatedUser = UserContext.get();
        StreamingResponseBody responseBody = outputStream -> {
            if (authenticatedUser != null) {
                UserContext.set(authenticatedUser);
            }
            try {
                assistantService.streamChat(request, requestBody, event -> {
                    try {
                        writeEvent(outputStream, event);
                    } catch (IOException exception) {
                        throw new IllegalStateException("发送 SSE 事件失败", exception);
                    }
                }); 
                outputStream.flush();
                outputStream.close();
            } catch (Exception exception) {
                try {
                    writeEvent(outputStream, AssistantChatStreamEvent.error(
                            requestBody.sessionId(),
                            requestBody.toolMode(),
                            requestBody.groupId(),
                            GlobalExceptionHandler.userFacingMessage(exception, "流式助手调用失败")
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

    private void writeEvent(OutputStream outputStream, AssistantChatStreamEvent event) throws IOException {
        SseEventWriter.write(outputStream, event.event(), objectMapper.writeValueAsString(event));
    }
}
