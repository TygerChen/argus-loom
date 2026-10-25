package com.argus.rag.excel.config;

import com.argus.rag.auth.security.JwtAccessTokenService;
import com.argus.rag.excel.collab.StompSessionAuth;
import com.argus.rag.excel.service.ExcelPermissionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

import java.util.List;

/**
 * STOMP WebSocket 配置。
 * <p>
 * 启用 STOMP 消息代理，前端通过 SockJS + STOMP 连接此端点。
 * 认证：STOMP CONNECT 帧的 Authorization header 中携带 JWT token，
 * 由 {@link WebSocketAuthChannelInterceptor} 解析后存入 session attributes。
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private static final Logger log = LoggerFactory.getLogger(WebSocketConfig.class);

    private final JwtAccessTokenService jwtAccessTokenService;
    private final ExcelPermissionService permissionService;
    /** 允许的 STOMP/SockJS 跨域来源（逗号分隔 origin pattern；P2-08：不再写死 "*"）。 */
    private final String allowedOriginPatterns;

    public WebSocketConfig(JwtAccessTokenService jwtAccessTokenService,
                           ExcelPermissionService permissionService,
                           @org.springframework.beans.factory.annotation.Value(
                                   "${argus.rag.websocket.allowed-origin-patterns:http://localhost:8081,http://127.0.0.1:8081,http://localhost:10001}")
                           String allowedOriginPatterns) {
        this.jwtAccessTokenService = jwtAccessTokenService;
        this.permissionService = permissionService;
        this.allowedOriginPatterns = allowedOriginPatterns;
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        // 客户端发送消息的目标前缀
        registry.setApplicationDestinationPrefixes("/app");
        // 内置 STOMP broker 的前缀——消息从这里广播到所有订阅者
        registry.enableSimpleBroker("/topic");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws")
                // P2-08：来源收敛到配置（argus.rag.websocket.allowed-origin-patterns），
                // 默认仅本机前端/后端；LAN 或域名部署经 ARGUS_WS_ALLOWED_ORIGINS 覆盖。
                .setAllowedOriginPatterns(resolveOriginPatterns(allowedOriginPatterns))
                .withSockJS();
    }

    /**
     * 解析逗号分隔的 origin 配置为 pattern 数组（沉淀为纯函数便于单测）。
     * <p>规则：去空白、忽略空段；全部为空时回退到本机默认来源（不回退 "*"，避免悄悄放开跨站）。</p>
     */
    static String[] resolveOriginPatterns(String csv) {
        if (csv == null || csv.isBlank()) {
            return new String[]{"http://localhost:8081", "http://127.0.0.1:8081", "http://localhost:10001"};
        }
        List<String> patterns = java.util.Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
        if (patterns.isEmpty()) {
            return new String[]{"http://localhost:8081", "http://127.0.0.1:8081", "http://localhost:10001"};
        }
        return patterns.toArray(new String[0]);
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(new WebSocketAuthChannelInterceptor(),
                new ExcelSubscribeInterceptor(permissionService));
    }

    /**
     * WebSocket STOMP 认证拦截器。
     * 从 CONNECT 帧的 Authorization header 中提取 JWT，验证后注入 session attributes。
     */
    private class WebSocketAuthChannelInterceptor implements ChannelInterceptor {

        @Override
        public Message<?> preSend(Message<?> message, MessageChannel channel) {
            StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(
                    message, StompHeaderAccessor.class);

            if (accessor != null && StompCommand.CONNECT.equals(accessor.getCommand())) {
                List<String> authHeaders = accessor.getNativeHeader("Authorization");
                if (authHeaders != null && !authHeaders.isEmpty()) {
                    try {
                        String token = authHeaders.get(0);
                        if (token.startsWith("Bearer ")) {
                            token = token.substring(7);
                        }
                        var claims = jwtAccessTokenService.parse(token);
                        if (claims != null) {
                            accessor.getSessionAttributes().put(StompSessionAuth.USER_ID, claims.userId());
                            accessor.getSessionAttributes().put(StompSessionAuth.DISPLAY_NAME, claims.displayName());
                            accessor.getSessionAttributes().put(StompSessionAuth.SYSTEM_ROLE, claims.systemRole().name());
                            log.debug("WebSocket 认证成功: userId={}", claims.userId());
                        }
                    } catch (Exception e) {
                        log.warn("WebSocket 认证失败: {}", e.getMessage());
                        // 认证失败 → 拒绝 CONNECT，不浪费服务器资源
                        throw new org.springframework.messaging.MessageDeliveryException(
                                "WebSocket 认证失败: " + e.getMessage());
                    }
                }
            }
            return message;
        }
    }
}