package com.justchat.websocket;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.WebSocketHandler;

import java.security.Principal;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthHandshakeInterceptorTest {

    @Mock
    private ServerHttpRequest request;

    @Mock
    private ServerHttpResponse response;

    @Mock
    private WebSocketHandler wsHandler;

    private final AuthHandshakeInterceptor interceptor = new AuthHandshakeInterceptor();

    @Test
    void rejectsTheHandshakeWithUnauthorizedWhenThereIsNoPrincipal() {
        when(request.getPrincipal()).thenReturn(null);
        Map<String, Object> attributes = new HashMap<>();

        boolean proceed = interceptor.beforeHandshake(request, response, wsHandler, attributes);

        assertThat(proceed).isFalse();
        verify(response).setStatusCode(HttpStatus.UNAUTHORIZED);
        assertThat(attributes).isEmpty();
    }

    @Test
    void allowsTheHandshakeAndStashesTheUsernameWhenAPrincipalIsPresent() {
        Principal principal = () -> "alice";
        when(request.getPrincipal()).thenReturn(principal);
        Map<String, Object> attributes = new HashMap<>();

        boolean proceed = interceptor.beforeHandshake(request, response, wsHandler, attributes);

        assertThat(proceed).isTrue();
        assertThat(attributes).containsEntry("username", "alice");
    }
}
