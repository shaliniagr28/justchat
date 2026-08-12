package com.justchat.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MessageTest {

    @Test
    void newMessageStartsAsSentWithNoDeliveredTimestamp() {
        Message message = new Message(1L, 2L, "hello");

        assertThat(message.getStatus()).isEqualTo(MessageStatus.SENT);
        assertThat(message.getSenderId()).isEqualTo(1L);
        assertThat(message.getRecipientId()).isEqualTo(2L);
        assertThat(message.getContent()).isEqualTo("hello");
        assertThat(message.getDeliveredAt()).isNull();
    }

    @Test
    void markDeliveredMovesSentToDeliveredAndStampsDeliveredAt() {
        Message message = new Message(1L, 2L, "hi");

        message.markDelivered();

        assertThat(message.getStatus()).isEqualTo(MessageStatus.DELIVERED);
        assertThat(message.getDeliveredAt()).isNotNull();
    }

    @Test
    void markDeliveredIsANoOpOnceAlreadyDelivered() {
        Message message = new Message(1L, 2L, "hi");
        message.markDelivered();
        var firstDeliveredAt = message.getDeliveredAt();

        message.markDelivered();

        assertThat(message.getStatus()).isEqualTo(MessageStatus.DELIVERED);
        assertThat(message.getDeliveredAt()).isEqualTo(firstDeliveredAt);
    }
}
