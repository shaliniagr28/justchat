package com.justchat.controller;

import com.justchat.dto.GroupRequestDto;
import com.justchat.dto.GroupResponseDto;
import com.justchat.model.Group;
import com.justchat.model.User;
import com.justchat.repository.UserRepository;
import com.justchat.service.GroupService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class GroupController {

    private final GroupService groupService;
    private final UserRepository userRepository;

    public GroupController(GroupService groupService, UserRepository userRepository) {
        this.groupService = groupService;
        this.userRepository = userRepository;
    }


    @PostMapping("/groups")
    public ResponseEntity<?> createGroup(@RequestBody GroupRequestDto req, HttpServletRequest request, HttpServletResponse response) {
        if (req.groupName == null || req.groupName.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "groupName is required"));
        }
        Group group;
        try {
            group = groupService.createGroup(req.groupName.trim(), currentUser().getId());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(409).body(Map.of("error", e.getMessage()));
        }
        return ResponseEntity.ok(new GroupResponseDto(group));
    }

    @GetMapping("/groups")
    public List<GroupResponseDto> listGroups(@RequestParam(required = false) String q) {
        List<Group> groups = (q == null || q.isBlank())
                ? groupService.findByUserId(currentUser().getId())
                : groupService.findByGroupName(q.trim());
        return groups.stream()
                .map(GroupResponseDto::new)
                .toList();
    }

    private User currentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return userRepository.findByUsername(auth.getName()).orElseThrow();
    }
}
