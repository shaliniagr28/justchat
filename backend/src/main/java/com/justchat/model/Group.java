package com.justchat.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "groups")
public class Group {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String groupName;

    // Only the creator may add members. Nullable so groups created before this column
    // existed still load; such a group has no one who can add members.
    private Long createdBy;

    Group(){

    }
    public Group(String groupName, Long createdBy) {
        this.groupName = groupName;
        this.createdBy = createdBy;
    }

    public Long getId() {
        return id;
    }

    public String getGroupName() {
        return groupName;
    }

    public Long getCreatedBy() {
        return createdBy;
    }
}
