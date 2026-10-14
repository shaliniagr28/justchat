package com.justchat.model;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "messages", indexes = {
        // Backlog query on reconnect: "give me everything not yet delivered to this user".
        @Index(name = "idx_recipient_status", columnList = "recipientId,status"),
        // Thread lookups in both directions (A->B and B->A make up one conversation).
        @Index(name = "idx_sender_recipient", columnList = "senderId,recipientId"),
        @Index(name = "idx_recipient_sender", columnList = "recipientId,senderId")
})
public class Message {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long senderId;

    // Null for group messages, which carry groupId instead. Keeping the two apart means every
    // 1:1 query (thread, backlog, read receipts) matches on recipientId and can never pick up
    // a group message just because a group id happens to equal a user id.
    private Long recipientId;

    private Long groupId;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private MessageStatus status = MessageStatus.SENT;

    @Column(nullable = false)
    private Instant sentAt = Instant.now();

    private Instant deliveredAt;

    private Instant readAt;

    public Message() {}

    public Message(Long senderId, Long recipientId, String content) {
        this.senderId = senderId;
        this.recipientId = recipientId;
        this.content = content;
    }

    public static Message toGroup(Long senderId, Long groupId, String content) {
        Message message = new Message(senderId, null, content);
        message.groupId = groupId;
        return message;
    }

    public Long getId() { return id; }
    public Long getSenderId() { return senderId; }
    public Long getRecipientId() { return recipientId; }
    public Long getGroupId() { return groupId; }
    public String getContent() { return content; }
    public MessageStatus getStatus() { return status; }
    public Instant getSentAt() { return sentAt; }
    public Instant getDeliveredAt() { return deliveredAt; }
    public Instant getReadAt() { return readAt; }

    public void markDelivered() {
        if (this.status == MessageStatus.SENT) {
            this.status = MessageStatus.DELIVERED;
            this.deliveredAt = Instant.now();
        }
    }

    // Read implies delivered, so a message the recipient somehow reads while still SENT
    // (e.g. seen via REST history before the backlog replay marked it) gets both stamps.
    public void markRead() {
        if (this.status == MessageStatus.READ) return;
        Instant now = Instant.now();
        if (this.deliveredAt == null) this.deliveredAt = now;
        this.status = MessageStatus.READ;
        this.readAt = now;
    }
}
