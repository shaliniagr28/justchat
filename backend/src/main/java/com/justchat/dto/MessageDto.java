package com.justchat.dto;

import com.justchat.model.Message;

public class MessageDto {
    public Long id;
    public Long senderId;
    public Long recipientId;
    public Long groupId;
    public boolean system;
    public String content;
    public String status;
    public String sentAt;

    public MessageDto(Message m) {
        this.id = m.getId();
        this.senderId = m.getSenderId();
        this.recipientId = m.getRecipientId();
        this.groupId = m.getGroupId();
        this.system = m.isSystemMessage();
        this.content = m.getContent();
        this.status = m.getStatus().name();
        this.sentAt = m.getSentAt().toString();
    }
}
