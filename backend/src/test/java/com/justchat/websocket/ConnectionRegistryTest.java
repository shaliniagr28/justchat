package com.justchat.websocket;

import org.junit.jupiter.api.Test;
import org.springframework.web.socket.WebSocketSession;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ConnectionRegistryTest {

    private final ConnectionRegistry registry = new ConnectionRegistry();

    @Test
    void unknownUserIsNotOnlineAndHasNoSessions() {
        assertThat(registry.isOnline(1L)).isFalse();
        assertThat(registry.getSessions(1L)).isEmpty();
    }

    @Test
    void registeringASessionMakesTheUserOnline() {
        WebSocketSession session = mock(WebSocketSession.class);

        registry.register(1L, session);

        assertThat(registry.isOnline(1L)).isTrue();
        assertThat(registry.getSessions(1L)).containsExactly(session);
    }

    @Test
    void aUserCanHaveMultipleSessionsAtOnceForMultipleTabsOrDevices() {
        WebSocketSession tab1 = mock(WebSocketSession.class);
        WebSocketSession tab2 = mock(WebSocketSession.class);

        registry.register(1L, tab1);
        registry.register(1L, tab2);

        assertThat(registry.getSessions(1L)).containsExactlyInAnyOrder(tab1, tab2);
    }

    @Test
    void unregisteringOneSessionLeavesOtherSessionsForTheSameUserIntact() {
        WebSocketSession tab1 = mock(WebSocketSession.class);
        WebSocketSession tab2 = mock(WebSocketSession.class);
        registry.register(1L, tab1);
        registry.register(1L, tab2);

        registry.unregister(1L, tab1);

        assertThat(registry.isOnline(1L)).isTrue();
        assertThat(registry.getSessions(1L)).containsExactly(tab2);
    }

    @Test
    void unregisteringTheLastSessionMakesTheUserOffline() {
        WebSocketSession session = mock(WebSocketSession.class);
        registry.register(1L, session);

        registry.unregister(1L, session);

        assertThat(registry.isOnline(1L)).isFalse();
        assertThat(registry.getSessions(1L)).isEmpty();
    }

    @Test
    void unregisteringASessionForAnUnknownUserIsANoOp() {
        WebSocketSession session = mock(WebSocketSession.class);

        registry.unregister(99L, session);

        assertThat(registry.isOnline(99L)).isFalse();
    }

    @Test
    void unregisteringASessionThatWasNeverRegisteredIsANoOp() {
        WebSocketSession registered = mock(WebSocketSession.class);
        WebSocketSession neverRegistered = mock(WebSocketSession.class);
        registry.register(1L, registered);

        registry.unregister(1L, neverRegistered);

        assertThat(registry.getSessions(1L)).containsExactly(registered);
    }

    @Test
    void differentUsersAreTrackedIndependently() {
        WebSocketSession sessionA = mock(WebSocketSession.class);
        WebSocketSession sessionB = mock(WebSocketSession.class);

        registry.register(1L, sessionA);
        registry.register(2L, sessionB);
        registry.unregister(1L, sessionA);

        assertThat(registry.isOnline(1L)).isFalse();
        assertThat(registry.isOnline(2L)).isTrue();
        assertThat(registry.getSessions(2L)).containsExactly(sessionB);
    }
}
