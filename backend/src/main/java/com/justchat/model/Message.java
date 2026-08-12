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

    @Column(nullable = false)
    private Long recipientId;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private MessageStatus status = MessageStatus.SENT;

    @Column(nullable = false)
    private Instant sentAt = Instant.now();

    private Instant deliveredAt;

    public Message() {}

    public Message(Long senderId, Long recipientId, String content) {
        this.senderId = senderId;
        this.recipientId = recipientId;
        this.content = content;
    }

    public Long getId() { return id; }
    public Long getSenderId() { return senderId; }
    public Long getRecipientId() { return recipientId; }
    public String getContent() { return content; }
    public MessageStatus getStatus() { return status; }
    public Instant getSentAt() { return sentAt; }
    public Instant getDeliveredAt() { return deliveredAt; }

    public void markDelivered() {
        if (this.status == MessageStatus.SENT) {
            this.status = MessageStatus.DELIVERED;
            this.deliveredAt = Instant.now();
        }
    }
}
