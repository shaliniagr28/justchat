package com.justchat.metrics;

import com.justchat.websocket.ConnectionRegistry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

/**
 * App-level metrics for the hand-built real-time layer, exposed on /actuator/prometheus
 * alongside Spring's built-in HTTP/JVM metrics. Kept in one place so the handler only
 * makes one-line calls and metric names stay consistent with the Grafana dashboard
 * (monitoring/grafana/provisioning/dashboards/justchat.json).
 */
@Component
public class ChatMetrics {

    private final Counter connectionsOpened;
    private final Counter directMessagesSent;
    private final Counter groupMessagesSent;
    private final Counter deliveredLive;
    private final Counter deliveredBacklog;
    private final Counter messagesRead;
    private final Counter pushFailures;
    private final Counter clientErrors;
    private final Timer sendLatency;

    public ChatMetrics(MeterRegistry meters, ConnectionRegistry connections) {
        Gauge.builder("justchat.ws.sessions.active", connections, ConnectionRegistry::sessionCount)
                .description("Open WebSocket sessions (a user with two tabs counts twice)")
                .register(meters);
        Gauge.builder("justchat.users.online", connections, ConnectionRegistry::onlineUserCount)
                .description("Distinct users with at least one open WebSocket session")
                .register(meters);

        connectionsOpened = Counter.builder("justchat.ws.connections.opened")
                .description("WebSocket connections established, including reconnects")
                .register(meters);
        directMessagesSent = Counter.builder("justchat.messages.sent")
                .tag("kind", "direct").register(meters);
        groupMessagesSent = Counter.builder("justchat.messages.sent")
                .tag("kind", "group").register(meters);
        deliveredLive = Counter.builder("justchat.messages.delivered")
                .description("Messages flipped to DELIVERED")
                .tag("path", "live").register(meters);
        deliveredBacklog = Counter.builder("justchat.messages.delivered")
                .description("Messages flipped to DELIVERED")
                .tag("path", "backlog").register(meters);
        messagesRead = Counter.builder("justchat.messages.read")
                .description("Messages flipped to READ")
                .register(meters);
        pushFailures = Counter.builder("justchat.ws.push.failures")
                .description("Failed pushes to a recipient session (session evicted)")
                .register(meters);
        clientErrors = Counter.builder("justchat.ws.client.errors")
                .description("ERROR frames sent back to clients for bad input")
                .register(meters);
        sendLatency = Timer.builder("justchat.messages.send.duration")
                .description("Server-side time to persist and fan out one SEND frame")
                .publishPercentileHistogram()
                .register(meters);
    }

    public void connectionOpened() { connectionsOpened.increment(); }
    public void directMessageSent() { directMessagesSent.increment(); }
    public void groupMessageSent() { groupMessagesSent.increment(); }
    public void deliveredLive() { deliveredLive.increment(); }
    public void deliveredFromBacklog() { deliveredBacklog.increment(); }
    public void messagesRead(int count) { messagesRead.increment(count); }
    public void pushFailed() { pushFailures.increment(); }
    public void clientError() { clientErrors.increment(); }
    public Timer sendLatency() { return sendLatency; }
}
