package com.justchat.repository;

import java.util.List;

import com.justchat.model.Group;
import org.springframework.data.jpa.repository.JpaRepository;

import com.justchat.model.GroupMembers;

public interface GroupRepository extends JpaRepository<Group, Long> {

     List<Group> findByGroupNameContainingIgnoreCase(String groupName);
}
