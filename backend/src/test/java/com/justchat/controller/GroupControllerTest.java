package com.justchat.controller;

import com.justchat.dto.WsEnvelope;
import com.justchat.model.Group;
import com.justchat.model.Message;
import com.justchat.model.User;
import com.justchat.repository.UserRepository;
import com.justchat.service.GroupService;
import com.justchat.websocket.UserPusher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Collection;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class GroupControllerTest {

    private final GroupService groupService = mock(GroupService.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final UserPusher pusher = mock(UserPusher.class);

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new GroupController(groupService, userRepository, pusher)).build();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("alice", null, List.of()));
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(user(1L, "alice")));
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void addMemberReturnsTheGroupAndPushesSystemMessageAndGroupAdded() throws Exception {
        Group group = group(7L, "team", 1L);
        User bob = user(2L, "bob");
        Message system = withId(Message.groupSystem(1L, 7L, "alice added bob"), 50L);
        when(groupService.addMember(7L, 1L, "bob")).thenReturn(new GroupService.AddMemberResult(group, bob, system));
        when(groupService.memberIds(7L)).thenReturn(List.of(1L, 2L));
        when(groupService.members(7L)).thenReturn(List.of(user(1L, "alice"), bob));

        mockMvc.perform(post("/api/groups/7/members").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"bob\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(7))
                .andExpect(jsonPath("$.createdBy").value(1))
                .andExpect(jsonPath("$.members.length()").value(2))
                .andExpect(jsonPath("$.members[1].username").value("bob"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<Long>> ids = ArgumentCaptor.forClass(Collection.class);
        ArgumentCaptor<WsEnvelope> toMembers = ArgumentCaptor.forClass(WsEnvelope.class);
        ArgumentCaptor<WsEnvelope> toAdded = ArgumentCaptor.forClass(WsEnvelope.class);
        InOrder order = inOrder(pusher);
        order.verify(pusher).pushToUsers(ids.capture(), toMembers.capture());
        order.verify(pusher).pushToUser(eq(2L), toAdded.capture());
        assertThat(ids.getValue()).containsExactly(1L, 2L);
        assertThat(toMembers.getValue().type).isEqualTo("MESSAGE");
        assertThat(toMembers.getValue().system).isTrue();
        assertThat(toMembers.getValue().groupId).isEqualTo(7L);
        assertThat(toMembers.getValue().id).isEqualTo(50L);
        assertThat(toAdded.getValue().type).isEqualTo("GROUP_ADDED");
        assertThat(toAdded.getValue().groupId).isEqualTo(7L);
    }

    @Test
    void addMemberByNonCreatorIs403() throws Exception {
        when(groupService.addMember(7L, 1L, "carol"))
                .thenThrow(new AccessDeniedException("Only the group creator can add members"));

        mockMvc.perform(post("/api/groups/7/members").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"carol\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("Only the group creator can add members"));
        verifyNoInteractions(pusher);
    }

    @Test
    void addMemberUnknownUserIs404WithError() throws Exception {
        when(groupService.addMember(7L, 1L, "zed")).thenThrow(new NoSuchElementException("No user named zed"));

        mockMvc.perform(post("/api/groups/7/members").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"zed\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("No user named zed"));
    }

    @Test
    void addMemberDuplicateIs409() throws Exception {
        when(groupService.addMember(7L, 1L, "bob")).thenThrow(new IllegalStateException("bob is already a member"));

        mockMvc.perform(post("/api/groups/7/members").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"bob\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("bob is already a member"));
    }

    @Test
    void blankUsernameIs400() throws Exception {
        mockMvc.perform(post("/api/groups/7/members").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"  \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("username is required"));
        verify(groupService, never()).addMember(any(), any(), any());
    }

    @Test
    void detailForNonMemberIs403() throws Exception {
        when(groupService.requireMember(7L, 1L)).thenThrow(new AccessDeniedException("Not a member of this group"));

        mockMvc.perform(get("/api/groups/7"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("Not a member of this group"));
    }

    @Test
    void detailForMemberListsMembers() throws Exception {
        when(groupService.requireMember(7L, 1L)).thenReturn(group(7L, "team", 1L));
        when(groupService.members(7L)).thenReturn(List.of(user(1L, "alice"), user(2L, "bob")));

        mockMvc.perform(get("/api/groups/7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.groupName").value("team"))
                .andExpect(jsonPath("$.members[0].username").value("alice"));
    }

    @Test
    void historyReturnsMessagesWithGroupIdAndSystemFlag() throws Exception {
        when(groupService.history(7L, 1L)).thenReturn(List.of(
                withId(Message.groupSystem(1L, 7L, "alice added bob"), 50L),
                withId(Message.toGroup(2L, 7L, "hi all"), 51L)));

        mockMvc.perform(get("/api/groups/7/messages"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].system").value(true))
                .andExpect(jsonPath("$[0].groupId").value(7))
                .andExpect(jsonPath("$[1].system").value(false))
                .andExpect(jsonPath("$[1].content").value("hi all"));
    }

    @Test
    void createGroupReturnsDetailWithTheCreatorAsOnlyMember() throws Exception {
        when(groupService.createGroup("team", 1L)).thenReturn(group(7L, "team", 1L));
        when(groupService.members(7L)).thenReturn(List.of(user(1L, "alice")));

        mockMvc.perform(post("/api/groups").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"groupName\":\" team \"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(7))
                .andExpect(jsonPath("$.createdBy").value(1))
                .andExpect(jsonPath("$.members.length()").value(1));
    }

    private static User user(Long id, String username) {
        User user = new User(username, "hashed");
        setField(user, "id", id);
        return user;
    }

    private static Group group(Long id, String name, Long createdBy) {
        Group group = new Group(name, createdBy);
        setField(group, "id", id);
        return group;
    }

    private static Message withId(Message message, Long id) {
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
