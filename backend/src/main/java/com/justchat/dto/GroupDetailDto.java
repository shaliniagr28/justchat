package com.justchat.dto;

import com.justchat.model.Group;
import com.justchat.model.User;

import java.util.List;

public class GroupDetailDto {
    public Long id;
    public String groupName;
    public Long createdBy;
    public List<UserSummaryDto> members;

    public GroupDetailDto(Group group, List<User> members) {
        this.id = group.getId();
        this.groupName = group.getGroupName();
        this.createdBy = group.getCreatedBy();
        this.members = members.stream().map(UserSummaryDto::new).toList();
    }
}
