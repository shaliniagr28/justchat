package com.justchat.dto;


/**
 * Every WS frame in both directions is one JSON object with a discriminating "type" field.
 *
 * Client -> server:
 *   SEND       { type, recipientId | groupId, content, clientMsgId }
 *   READ       { type, senderId, id }   -- "I've read senderId's messages up to and including id"
 *
 * Server -> client:
 *   MESSAGE        { type, id, senderId, recipientId | groupId, content, status, sentAt, clientMsgId }
 *   STATUS_UPDATE  { type, id, status }
 *   BACKLOG_DONE   { type }   -- sentinel marking end of the reconnect backlog replay
 *   ERROR          { type, error }
 */
public class WsEnvelope {

    public String type;
    public Long id;
    public Long senderId;
    public Long recipientId;
    public String content;
    public String status;
    public String sentAt;
    public String error;
    public Long groupId;

    // Echoed back on MESSAGE so the sending client can reconcile its optimistic local
    // render with the server-confirmed row (match by clientMsgId, then swap in real id).
    public String clientMsgId;

    public WsEnvelope() {}

    public static WsEnvelope type(String type) {
        WsEnvelope e = new WsEnvelope();
        e.type = type;
        return e;
    }
}
