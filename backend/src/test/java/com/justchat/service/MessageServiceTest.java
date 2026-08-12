package com.justchat.service;

import com.justchat.model.Message;
import com.justchat.model.MessageStatus;
import com.justchat.repository.MessageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MessageServiceTest {

    @Mock
    private MessageRepository messageRepository;

    private MessageService messageService;

    @BeforeEach
    void setUp() {
        messageService = new MessageService(messageRepository);
    }

    @Test
    void persistNewMessageSavesAMessageWithSentStatus() {
        when(messageRepository.save(any(Message.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Message saved = messageService.persistNewMessage(1L, 2L, "hello");

        assertThat(saved.getSenderId()).isEqualTo(1L);
        assertThat(saved.getRecipientId()).isEqualTo(2L);
        assertThat(saved.getContent()).isEqualTo("hello");
        assertThat(saved.getStatus()).isEqualTo(MessageStatus.SENT);
        verify(messageRepository).save(any(Message.class));
    }

    @Test
    void markDeliveredFlipsStatusAndPersistsTheUpdatedRow() {
        Message message = new Message(1L, 2L, "hi");
        setId(message, 42L);
        when(messageRepository.findById(42L)).thenReturn(Optional.of(message));
        when(messageRepository.save(message)).thenReturn(message);

        Message result = messageService.markDelivered(42L);

        assertThat(result.getStatus()).isEqualTo(MessageStatus.DELIVERED);
        verify(messageRepository).save(message);
    }

    @Test
    void markDeliveredThrowsOnUnknownMessageId() {
        when(messageRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> messageService.markDelivered(999L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("999");
    }

    @Test
    void findUndeliveredDelegatesToRepositoryWithSentStatus() {
        Message backlogItem = new Message(2L, 1L, "queued");
        when(messageRepository.findByRecipientIdAndStatus(1L, MessageStatus.SENT))
                .thenReturn(List.of(backlogItem));

        List<Message> result = messageService.findUndelivered(1L);

        assertThat(result).containsExactly(backlogItem);
        ArgumentCaptor<MessageStatus> statusCaptor = ArgumentCaptor.forClass(MessageStatus.class);
        verify(messageRepository).findByRecipientIdAndStatus(eq(1L), statusCaptor.capture());
        assertThat(statusCaptor.getValue()).isEqualTo(MessageStatus.SENT);
    }

    @Test
    void findThreadDelegatesToRepositoryForBothUserIds() {
        Message threadMessage = new Message(1L, 2L, "hey");
        when(messageRepository.findThreadBetween(1L, 2L)).thenReturn(List.of(threadMessage));

        List<Message> result = messageService.findThread(1L, 2L);

        assertThat(result).containsExactly(threadMessage);
        verify(messageRepository).findThreadBetween(1L, 2L);
    }

    private void setId(Message message, Long id) {
        try {
            var field = Message.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(message, id);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }
}
