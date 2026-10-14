package com.justchat.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.justchat.dto.WsEnvelope;
import com.justchat.metrics.ChatMetrics;
import com.justchat.model.Message;
import com.justchat.model.User;
import com.justchat.repository.GroupMemberRepository;
import com.justchat.repository.GroupRepository;
import com.justchat.repository.UserRepository;
import com.justchat.service.MessageService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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
    private final GroupMemberRepository groupMemberRepository = mock(GroupMemberRepository.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    private ChatWebSocketHandler handler;

    @BeforeEach
    void setUp() {
        handler = new ChatWebSocketHandler(registry, messageService, userRepository, groupMemberRepository, objectMapper,
                new ChatMetrics(meters, registry));
    }

    // ---- afterConnectionEstablished: registration + backlog replay ----

    @Test
    void connectingRegistersTheSessionAndSignalsBacklogDoneWhenNothingIsPending() throws Exception {
        WebSocketSession session = sessionWithAttributes(attrs("username", "bob"));
        userExists("bob", 2L);
        when(messageService.findUndelivered(2L)).thenReturn(List.of());

        handler.afterConnectionEstablished(session);

        WebSocketSession registered = registeredSession(2L);
        assertThat(registered).isInstanceOf(ConcurrentWebSocketSessionDecorator.class);
        assertThat(((ConcurrentWebSocketSessionDecorator) registered).getDelegate()).isSameAs(session);
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
        assertThat(counter("justchat.messages.delivered", "path", "backlog")).isEqualTo(1.0);
        assertThat(counter("justchat.ws.connections.opened")).isEqualTo(1.0);
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
        assertThat(counter("justchat.messages.sent", "kind", "direct")).isEqualTo(1.0);
        assertThat(counter("justchat.messages.delivered", "path", "live")).isEqualTo(1.0);
        assertThat(meters.get("justchat.messages.send.duration").timer().count()).isEqualTo(1);
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
        assertThat(counter("justchat.messages.sent", "kind", "direct")).isEqualTo(1.0);
        assertThat(counter("justchat.messages.delivered", "path", "live")).isZero();
    }

    @Test
    void sendingWithoutARecipientOrContentIsRejectedBeforePersisting() throws Exception {
        WebSocketSession senderSession = sessionWithAttributes(attrs("userId", 1L));

        handler.handleTextMessage(senderSession, json(Map.of("type", "SEND", "content", "hello")));

        List<WsEnvelope> sent = sentTo(senderSession);
        assertThat(sent).extracting(e -> e.type).containsExactly("ERROR");
        verifyNoInteractions(messageService);
        assertThat(counter("justchat.ws.client.errors")).isEqualTo(1.0);
        assertThat(counter("justchat.messages.sent", "kind", "direct")).isZero();
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
        assertThat(counter("justchat.ws.push.failures")).isEqualTo(1.0);
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
    void closingAConnectionUnregistersTheSameWrappedSessionThatWasRegistered() throws Exception {
        WebSocketSession session = sessionWithAttributes(attrs("username", "bob"));
        userExists("bob", 2L);
        when(messageService.findUndelivered(2L)).thenReturn(List.of());
        handler.afterConnectionEstablished(session);
        WebSocketSession registered = registeredSession(2L);

        handler.afterConnectionClosed(session, CloseStatus.NORMAL);

        verify(registry).unregister(2L, registered);
    }

    @Test
    void concurrentPushesToOneRecipientAreNeverWrittenToItsSocketAtTheSameTime() throws Exception {
        // Regression: a raw session written from two threads at once fails with
        // TEXT_PARTIAL_WRITING and is closed. This socket fails the same way on overlap.
        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger overlaps = new AtomicInteger();
        AtomicInteger written = new AtomicInteger();
        WebSocketSession bobRaw = sessionWithAttributes(attrs("username", "bob"));
        when(bobRaw.isOpen()).thenReturn(true);
        doAnswer(invocation -> {
            if (inFlight.incrementAndGet() > 1) overlaps.incrementAndGet();
            Thread.sleep(2);
            written.incrementAndGet();
            inFlight.decrementAndGet();
            return null;
        }).when(bobRaw).sendMessage(any());
        userExists("bob", 2L);
        when(messageService.findUndelivered(2L)).thenReturn(List.of());
        handler.afterConnectionEstablished(bobRaw);
        WebSocketSession bobOutbound = registeredSession(2L);
        when(registry.isOnline(2L)).thenReturn(true);
        when(registry.getSessions(2L)).thenReturn(Set.of(bobOutbound));
        when(messageService.persistNewMessage(eq(1L), eq(2L), any()))
                .thenAnswer(invocation -> messageWithId(5L, 1L, 2L, invocation.getArgument(2)));
        when(messageService.markDelivered(5L)).thenReturn(messageWithId(5L, 1L, 2L, "x"));

        int senders = 8;
        ExecutorService pool = Executors.newFixedThreadPool(senders);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> sends = new ArrayList<>();
        for (int i = 0; i < senders; i++) {
            WebSocketSession aliceTab = sessionWithAttributes(attrs("userId", 1L));
            TextMessage frame = json(Map.of("type", "SEND", "recipientId", 2, "content", "m" + i));
            sends.add(pool.submit(() -> {
                start.await();
                handler.handleTextMessage(aliceTab, frame);
                return null;
            }));
        }
        start.countDown();
        for (Future<?> send : sends) send.get(5, TimeUnit.SECONDS);
        pool.shutdown();

        assertThat(overlaps.get()).isZero();
        // 1 BACKLOG_DONE + one push per send, none dropped
        assertThat(written.get()).isEqualTo(1 + senders);
    }

    @Test
    void closingAConnectionWithoutAResolvedUserIdIsANoOp() {
        WebSocketSession session = sessionWithAttributes(new HashMap<>());

        handler.afterConnectionClosed(session, CloseStatus.NORMAL);

        verifyNoInteractions(registry);
    }

    // ---- handleTextMessage: SEND to a group ----

    @Test
    void sendingToAGroupStoresAGroupMessageAndPushesToEveryOtherMember() throws Exception {
        WebSocketSession aliceSession = sessionWithAttributes(attrs("userId", 1L));
        WebSocketSession bobSession = mock(WebSocketSession.class);
        Message saved = groupMessageWithId(5L, 1L, 7L, "hey all");
        when(messageService.persistNewGroupMessage(1L, 7L, "hey all")).thenReturn(saved);
        when(groupMemberRepository.findByGroupId(7L)).thenReturn(List.of(
                new com.justchat.model.GroupMembers(7L, 1L), new com.justchat.model.GroupMembers(7L, 2L)));
        when(registry.getSessions(2L)).thenReturn(Set.of(bobSession));

        handler.handleTextMessage(aliceSession, json(Map.of(
                "type", "SEND", "groupId", 7, "content", "hey all", "clientMsgId", "abc")));

        verify(messageService, never()).persistNewMessage(any(), any(), any());
        List<WsEnvelope> toBob = sentTo(bobSession);
        assertThat(toBob).hasSize(1);
        assertThat(toBob.get(0).groupId).isEqualTo(7L);
        assertThat(toBob.get(0).recipientId).isNull();
        assertThat(sentTo(aliceSession)).extracting(e -> e.clientMsgId).containsExactly("abc");
        verify(registry, never()).getSessions(1L);
    }

    @Test
    void aFailedPushToOneGroupMemberEvictsThatMembersSessionAndStillReachesTheRest() throws Exception {
        WebSocketSession aliceSession = sessionWithAttributes(attrs("userId", 1L));
        WebSocketSession deadBobSession = mock(WebSocketSession.class);
        WebSocketSession carolSession = mock(WebSocketSession.class);
        doThrow(new IOException("broken pipe")).when(deadBobSession).sendMessage(any());
        when(messageService.persistNewGroupMessage(1L, 7L, "hey")).thenReturn(groupMessageWithId(5L, 1L, 7L, "hey"));
        when(groupMemberRepository.findByGroupId(7L)).thenReturn(List.of(
                new com.justchat.model.GroupMembers(7L, 1L),
                new com.justchat.model.GroupMembers(7L, 2L),
                new com.justchat.model.GroupMembers(7L, 3L)));
        when(registry.getSessions(2L)).thenReturn(Set.of(deadBobSession));
        when(registry.getSessions(3L)).thenReturn(Set.of(carolSession));

        handler.handleTextMessage(aliceSession, json(Map.of("type", "SEND", "groupId", 7, "content", "hey")));

        verify(registry).unregister(2L, deadBobSession);
        assertThat(sentTo(carolSession)).hasSize(1);
    }

    // ---- handleTextMessage: READ ----

    @Test
    void readingAThreadMarksItReadAndPingsTheOnlineSenderForEachMessage() throws Exception {
        WebSocketSession bobSession = sessionWithAttributes(attrs("userId", 2L));
        WebSocketSession aliceSession = mock(WebSocketSession.class);
        Message first = messageWithId(10L, 1L, 2L, "hi");
        Message second = messageWithId(11L, 1L, 2L, "there");
        first.markRead();
        second.markRead();
        when(messageService.markThreadRead(2L, 1L, 11L)).thenReturn(List.of(first, second));
        when(registry.isOnline(1L)).thenReturn(true);
        when(registry.getSessions(1L)).thenReturn(Set.of(aliceSession));

        handler.handleTextMessage(bobSession, json(Map.of("type", "READ", "senderId", 1, "id", 11)));

        List<WsEnvelope> toAlice = sentTo(aliceSession);
        assertThat(toAlice).extracting(e -> e.type).containsExactly("STATUS_UPDATE", "STATUS_UPDATE");
        assertThat(toAlice).extracting(e -> e.id).containsExactly(10L, 11L);
        assertThat(toAlice).extracting(e -> e.status).containsOnly("READ");
        verify(bobSession, never()).sendMessage(any());
        assertThat(counter("justchat.messages.read")).isEqualTo(2.0);
    }

    @Test
    void readingWhenTheSenderIsOfflineStillMarksReadWithoutPinging() throws Exception {
        WebSocketSession bobSession = sessionWithAttributes(attrs("userId", 2L));
        Message message = messageWithId(10L, 1L, 2L, "hi");
        message.markRead();
        when(messageService.markThreadRead(2L, 1L, 10L)).thenReturn(List.of(message));
        when(registry.isOnline(1L)).thenReturn(false);

        handler.handleTextMessage(bobSession, json(Map.of("type", "READ", "senderId", 1, "id", 10)));

        verify(messageService).markThreadRead(2L, 1L, 10L);
        verify(registry, never()).getSessions(any());
    }

    @Test
    void readingWithoutASenderOrUpToIdIsRejected() throws Exception {
        WebSocketSession session = sessionWithAttributes(attrs("userId", 2L));

        handler.handleTextMessage(session, json(Map.of("type", "READ", "senderId", 1)));

        assertThat(sentTo(session)).extracting(e -> e.type).containsExactly("ERROR");
        verify(messageService, never()).markThreadRead(any(), any(), any());
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

    private static Message groupMessageWithId(Long id, Long senderId, Long groupId, String content) {
        Message message = Message.toGroup(senderId, groupId, content);
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

    private WebSocketSession registeredSession(Long userId) {
        var captor = org.mockito.ArgumentCaptor.forClass(WebSocketSession.class);
        verify(registry).register(eq(userId), captor.capture());
        return captor.getValue();
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

    private double counter(String name, String... tags) {
        return meters.get(name).tags(tags).counter().count();
    }
}
