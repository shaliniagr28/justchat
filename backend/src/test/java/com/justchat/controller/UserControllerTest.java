package com.justchat.controller;

import com.justchat.model.Message;
import com.justchat.model.User;
import com.justchat.repository.UserRepository;
import com.justchat.service.MessageService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Optional;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class UserControllerTest {

    private final UserRepository userRepository = mock(UserRepository.class);
    private final MessageService messageService = mock(MessageService.class);

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        UserController controller = new UserController(userRepository, messageService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("alice", null, List.of()));
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(userWithId(1L, "alice")));
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void listUsersReturnsEveryoneExceptTheCurrentUserWhenThereIsNoQuery() throws Exception {
        when(userRepository.findAll()).thenReturn(List.of(
                userWithId(1L, "alice"), userWithId(2L, "bob"), userWithId(3L, "carol")));

        mockMvc.perform(get("/api/users"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].username").value("bob"))
                .andExpect(jsonPath("$[1].username").value("carol"));
    }

    @Test
    void listUsersSearchesByUsernameAndStillExcludesTheCurrentUser() throws Exception {
        when(userRepository.findByUsernameContainingIgnoreCase("bo")).thenReturn(List.of(userWithId(2L, "bob")));

        mockMvc.perform(get("/api/users").param("q", "bo"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].username").value("bob"));
    }

    @Test
    void threadReturnsTheConversationBetweenTheCurrentUserAndTheRequestedOther() throws Exception {
        Message m1 = messageWithId(1L, 1L, 2L, "hi");
        Message m2 = messageWithId(2L, 2L, 1L, "hey back");
        when(messageService.findThread(1L, 2L)).thenReturn(List.of(m1, m2));

        mockMvc.perform(get("/api/messages/thread/2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].content").value("hi"))
                .andExpect(jsonPath("$[1].content").value("hey back"));
    }

    private static User userWithId(Long id, String username) {
        User user = new User(username, "hashed");
        setField(user, "id", id);
        return user;
    }

    private static Message messageWithId(Long id, Long senderId, Long recipientId, String content) {
        Message message = new Message(senderId, recipientId, content);
        setField(message, "id", id);
        return message;
    }

    private static void setField(Object target, String fieldName, Object value) {
        try {
            var field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }
}
