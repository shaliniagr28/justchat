package com.justchat.service;

import com.justchat.model.Message;
import com.justchat.model.MessageStatus;
import com.justchat.repository.MessageRepository;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class MessageService {

    private final MessageRepository messageRepository;

    public MessageService(MessageRepository messageRepository) {
        this.messageRepository = messageRepository;
    }

    public Message persistNewMessage(Long senderId, Long recipientId, String content) {
        Message message = new Message(senderId, recipientId, content);
        return messageRepository.save(message);
    }

    public Message markDelivered(Long messageId) {
        Message message = messageRepository.findById(messageId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown message id: " + messageId));
        message.markDelivered();
        return messageRepository.save(message);
    }

    public List<Message> findUndelivered(Long recipientId) {
        return messageRepository.findByRecipientIdAndStatus(recipientId, MessageStatus.SENT);
    }

    public List<Message> findThread(Long userA, Long userB) {
        return messageRepository.findThreadBetween(userA, userB);
    }
}
