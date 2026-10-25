package com.argus.rag.config;

import com.argus.rag.common.api.ApiResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

/**
 * Agnes AI 生成式能力（文生图/文生视频）的 HTTP 封装。
 * <p>从配置读取 OpenAI 兼容的 api-key 与 base-url（默认指向 Agnes 网关），用 {@code RestTemplate} + {@code ObjectMapper} 调用生成式接口，返回统一包装的 {@code ApiResponse}。它是图片/视频生成对普通 Service 的底层通道。</p>
 */
@Service
public class AgnesAIService {
    private static final Logger log = LoggerFactory.getLogger(AgnesAIService.class);
    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${spring.ai.openai.api-key:}") private String apiKey;
    @Value("${spring.ai.openai.base-url:https://api.agnes-ai.cn/v1}") private String baseUrl;

    public ApiResponse<Map<String, Object>> generateImage(String model, String prompt, String size, int n) {
        try {
            Map<String, Object> body = Map.of("model", model, "prompt", prompt, "n", n, "size",
                    size != null ? size : "1024x1024");
            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(apiKey);
            headers.setContentType(MediaType.APPLICATION_JSON);
            @SuppressWarnings("unchecked")
            Map<String, Object> result = restTemplate.postForObject(baseUrl + "/images/generations",
                    new HttpEntity<>(body, headers), Map.class);
            return ApiResponse.success(result);
        } catch (Exception e) {
            log.error("Image generation failed: {}", e.getMessage());
            return new ApiResponse<>(false, null, "Image generation failed: " + e.getMessage());
        }
    }

    public ApiResponse<Map<String, Object>> generateVideo(String model, String prompt) {
        try {
            Map<String, Object> body = Map.of("model", model, "prompt", prompt);
            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(apiKey);
            headers.setContentType(MediaType.APPLICATION_JSON);
            @SuppressWarnings("unchecked")
            Map<String, Object> result = restTemplate.postForObject(baseUrl + "/videos",
                    new HttpEntity<>(body, headers), Map.class);
            return ApiResponse.success(result);
        } catch (Exception e) {
            log.error("Video generation failed: {}", e.getMessage());
            return new ApiResponse<>(false, null, "Video generation failed: " + e.getMessage());
        }
    }
}
