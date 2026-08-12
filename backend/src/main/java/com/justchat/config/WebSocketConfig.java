package com.justchat.config;

import com.justchat.websocket.AuthHandshakeInterceptor;
import com.justchat.websocket.ChatWebSocketHandler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final ChatWebSocketHandler chatWebSocketHandler;
    private final AuthHandshakeInterceptor authHandshakeInterceptor;

    @Value("${app.cors.allowed-origin:http://localhost:5173}")
    private String allowedOrigin;

    public WebSocketConfig(ChatWebSocketHandler chatWebSocketHandler,
                            AuthHandshakeInterceptor authHandshakeInterceptor) {
        this.chatWebSocketHandler = chatWebSocketHandler;
        this.authHandshakeInterceptor = authHandshakeInterceptor;
    }

    @SuppressWarnings("null")
    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(chatWebSocketHandler, "/ws/chat")
                .addInterceptors(authHandshakeInterceptor)
                .setAllowedOrigins(allowedOrigin);
    }
}
