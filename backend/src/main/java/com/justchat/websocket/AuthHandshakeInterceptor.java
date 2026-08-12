package com.justchat.websocket;

import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.security.Principal;
import java.util.Map;

/**
 * The gotcha this exists to prevent: SecurityConfig already requires authentication on
 * the /ws/** path via the normal HTTP filter chain (the handshake starts life as a plain
 * GET request, so Spring Security sees it same as any other endpoint). That alone is
 * enough to reject unauthenticated upgrades with a 401 before this class ever runs.
 *
 * This interceptor is deliberate defense-in-depth on top of that, for two reasons:
 * 1) it's the kind of check that's easy to accidentally remove later (e.g. someone
 *    loosens the security config for an unrelated endpoint and typos the WS path into
 *    the permit-all list) - failing closed here again costs nothing.
 * 2) it's where we grab the authenticated username off the handshake request and stash
 *    it as a session attribute, since WebSocketSession doesn't expose the servlet
 *    request/principal directly once the connection is upgraded.
 */
@Component
public class AuthHandshakeInterceptor implements HandshakeInterceptor {

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                    WebSocketHandler wsHandler, Map<String, Object> attributes) {
        Principal principal = request.getPrincipal();
        if (principal == null) {
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }
        attributes.put("username", principal.getName());
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                WebSocketHandler wsHandler, Exception exception) {
        // Nothing to do - cleanup on close is handled by ChatWebSocketHandler, not here.
    }
}
