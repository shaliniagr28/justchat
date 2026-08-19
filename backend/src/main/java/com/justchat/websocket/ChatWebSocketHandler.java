package com.justchat.websocket;

import com.justchat.dto.WsEnvelope;
import com.justchat.model.Message;
import com.justchat.model.User;
import com.justchat.repository.UserRepository;
import com.justchat.service.MessageService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.List;

@Component
public class ChatWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(ChatWebSocketHandler.class);

    private final ConnectionRegistry registry;
    private final MessageService messageService;
    private final UserRepository userRepository;
    private final ObjectMapper objectMapper;

    public ChatWebSocketHandler(ConnectionRegistry registry, MessageService messageService,
                                 UserRepository userRepository, ObjectMapper objectMapper) {
        this.registry = registry;
        this.messageService = messageService;
        this.userRepository = userRepository;
        this.objectMapper = objectMapper;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        Long userId = resolveUserId(session);
        session.getAttributes().put("userId", userId);
        registry.register(userId, session);
        log.info("WS connected: userId={} sessionId={}", userId, session.getId());

        List<Message> backlog = messageService.findUndelivered(userId);
        for (Message m : backlog) {
            sendToSession(session, toMessageEnvelope(m));
            Message updated = messageService.markDelivered(m.getId());
            notifySenderOfStatus(updated);
        }
        sendToSession(session, WsEnvelope.type("BACKLOG_DONE"));
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        Long userId = (Long) session.getAttributes().get("userId");
        WsEnvelope in;
        try {
            in = objectMapper.readValue(message.getPayload(), WsEnvelope.class);
        } catch (Exception e) {
            sendError(session, "Malformed message");
            return;
        }

        if (in.type == null) {
            sendError(session, "Missing type");
            return;
        }

        switch (in.type) {
            case "SEND" -> handleSend(session, userId, in);
            default -> sendError(session, "Unknown type: " + in.type);
        }
    }

    private void handleSend(WebSocketSession session, Long senderId, WsEnvelope in) throws IOException {
        if (in.recipientId == null || in.content == null || in.content.isBlank()) {
            sendError(session, "SEND requires recipientId and content");
            return;
        }

        // Persist first, push second - this
        // ordering matters (a crash between the two degrades to "delivered late" via the
        // backlog path on next connect, rather than the message being lost outright).
        Message saved = messageService.persistNewMessage(senderId, in.recipientId, in.content);

        // Echo back to the sender with the server-assigned id, so the sender's optimistic
        // local render (shown instantly on send) can be reconciled with the real row via
        // clientMsgId, rather than trusting its own guess at an id.
        WsEnvelope echo = toMessageEnvelope(saved);
        echo.clientMsgId = in.clientMsgId;
        sendToSession(session, echo);

        if (registry.isOnline(in.recipientId)) {
            WsEnvelope push = toMessageEnvelope(saved);
            boolean delivered = false;
            for (WebSocketSession recipientSession : registry.getSessions(in.recipientId)) {
                try {
                    recipientSession.sendMessage(new TextMessage(objectMapper.writeValueAsString(push)));
                    delivered = true;
                } catch (IOException e) {
                    log.warn("Failed pushing to a session for userId={}, evicting it", in.recipientId);
                    registry.unregister(in.recipientId, recipientSession);
                }
            }
            if (delivered) {
                Message updated = messageService.markDelivered(saved.getId());
                notifySenderOfStatus(updated);
            }
        }
    }

    private void notifySenderOfStatus(Message message) throws IOException {
        if (!registry.isOnline(message.getSenderId())) return;
        WsEnvelope update = WsEnvelope.type("STATUS_UPDATE");
        update.id = message.getId();
        update.status = message.getStatus().name();
        for (WebSocketSession senderSession : registry.getSessions(message.getSenderId())) {
            try {
                senderSession.sendMessage(new TextMessage(objectMapper.writeValueAsString(update)));
            } catch (IOException e) {
                registry.unregister(message.getSenderId(), senderSession);
            }
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        Long userId = (Long) session.getAttributes().get("userId");
        if (userId != null) {
            registry.unregister(userId, session);
            log.info("WS closed: userId={} sessionId={} status={}", userId, session.getId(), status);
        }
    }

    private Long resolveUserId(WebSocketSession session) {
        String username = (String) session.getAttributes().get("username");
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalStateException("Authenticated username not found: " + username));
        return user.getId();
    }

    private WsEnvelope toMessageEnvelope(Message m) {
        WsEnvelope e = WsEnvelope.type("MESSAGE");
        e.id = m.getId();
        e.senderId = m.getSenderId();
        e.recipientId = m.getRecipientId();
        e.content = m.getContent();
        e.status = m.getStatus().name();
        e.sentAt = m.getSentAt().toString();
        return e;
    }

    private void sendToSession(WebSocketSession session, WsEnvelope envelope) throws IOException {
        session.sendMessage(new TextMessage(objectMapper.writeValueAsString(envelope)));
    }

    private void sendError(WebSocketSession session, String error) throws IOException {
        WsEnvelope e = WsEnvelope.type("ERROR");
        e.error = error;
        sendToSession(session, e);
    }
}
