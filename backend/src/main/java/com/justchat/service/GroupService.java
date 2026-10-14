package com.justchat.service;

import com.justchat.model.Group;
import com.justchat.model.GroupMembers;
import com.justchat.model.Message;
import com.justchat.model.User;
import com.justchat.repository.GroupMemberRepository;
import com.justchat.repository.GroupRepository;
import com.justchat.repository.MessageRepository;
import com.justchat.repository.UserRepository;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.NoSuchElementException;

/**
 * Group membership rules live here, not in the controller or WS handler, so both entry
 * points enforce the same thing: only members read or post, only the creator adds members.
 * Failures are thrown as NoSuchElementException (404), AccessDeniedException (403) and
 * IllegalStateException (409); GroupController maps them to responses.
 */
@Service
public class GroupService {

    public record AddMemberResult(Group group, User added, Message systemMessage) {}

    private final GroupRepository groupRepository;
    private final GroupMemberRepository groupMemberRepository;
    private final UserRepository userRepository;
    private final MessageRepository messageRepository;

    public GroupService(GroupRepository groupRepository, GroupMemberRepository groupMemberRepository,
            UserRepository userRepository, MessageRepository messageRepository) {
        this.groupRepository = groupRepository;
        this.groupMemberRepository = groupMemberRepository;
        this.userRepository = userRepository;
        this.messageRepository = messageRepository;
    }

    @Transactional
    public Group createGroup(String groupName, Long creatorId) {
        Group group = groupRepository.save(new Group(groupName, creatorId));
        groupMemberRepository.save(new GroupMembers(group.getId(), creatorId));
        return group;
    }

    public Group requireMember(Long groupId, Long userId) {
        Group group = findGroup(groupId);
        if (!isMember(groupId, userId)) {
            throw new AccessDeniedException("Not a member of this group");
        }
        return group;
    }

    public boolean isMember(Long groupId, Long userId) {
        return groupMemberRepository.existsByGroupIdAndUserId(groupId, userId);
    }

    public List<Long> memberIds(Long groupId) {
        return groupMemberRepository.findByGroupId(groupId).stream()
                .map(GroupMembers::getUserId)
                .toList();
    }

    public List<User> members(Long groupId) {
        return userRepository.findAllById(memberIds(groupId));
    }

    public List<Message> history(Long groupId, Long userId) {
        requireMember(groupId, userId);
        return messageRepository.findByGroupIdOrderByIdAsc(groupId);
    }

    @Transactional
    public AddMemberResult addMember(Long groupId, Long actorId, String username) {
        Group group = findGroup(groupId);
        if (group.getCreatedBy() == null || !group.getCreatedBy().equals(actorId)) {
            throw new AccessDeniedException("Only the group creator can add members");
        }
        String trimmed = username.trim();
        User added = userRepository.findByUsername(trimmed)
                .orElseThrow(() -> new NoSuchElementException("No user named " + trimmed));
        if (isMember(groupId, added.getId())) {
            throw new IllegalStateException(added.getUsername() + " is already a member");
        }
        groupMemberRepository.save(new GroupMembers(groupId, added.getId()));
        User actor = userRepository.findById(actorId).orElseThrow();
        Message systemMessage = messageRepository.save(
                Message.groupSystem(actorId, groupId, actor.getUsername() + " added " + added.getUsername()));
        return new AddMemberResult(group, added, systemMessage);
    }

    public List<Group> findByUserId(Long userId) {
        List<Long> groupIds = groupMemberRepository.findByUserId(userId).stream()
                .map(GroupMembers::getGroupId)
                .toList();
        return groupRepository.findAllById(groupIds);
    }

    public List<Group> findByGroupName(String query) {
        return groupRepository.findByGroupNameContainingIgnoreCase(query);
    }

    private Group findGroup(Long groupId) {
        return groupRepository.findById(groupId)
                .orElseThrow(() -> new NoSuchElementException("Group not found"));
    }
}
