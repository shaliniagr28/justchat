package com.justchat.service;

import com.justchat.model.Group;
import com.justchat.model.GroupMembers;
import com.justchat.model.Message;
import com.justchat.model.User;
import com.justchat.repository.GroupMemberRepository;
import com.justchat.repository.GroupRepository;
import com.justchat.repository.MessageRepository;
import com.justchat.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GroupServiceTest {

    @Mock private GroupRepository groupRepository;
    @Mock private GroupMemberRepository groupMemberRepository;
    @Mock private UserRepository userRepository;
    @Mock private MessageRepository messageRepository;

    private GroupService groupService;

    @BeforeEach
    void setUp() {
        groupService = new GroupService(groupRepository, groupMemberRepository, userRepository, messageRepository);
    }

    @Test
    void createGroupRecordsTheCreatorAndAddsThemAsAMember() {
        when(groupRepository.save(any(Group.class))).thenAnswer(inv -> withId(inv.getArgument(0), 7L));

        Group group = groupService.createGroup("team", 1L);

        assertThat(group.getCreatedBy()).isEqualTo(1L);
        ArgumentCaptor<GroupMembers> member = ArgumentCaptor.forClass(GroupMembers.class);
        verify(groupMemberRepository).save(member.capture());
        assertThat(member.getValue().getGroupId()).isEqualTo(7L);
        assertThat(member.getValue().getUserId()).isEqualTo(1L);
    }

    @Test
    void addMemberByCreatorAddsTheUserAndStoresASystemMessage() {
        groupCreatedBy(7L, 1L);
        when(userRepository.findById(1L)).thenReturn(Optional.of(user(1L, "alice")));
        when(userRepository.findByUsername("bob")).thenReturn(Optional.of(user(2L, "bob")));
        when(groupMemberRepository.existsByGroupIdAndUserId(7L, 2L)).thenReturn(false);
        when(messageRepository.save(any(Message.class))).thenAnswer(inv -> inv.getArgument(0));

        GroupService.AddMemberResult result = groupService.addMember(7L, 1L, " bob ");

        ArgumentCaptor<GroupMembers> member = ArgumentCaptor.forClass(GroupMembers.class);
        verify(groupMemberRepository).save(member.capture());
        assertThat(member.getValue().getGroupId()).isEqualTo(7L);
        assertThat(member.getValue().getUserId()).isEqualTo(2L);
        assertThat(result.added().getId()).isEqualTo(2L);
        Message system = result.systemMessage();
        assertThat(system.getContent()).isEqualTo("alice added bob");
        assertThat(system.isSystemMessage()).isTrue();
        assertThat(system.getGroupId()).isEqualTo(7L);
        assertThat(system.getRecipientId()).isNull();
    }

    @Test
    void addMemberByNonCreatorIsForbidden() {
        groupCreatedBy(7L, 1L);

        assertThatThrownBy(() -> groupService.addMember(7L, 2L, "carol"))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessage("Only the group creator can add members");
        verify(groupMemberRepository, never()).save(any());
        verify(messageRepository, never()).save(any());
    }

    @Test
    void addMemberToAGroupWithNoRecordedCreatorIsForbidden() {
        groupCreatedBy(7L, null);

        assertThatThrownBy(() -> groupService.addMember(7L, 1L, "bob"))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void addMemberToAnUnknownGroupIsNotFound() {
        when(groupRepository.findById(9L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> groupService.addMember(9L, 1L, "bob"))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessage("Group not found");
    }

    @Test
    void addMemberWithUnknownUsernameIsNotFound() {
        groupCreatedBy(7L, 1L);
        when(userRepository.findByUsername("zed")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> groupService.addMember(7L, 1L, "zed"))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessage("No user named zed");
    }

    @Test
    void addMemberWhoIsAlreadyInTheGroupConflicts() {
        groupCreatedBy(7L, 1L);
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(user(1L, "alice")));
        when(groupMemberRepository.existsByGroupIdAndUserId(7L, 1L)).thenReturn(true);

        assertThatThrownBy(() -> groupService.addMember(7L, 1L, "alice"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("alice is already a member");
        verify(groupMemberRepository, never()).save(any());
        verify(messageRepository, never()).save(any());
    }

    @Test
    void historyForANonMemberIsForbidden() {
        groupCreatedBy(7L, 1L);
        when(groupMemberRepository.existsByGroupIdAndUserId(7L, 3L)).thenReturn(false);

        assertThatThrownBy(() -> groupService.history(7L, 3L))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessage("Not a member of this group");
        verify(messageRepository, never()).findByGroupIdOrderByIdAsc(any());
    }

    @Test
    void historyForAMemberReturnsMessagesInIdOrder() {
        groupCreatedBy(7L, 1L);
        when(groupMemberRepository.existsByGroupIdAndUserId(7L, 2L)).thenReturn(true);
        List<Message> messages = List.of(Message.toGroup(1L, 7L, "a"), Message.toGroup(2L, 7L, "b"));
        when(messageRepository.findByGroupIdOrderByIdAsc(7L)).thenReturn(messages);

        assertThat(groupService.history(7L, 2L)).isEqualTo(messages);
    }

    @Test
    void memberIdsListsEveryMembersUserId() {
        when(groupMemberRepository.findByGroupId(7L)).thenReturn(List.of(new GroupMembers(7L, 1L), new GroupMembers(7L, 2L)));

        assertThat(groupService.memberIds(7L)).containsExactly(1L, 2L);
    }

    private void groupCreatedBy(Long groupId, Long creatorId) {
        Group group = withId(new Group("team", creatorId), groupId);
        lenient().when(groupRepository.findById(groupId)).thenReturn(Optional.of(group));
    }

    private static User user(Long id, String username) {
        User user = new User(username, "hashed");
        setField(user, "id", id);
        return user;
    }

    private static Group withId(Group group, Long id) {
        setField(group, "id", id);
        return group;
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
