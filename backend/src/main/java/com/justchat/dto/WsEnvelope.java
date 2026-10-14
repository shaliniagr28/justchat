package com.justchat.dto;

import com.justchat.model.Message;


/**
 * Every WS frame in both directions is one JSON object with a discriminating "type" field.
 *
 * Client -> server:
 *   SEND       { type, recipientId | groupId, content, clientMsgId }
 *   READ       { type, senderId, id }   -- "I've read senderId's messages up to and including id"
 *
 * Server -> client:
 *   MESSAGE        { type, id, senderId, recipientId | groupId, content, status, sentAt, system, clientMsgId }
 *                  (system = true for group events like "alice added bob", shown as a line, not a bubble)
 *   STATUS_UPDATE  { type, id, status }
 *   GROUP_ADDED    { type, groupId }   -- you were added to this group; refresh your group list
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
    public boolean system;

    // Echoed back on MESSAGE so the sending client can reconcile its optimistic local
    // render with the server-confirmed row (match by clientMsgId, then swap in real id).
    public String clientMsgId;

    public WsEnvelope() {}

    public static WsEnvelope type(String type) {
        WsEnvelope e = new WsEnvelope();
        e.type = type;
        return e;
    }

    public static WsEnvelope fromMessage(Message m) {
        WsEnvelope e = type("MESSAGE");
        e.id = m.getId();
        e.senderId = m.getSenderId();
        e.recipientId = m.getRecipientId();
        e.groupId = m.getGroupId();
        e.content = m.getContent();
        e.status = m.getStatus().name();
        e.sentAt = m.getSentAt().toString();
        e.system = m.isSystemMessage();
        return e;
    }

    public static WsEnvelope groupAdded(Long groupId) {
        WsEnvelope e = type("GROUP_ADDED");
        e.groupId = groupId;
        return e;
    }
}
