package com.justchat.websocket;

import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * The core piece of state: who is currently connected,
 * and which live socket(s) belong to them.
 *
 * - This registry is single-instance/in-memory. It is explicitly NOT the answer once
 *   there's more than one backend process (Docker Compose --scale, real horizontal
 *   scaling): userId -> session only resolves connections held by THIS JVM. A message
 *   for a user connected to a different instance would look "offline" here even though
 *   they're online elsewhere. The real fix is a shared layer (Redis pub/sub, or a
 *   message broker) that every instance publishes to and subscribes from, replacing
 *   "is this user in my local map" with "is this user in ANY instance's map, and if so
 *   which one do I forward to". Deliberately out of scope for this MVP - noted here
 *   rather than pretending the registry generalizes.
 */
@Component
public class ConnectionRegistry {

    private final ConcurrentHashMap<Long, Set<WebSocketSession>> sessionsByUserId = new ConcurrentHashMap<>();

    public void register(Long userId, WebSocketSession session) {
        sessionsByUserId
                .computeIfAbsent(userId, id -> new CopyOnWriteArraySet<>())
                .add(session);
    }

    public void unregister(Long userId, WebSocketSession session) {
        Set<WebSocketSession> sessions = sessionsByUserId.get(userId);
        if (sessions == null) return;
        sessions.remove(session);
        if (sessions.isEmpty()) {
            sessionsByUserId.remove(userId, sessions);
        }
    }

    public boolean isOnline(Long userId) {
        Set<WebSocketSession> sessions = sessionsByUserId.get(userId);
        return sessions != null && !sessions.isEmpty();
    }

    public Set<WebSocketSession> getSessions(Long userId) {
        return sessionsByUserId.getOrDefault(userId, Set.of());
    }
}
