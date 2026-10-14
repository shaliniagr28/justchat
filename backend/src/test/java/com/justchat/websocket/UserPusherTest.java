package com.justchat.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.justchat.dto.WsEnvelope;
import com.justchat.metrics.ChatMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class UserPusherTest {

    private final ConnectionRegistry registry = mock(ConnectionRegistry.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private UserPusher pusher;

    @BeforeEach
    void setUp() {
        pusher = new UserPusher(registry, objectMapper, new ChatMetrics(meters, registry));
    }

    @Test
    void pushesToEverySessionOfEachUser() throws Exception {
        WebSocketSession bobTab1 = mock(WebSocketSession.class);
        WebSocketSession bobTab2 = mock(WebSocketSession.class);
        WebSocketSession carol = mock(WebSocketSession.class);
        when(registry.getSessions(2L)).thenReturn(Set.of(bobTab1, bobTab2));
        when(registry.getSessions(3L)).thenReturn(Set.of(carol));

        pusher.pushToUsers(List.of(2L, 3L), WsEnvelope.groupAdded(7L));

        for (WebSocketSession s : List.of(bobTab1, bobTab2, carol)) {
            var captor = org.mockito.ArgumentCaptor.forClass(TextMessage.class);
            verify(s).sendMessage(captor.capture());
            WsEnvelope sent = objectMapper.readValue(captor.getValue().getPayload(), WsEnvelope.class);
            assertThat(sent.type).isEqualTo("GROUP_ADDED");
            assertThat(sent.groupId).isEqualTo(7L);
        }
    }

    @Test
    void aFailingSessionIsEvictedAndTheOthersStillReceive() throws Exception {
        WebSocketSession dead = mock(WebSocketSession.class);
        WebSocketSession healthy = mock(WebSocketSession.class);
        doThrow(new IOException("broken pipe")).when(dead).sendMessage(any());
        when(registry.getSessions(2L)).thenReturn(Set.of(dead));
        when(registry.getSessions(3L)).thenReturn(Set.of(healthy));

        pusher.pushToUsers(List.of(2L, 3L), WsEnvelope.groupAdded(7L));

        verify(registry).unregister(2L, dead);
        verify(healthy, times(1)).sendMessage(any());
        assertThat(meters.get("justchat.ws.push.failures").counter().count()).isEqualTo(1.0);
    }
}
