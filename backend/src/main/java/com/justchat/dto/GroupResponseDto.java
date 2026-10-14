package com.justchat.dto;

import com.justchat.model.Group;

public class GroupResponseDto {
    public Long id;
    public String groupName;

    public GroupResponseDto(Group group) {
        this.id = group.getId();
        this.groupName = group.getGroupName();
    }
}
