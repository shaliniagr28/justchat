package com.justchat.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.justchat.dto.WsEnvelope;
import com.justchat.metrics.ChatMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.SessionLimitExceededException;

import java.io.IOException;
import java.util.Collection;

/**
 * Pushes one frame to every live session of a user, for code outside the WS handler's own
 * callbacks (e.g. a REST call that adds a group member). Sessions in ConnectionRegistry are
 * already the per-connection ConcurrentWebSocketSessionDecorator, so concurrent pushes are
 * safe. A session that fails is evicted, same as in ChatWebSocketHandler.
 */
@Component
public class UserPusher {

    private static final Logger log = LoggerFactory.getLogger(UserPusher.class);

    private final ConnectionRegistry registry;
    private final ObjectMapper objectMapper;
    private final ChatMetrics metrics;

    public UserPusher(ConnectionRegistry registry, ObjectMapper objectMapper, ChatMetrics metrics) {
        this.registry = registry;
        this.objectMapper = objectMapper;
        this.metrics = metrics;
    }

    public void pushToUser(Long userId, WsEnvelope envelope) {
        pushToUsers(java.util.List.of(userId), envelope);
    }

    public void pushToUsers(Collection<Long> userIds, WsEnvelope envelope) {
        TextMessage frame;
        try {
            frame = new TextMessage(objectMapper.writeValueAsString(envelope));
        } catch (IOException e) {
            throw new IllegalStateException("Could not serialize " + envelope.type, e);
        }
        for (Long userId : userIds) {
            for (WebSocketSession session : registry.getSessions(userId)) {
                try {
                    session.sendMessage(frame);
                } catch (IOException | SessionLimitExceededException e) {
                    log.warn("Failed pushing to a session for userId={}, evicting it", userId);
                    metrics.pushFailed();
                    registry.unregister(userId, session);
                }
            }
        }
    }
}
