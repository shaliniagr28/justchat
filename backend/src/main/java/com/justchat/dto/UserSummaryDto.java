package com.justchat.dto;

import com.justchat.model.User;

public class UserSummaryDto {
    public Long id;
    public String username;

    public UserSummaryDto(User user) {
        this.id = user.getId();
        this.username = user.getUsername();
    }
}
