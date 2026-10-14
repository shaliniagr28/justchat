package com.justchat.repository;

import com.justchat.model.Group;
import com.justchat.model.GroupMembers;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface GroupMemberRepository extends JpaRepository<GroupMembers, Long> {

     List<GroupMembers> findByGroupId(Long groupId);

     List<GroupMembers> findByUserId(Long userId);
}
