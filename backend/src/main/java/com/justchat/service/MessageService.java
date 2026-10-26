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

    public Message persistNewGroupMessage(Long senderId, Long groupId, String content) {
        return messageRepository.save(Message.toGroup(senderId, groupId, content));
    }

    public Message markDelivered(Long messageId) {
        Message message = messageRepository.findById(messageId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown message id: " + messageId));
        message.markDelivered();
        return messageRepository.save(message);
    }

    // Marks everything readerId has received from senderId, up to and including upToId, as
    // READ. The upper bound is the newest message the reader's client actually rendered,
    // so a message that arrives while this READ frame is in flight isn't marked read unseen.
    public List<Message> markThreadRead(Long readerId, Long senderId, Long upToId) {
        List<Message> unread = messageRepository.findByRecipientIdAndSenderIdAndStatusInAndIdLessThanEqual(
                readerId, senderId, List.of(MessageStatus.SENT, MessageStatus.DELIVERED), upToId);
        if (unread.isEmpty()) return unread;
        unread.forEach(Message::markRead);
        return messageRepository.saveAll(unread);
    }

    public List<Message> findUndelivered(Long recipientId) {
        return messageRepository.findByRecipientIdAndStatus(recipientId, MessageStatus.SENT);
    }

    public List<Message> findThread(Long userA, Long userB) {
        return messageRepository.findThreadBetween(userA, userB);
    }
}
