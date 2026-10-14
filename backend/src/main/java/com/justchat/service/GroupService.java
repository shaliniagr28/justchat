package com.justchat.service;

import com.justchat.model.Group;
import com.justchat.model.GroupMembers;
import com.justchat.repository.GroupMemberRepository;
import com.justchat.repository.GroupRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class GroupService {

    private final GroupRepository groupRepository;
    private final GroupMemberRepository groupMemberRepository;

    public GroupService(GroupRepository groupRepository, GroupMemberRepository groupMemberRepository) {
        this.groupRepository = groupRepository;
        this.groupMemberRepository = groupMemberRepository;
    }

    @Transactional
    public Group createGroup(String groupName, Long creatorId) {
        Group group = groupRepository.save(new Group(groupName));
        groupMemberRepository.save(new GroupMembers(group.getId(), creatorId));
        return group;
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
}
