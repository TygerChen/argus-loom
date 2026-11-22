package com.argus.rag.finance.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 钉钉自定义机器人 webhook 客户端（个人日报推送通道）。
 *
 * <p>凭据走环境变量，缺失即跳过（fail-open，不阻塞日报生成）：
 * {@code FUND_DD_WEBHOOK}（access_token 地址）+ 可选 {@code FUND_DD_WEBHOOK_SECRET}（加签密钥）。</p>
 */
@Component
public class DingTalkWebhookClient {

    private static final Logger log = LoggerFactory.getLogger(DingTalkWebhookClient.class);

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newHttpClient();

    /** 推送结果：sent（成功）/ skipped（未配置）/ failed（发送失败）+ 说明。 */
    public record PushResult(String status, String detail) {
        static PushResult sent() {
            return new PushResult("SENT", "已推送");
        }

        public static PushResult skipped(String reason) {
            return new PushResult("SKIPPED", reason);
        }

        static PushResult failed(String reason) {
            return new PushResult("FAILED", reason);
        }
    }

    /** 发送 markdown 消息；webhook 未配置时返回 skipped。 */
    public PushResult sendMarkdown(String title, String markdownText) {
        String webhook = System.getenv("FUND_DD_WEBHOOK");
        if (webhook == null || webhook.isBlank()) {
            return PushResult.skipped("未配置 FUND_DD_WEBHOOK 环境变量，仅落库不推送");
        }
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("msgtype", "markdown");
            Map<String, Object> markdown = new LinkedHashMap<>();
            markdown.put("title", title);
            markdown.put("text", markdownText);
            body.put("markdown", markdown);
            String json = objectMapper.writeValueAsString(body);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(signedUrl(webhook)))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                String responseBody = response.body() == null ? "" : response.body();
                if (responseBody.contains("\"errcode\":0")) {
                    return PushResult.sent();
                }
                return PushResult.failed("钉钉返回异常: " + responseBody);
            }
            return PushResult.failed("HTTP " + response.statusCode());
        } catch (Exception e) {
            log.warn("钉钉推送失败: {}", e.getMessage());
            return PushResult.failed(e.getMessage());
        }
    }

    /** 加签密钥存在时追加 timestamp + sign 参数（HMAC-SHA256）。 */
    private String signedUrl(String webhook) throws Exception {
        String secret = System.getenv("FUND_DD_WEBHOOK_SECRET");
        if (secret == null || secret.isBlank()) {
            return webhook;
        }
        long timestamp = System.currentTimeMillis();
        String stringToSign = timestamp + "\n" + secret;
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] signData = mac.doFinal(stringToSign.getBytes(StandardCharsets.UTF_8));
        String sign = Base64.getEncoder().encodeToString(signData);
        String encoded = java.net.URLEncoder.encode(sign, StandardCharsets.UTF_8);
        String separator = webhook.contains("?") ? "&" : "?";
        return webhook + separator + "timestamp=" + timestamp + "&sign=" + encoded;
    }
}
