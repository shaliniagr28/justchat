package com.justchat.websocket;

import com.justchat.dto.WsEnvelope;
import com.justchat.metrics.ChatMetrics;
import com.justchat.model.Message;
import com.justchat.model.User;
import com.justchat.repository.UserRepository;
import com.justchat.service.GroupService;
import com.justchat.service.MessageService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.SessionLimitExceededException;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.List;

@Component
public class ChatWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(ChatWebSocketHandler.class);

    // A raw WebSocketSession is not safe for concurrent sendMessage calls: a sender's own
    // echo and a push from another user's thread can hit the same session at once, and
    // Tomcat then fails with TEXT_PARTIAL_WRITING and closes the connection. Every write
    // therefore goes through one ConcurrentWebSocketSessionDecorator per connection,
    // stored under this attribute and also the instance held in ConnectionRegistry.
    static final String OUTBOUND_SESSION = "outboundSession";
    private static final int SEND_TIME_LIMIT_MS = 10_000;
    private static final int SEND_BUFFER_LIMIT_BYTES = 512 * 1024;

    private final ConnectionRegistry registry;
    private final MessageService messageService;
    private final ObjectMapper objectMapper;
    private final UserRepository userRepository;
    private final GroupService groupService;
    private final UserPusher pusher;
    private final ChatMetrics metrics;

    public ChatWebSocketHandler(ConnectionRegistry registry, MessageService messageService,
            UserRepository userRepository, GroupService groupService, UserPusher pusher, ObjectMapper objectMapper,
            ChatMetrics metrics) {
        this.registry = registry;
        this.messageService = messageService;
        this.userRepository = userRepository;
        this.groupService = groupService;
        this.pusher = pusher;
        this.objectMapper = objectMapper;
        this.metrics = metrics;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        Long userId = resolveUserId(session);
        WebSocketSession outbound = new ConcurrentWebSocketSessionDecorator(
                session, SEND_TIME_LIMIT_MS, SEND_BUFFER_LIMIT_BYTES);
        session.getAttributes().put("userId", userId);
        session.getAttributes().put(OUTBOUND_SESSION, outbound);
        registry.register(userId, outbound);
        metrics.connectionOpened();
        log.info("WS connected: userId={} sessionId={}", userId, session.getId());

        List<Message> backlog = messageService.findUndelivered(userId);
        for (Message m : backlog) {
            sendToSession(outbound, WsEnvelope.fromMessage(m));
            Message updated = messageService.markDelivered(m.getId());
            metrics.deliveredFromBacklog();
            notifySenderOfStatus(updated);
        }
        sendToSession(outbound, WsEnvelope.type("BACKLOG_DONE"));
    }

    @Override
    protected void handleTextMessage(WebSocketSession rawSession, TextMessage message) throws Exception {
        WebSocketSession session = outbound(rawSession);
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
            case "READ" -> handleRead(session, userId, in);
            default -> sendError(session, "Unknown type: " + in.type);
        }
    }

    // Membership is checked before persisting, so a hand-crafted SEND from a non-member is
    // rejected with nothing stored or pushed. Fan-out reads the member list per send.
    private void handleGroupSend(WebSocketSession session, Long senderId, Long groupId, WsEnvelope in) throws IOException {
        if (in.content == null || in.content.isBlank()) {
            sendError(session, "SEND requires content");
            return;
        }
        if (!groupService.isMember(groupId, senderId)) {
            sendError(session, "Not a member of this group");
            return;
        }
        Message saved = messageService.persistNewGroupMessage(senderId, groupId, in.content);
        metrics.groupMessageSent();
        WsEnvelope echo = WsEnvelope.fromMessage(saved);
        echo.clientMsgId = in.clientMsgId;
        sendToSession(session, echo);
        List<Long> otherMembers = groupService.memberIds(groupId).stream()
                .filter(id -> !id.equals(senderId))
                .toList();
        pusher.pushToUsers(otherMembers, WsEnvelope.fromMessage(saved));
    }

    private void handleSend(WebSocketSession session, Long senderId, WsEnvelope in) throws IOException {
        Timer.Sample sample = Timer.start();
        try {
            routeSend(session, senderId, in);
        } finally {
            sample.stop(metrics.sendLatency());
        }
    }

    private void routeSend(WebSocketSession session, Long senderId, WsEnvelope in) throws IOException {

        if (in.groupId != null) {
            handleGroupSend(session, senderId, in.groupId, in);
            return;
        }
        if (in.recipientId == null || in.content == null || in.content.isBlank()) {
            sendError(session, "SEND requires recipientId and content");
            return;
        }

        // Persist first, push second - this
        // ordering matters (a crash between the two degrades to "delivered late" via
        // the
        // backlog path on next connect, rather than the message being lost outright).
        Message saved = messageService.persistNewMessage(senderId, in.recipientId, in.content);
        metrics.directMessageSent();

        // Echo back to the sender with the server-assigned id, so the sender's
        // optimistic
        // local render (shown instantly on send) can be reconciled with the real row
        // via
        // clientMsgId, rather than trusting its own guess at an id.
        WsEnvelope echo = WsEnvelope.fromMessage(saved);
        echo.clientMsgId = in.clientMsgId;
        sendToSession(session, echo);

        if (registry.isOnline(in.recipientId)) {
            WsEnvelope push = WsEnvelope.fromMessage(saved);
            boolean delivered = false;
            for (WebSocketSession recipientSession : registry.getSessions(in.recipientId)) {
                try {
                    recipientSession.sendMessage(new TextMessage(objectMapper.writeValueAsString(push)));
                    delivered = true;
                } catch (IOException | SessionLimitExceededException e) {
                    log.warn("Failed pushing to a session for userId={}, evicting it", in.recipientId);
                    metrics.pushFailed();
                    registry.unregister(in.recipientId, recipientSession);
                }
            }
            if (delivered) {
                Message updated = messageService.markDelivered(saved.getId());
                metrics.deliveredLive();
                notifySenderOfStatus(updated);
            }
        }
    }

    // The client sends READ only while the thread is open in a focused, visible window -
    // the server trusts that signal and just records it. Scoped by recipientId = userId in
    // the query, so a client can only mark messages addressed to itself.
    private void handleRead(WebSocketSession session, Long readerId, WsEnvelope in) throws IOException {
        if (in.senderId == null || in.id == null) {
            sendError(session, "READ requires senderId and id");
            return;
        }
        List<Message> read = messageService.markThreadRead(readerId, in.senderId, in.id);
        metrics.messagesRead(read.size());
        for (Message m : read) {
            notifySenderOfStatus(m);
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
            } catch (IOException | SessionLimitExceededException e) {
                registry.unregister(message.getSenderId(), senderSession);
            }
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        Long userId = (Long) session.getAttributes().get("userId");
        if (userId != null) {
            registry.unregister(userId, outbound(session));
            log.info("WS closed: userId={} sessionId={} status={}", userId, session.getId(), status);
        }
    }

    private static WebSocketSession outbound(WebSocketSession session) {
        Object outbound = session.getAttributes().get(OUTBOUND_SESSION);
        return outbound instanceof WebSocketSession s ? s : session;
    }

    private Long resolveUserId(WebSocketSession session) {
        String username = (String) session.getAttributes().get("username");
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalStateException("Authenticated username not found: " + username));
        return user.getId();
    }

    private void sendToSession(WebSocketSession session, WsEnvelope envelope) throws IOException {
        session.sendMessage(new TextMessage(objectMapper.writeValueAsString(envelope)));
    }

    private void sendError(WebSocketSession session, String error) throws IOException {
        metrics.clientError();
        WsEnvelope e = WsEnvelope.type("ERROR");
        e.error = error;
        sendToSession(session, e);
    }
}
