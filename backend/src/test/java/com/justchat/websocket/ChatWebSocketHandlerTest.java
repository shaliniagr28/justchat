package com.justchat.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.justchat.dto.WsEnvelope;
import com.justchat.model.Message;
import com.justchat.model.User;
import com.justchat.repository.UserRepository;
import com.justchat.service.MessageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Covers the hand-built routing/delivery logic that is the actual graded core of this
 * project (see the class doc on ChatWebSocketHandler) - not the WebSocket transport
 * itself, which is Spring's.
 */
class ChatWebSocketHandlerTest {

    private final ConnectionRegistry registry = mock(ConnectionRegistry.class);
    private final MessageService messageService = mock(MessageService.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    private ChatWebSocketHandler handler;

    @BeforeEach
    void setUp() {
        handler = new ChatWebSocketHandler(registry, messageService, userRepository, objectMapper);
    }

    // ---- afterConnectionEstablished: registration + backlog replay ----

    @Test
    void connectingRegistersTheSessionAndSignalsBacklogDoneWhenNothingIsPending() throws Exception {
        WebSocketSession session = sessionWithAttributes(attrs("username", "bob"));
        userExists("bob", 2L);
        when(messageService.findUndelivered(2L)).thenReturn(List.of());

        handler.afterConnectionEstablished(session);

        verify(registry).register(2L, session);
        assertThat(session.getAttributes()).containsEntry("userId", 2L);
        List<WsEnvelope> sent = sentTo(session);
        assertThat(sent).extracting(e -> e.type).containsExactly("BACKLOG_DONE");
        verify(messageService, never()).markDelivered(any());
    }

    @Test
    void connectingReplaysUndeliveredBacklogAndNotifiesAnOnlineSenderOfDelivery() throws Exception {
        WebSocketSession bobSession = sessionWithAttributes(attrs("username", "bob"));
        WebSocketSession aliceSession = mock(WebSocketSession.class);
        userExists("bob", 2L);

        Message backlogItem = messageWithId(10L, 1L, 2L, "hi");
        when(messageService.findUndelivered(2L)).thenReturn(List.of(backlogItem));
        when(messageService.markDelivered(10L)).thenAnswer(invocation -> {
            backlogItem.markDelivered();
            return backlogItem;
        });
        when(registry.isOnline(1L)).thenReturn(true);
        when(registry.getSessions(1L)).thenReturn(Set.of(aliceSession));

        handler.afterConnectionEstablished(bobSession);

        List<WsEnvelope> toBob = sentTo(bobSession);
        assertThat(toBob).extracting(e -> e.type).containsExactly("MESSAGE", "BACKLOG_DONE");
        assertThat(toBob.get(0).id).isEqualTo(10L);

        List<WsEnvelope> toAlice = sentTo(aliceSession);
        assertThat(toAlice).hasSize(1);
        assertThat(toAlice.get(0).type).isEqualTo("STATUS_UPDATE");
        assertThat(toAlice.get(0).id).isEqualTo(10L);
        assertThat(toAlice.get(0).status).isEqualTo("DELIVERED");
    }

    @Test
    void connectingReplaysBacklogWithoutFailingWhenTheOriginalSenderIsOffline() throws Exception {
        WebSocketSession bobSession = sessionWithAttributes(attrs("username", "bob"));
        userExists("bob", 2L);

        Message backlogItem = messageWithId(10L, 1L, 2L, "hi");
        when(messageService.findUndelivered(2L)).thenReturn(List.of(backlogItem));
        when(messageService.markDelivered(10L)).thenReturn(backlogItem);
        when(registry.isOnline(1L)).thenReturn(false);

        handler.afterConnectionEstablished(bobSession);

        verify(registry, never()).getSessions(1L);
        assertThat(sentTo(bobSession)).extracting(e -> e.type).containsExactly("MESSAGE", "BACKLOG_DONE");
    }

    // ---- handleTextMessage: SEND ----

    @Test
    void sendingToAnOnlineRecipientDeliversImmediatelyAndPingsTheSenderWithStatus() throws Exception {
        WebSocketSession senderSession = sessionWithAttributes(attrs("userId", 1L));
        WebSocketSession recipientSession = mock(WebSocketSession.class);

        Message saved = messageWithId(5L, 1L, 2L, "hello");
        when(messageService.persistNewMessage(1L, 2L, "hello")).thenReturn(saved);
        when(registry.isOnline(2L)).thenReturn(true);
        when(registry.getSessions(2L)).thenReturn(Set.of(recipientSession));
        when(messageService.markDelivered(5L)).thenAnswer(invocation -> {
            saved.markDelivered();
            return saved;
        });
        when(registry.isOnline(1L)).thenReturn(true);
        when(registry.getSessions(1L)).thenReturn(Set.of(senderSession));

        handler.handleTextMessage(senderSession, json(Map.of(
                "type", "SEND", "recipientId", 2, "content", "hello", "clientMsgId", "abc")));

        List<WsEnvelope> toSender = sentTo(senderSession);
        assertThat(toSender).extracting(e -> e.type).containsExactly("MESSAGE", "STATUS_UPDATE");
        assertThat(toSender.get(0).clientMsgId).isEqualTo("abc");
        assertThat(toSender.get(0).id).isEqualTo(5L);
        assertThat(toSender.get(1).status).isEqualTo("DELIVERED");

        List<WsEnvelope> toRecipient = sentTo(recipientSession);
        assertThat(toRecipient).hasSize(1);
        assertThat(toRecipient.get(0).type).isEqualTo("MESSAGE");
        assertThat(toRecipient.get(0).id).isEqualTo(5L);

        verify(messageService).persistNewMessage(1L, 2L, "hello");
        verify(messageService).markDelivered(5L);
    }

    @Test
    void sendingToAnOfflineRecipientPersistsAndEchoesButDoesNotPushOrMarkDelivered() throws Exception {
        WebSocketSession senderSession = sessionWithAttributes(attrs("userId", 1L));
        Message saved = messageWithId(5L, 1L, 2L, "hello");
        when(messageService.persistNewMessage(1L, 2L, "hello")).thenReturn(saved);
        when(registry.isOnline(2L)).thenReturn(false);

        handler.handleTextMessage(senderSession, json(Map.of(
                "type", "SEND", "recipientId", 2, "content", "hello", "clientMsgId", "abc")));

        assertThat(sentTo(senderSession)).extracting(e -> e.type).containsExactly("MESSAGE");
        verify(messageService, never()).markDelivered(any());
        verify(registry, never()).getSessions(2L);
    }

    @Test
    void sendingWithoutARecipientOrContentIsRejectedBeforePersisting() throws Exception {
        WebSocketSession senderSession = sessionWithAttributes(attrs("userId", 1L));

        handler.handleTextMessage(senderSession, json(Map.of("type", "SEND", "content", "hello")));

        List<WsEnvelope> sent = sentTo(senderSession);
        assertThat(sent).extracting(e -> e.type).containsExactly("ERROR");
        verifyNoInteractions(messageService);
    }

    @Test
    void sendingBlankContentIsRejectedBeforePersisting() throws Exception {
        WebSocketSession senderSession = sessionWithAttributes(attrs("userId", 1L));

        handler.handleTextMessage(senderSession, json(Map.of("type", "SEND", "recipientId", 2, "content", "   ")));

        assertThat(sentTo(senderSession)).extracting(e -> e.type).containsExactly("ERROR");
        verifyNoInteractions(messageService);
    }

    @Test
    void aDeadRecipientSessionIsEvictedButDoesNotStopDeliveryToAHealthyOne() throws Exception {
        WebSocketSession senderSession = sessionWithAttributes(attrs("userId", 1L));
        WebSocketSession deadRecipientSession = mock(WebSocketSession.class);
        WebSocketSession liveRecipientSession = mock(WebSocketSession.class);
        doThrow(new IOException("connection reset")).when(deadRecipientSession).sendMessage(any());

        Message saved = messageWithId(5L, 1L, 2L, "hello");
        when(messageService.persistNewMessage(1L, 2L, "hello")).thenReturn(saved);
        when(registry.isOnline(2L)).thenReturn(true);
        when(registry.getSessions(2L)).thenReturn(Set.of(deadRecipientSession, liveRecipientSession));
        when(messageService.markDelivered(5L)).thenReturn(saved);
        when(registry.isOnline(1L)).thenReturn(false);

        handler.handleTextMessage(senderSession, json(Map.of(
                "type", "SEND", "recipientId", 2, "content", "hello")));

        verify(registry).unregister(2L, deadRecipientSession);
        verify(registry, never()).unregister(2L, liveRecipientSession);
        verify(messageService).markDelivered(5L);
    }

    // ---- handleTextMessage: protocol-level errors ----

    @Test
    void malformedJsonProducesAnErrorInsteadOfPropagatingTheParseException() throws Exception {
        WebSocketSession session = sessionWithAttributes(attrs("userId", 1L));

        handler.handleTextMessage(session, new TextMessage("not valid json"));

        List<WsEnvelope> sent = sentTo(session);
        assertThat(sent).hasSize(1);
        assertThat(sent.get(0).type).isEqualTo("ERROR");
        assertThat(sent.get(0).error).isEqualTo("Malformed message");
    }

    @Test
    void aFrameWithoutATypeIsRejected() throws Exception {
        WebSocketSession session = sessionWithAttributes(attrs("userId", 1L));

        handler.handleTextMessage(session, json(Map.of("recipientId", 2, "content", "hi")));

        List<WsEnvelope> sent = sentTo(session);
        assertThat(sent.get(0).error).isEqualTo("Missing type");
    }

    @Test
    void anUnrecognizedTypeIsRejected() throws Exception {
        WebSocketSession session = sessionWithAttributes(attrs("userId", 1L));

        handler.handleTextMessage(session, json(Map.of("type", "PING")));

        List<WsEnvelope> sent = sentTo(session);
        assertThat(sent.get(0).type).isEqualTo("ERROR");
        assertThat(sent.get(0).error).isEqualTo("Unknown type: PING");
    }

    // ---- afterConnectionClosed ----

    @Test
    void closingAConnectionUnregistersItFromTheRegistry() {
        WebSocketSession session = sessionWithAttributes(attrs("userId", 1L));

        handler.afterConnectionClosed(session, CloseStatus.NORMAL);

        verify(registry).unregister(1L, session);
    }

    @Test
    void closingAConnectionWithoutAResolvedUserIdIsANoOp() {
        WebSocketSession session = sessionWithAttributes(new HashMap<>());

        handler.afterConnectionClosed(session, CloseStatus.NORMAL);

        verifyNoInteractions(registry);
    }

    // ---- helpers ----

    private void userExists(String username, Long id) {
        User user = new User(username, "hashed");
        setField(user, "id", id);
        when(userRepository.findByUsername(username)).thenReturn(Optional.of(user));
    }

    private static Message messageWithId(Long id, Long senderId, Long recipientId, String content) {
        Message message = new Message(senderId, recipientId, content);
        setField(message, "id", id);
        return message;
    }

    private static void setField(Object target, String fieldName, Object value) {
        try {
            var field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    private static Map<String, Object> attrs(String key, Object value) {
        Map<String, Object> attributes = new HashMap<>();
        attributes.put(key, value);
        return attributes;
    }

    private static WebSocketSession sessionWithAttributes(Map<String, Object> attributes) {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getAttributes()).thenReturn(attributes);
        return session;
    }

    private TextMessage json(Map<String, Object> fields) throws IOException {
        return new TextMessage(objectMapper.writeValueAsString(fields));
    }

    private List<WsEnvelope> sentTo(WebSocketSession session) throws IOException {
        var captor = org.mockito.ArgumentCaptor.forClass(TextMessage.class);
        verify(session, atLeastOnce()).sendMessage(captor.capture());
        List<WsEnvelope> envelopes = new ArrayList<>();
        for (TextMessage message : captor.getAllValues()) {
            envelopes.add(objectMapper.readValue(message.getPayload(), WsEnvelope.class));
        }
        return envelopes;
    }
}
