package com.justchat.controller;

import com.justchat.dto.GroupDetailDto;
import com.justchat.dto.GroupRequestDto;
import com.justchat.dto.GroupResponseDto;
import com.justchat.dto.MessageDto;
import com.justchat.dto.WsEnvelope;
import com.justchat.model.Group;
import com.justchat.model.User;
import com.justchat.repository.UserRepository;
import com.justchat.service.GroupService;
import com.justchat.websocket.UserPusher;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

@RestController
@RequestMapping("/api")
public class GroupController {

    private final GroupService groupService;
    private final UserRepository userRepository;
    private final UserPusher pusher;

    public GroupController(GroupService groupService, UserRepository userRepository, UserPusher pusher) {
        this.groupService = groupService;
        this.userRepository = userRepository;
        this.pusher = pusher;
    }

    public record AddMemberRequest(String username) {}

    @PostMapping("/groups")
    public ResponseEntity<?> createGroup(@RequestBody GroupRequestDto req) {
        if (req.groupName == null || req.groupName.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "groupName is required"));
        }
        Group group = groupService.createGroup(req.groupName.trim(), currentUser().getId());
        return ResponseEntity.ok(new GroupDetailDto(group, groupService.members(group.getId())));
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

    @GetMapping("/groups/{id}")
    public GroupDetailDto detail(@PathVariable Long id) {
        Group group = groupService.requireMember(id, currentUser().getId());
        return new GroupDetailDto(group, groupService.members(id));
    }

    @GetMapping("/groups/{id}/messages")
    public List<MessageDto> history(@PathVariable Long id) {
        return groupService.history(id, currentUser().getId()).stream()
                .map(MessageDto::new)
                .toList();
    }

    @PostMapping("/groups/{id}/members")
    public ResponseEntity<?> addMember(@PathVariable Long id, @RequestBody AddMemberRequest req) {
        if (req.username() == null || req.username().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "username is required"));
        }
        GroupService.AddMemberResult result = groupService.addMember(id, currentUser().getId(), req.username());
        // Everyone in the group (the new member included) gets the "X added Y" line live;
        // the new member also gets GROUP_ADDED so their group list refreshes.
        pusher.pushToUsers(groupService.memberIds(id), WsEnvelope.fromMessage(result.systemMessage()));
        pusher.pushToUser(result.added().getId(), WsEnvelope.groupAdded(id));
        return ResponseEntity.ok(new GroupDetailDto(result.group(), groupService.members(id)));
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<Map<String, String>> notFound(NoSuchElementException e) {
        return ResponseEntity.status(404).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Map<String, String>> forbidden(AccessDeniedException e) {
        return ResponseEntity.status(403).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> conflict(IllegalStateException e) {
        return ResponseEntity.status(409).body(Map.of("error", e.getMessage()));
    }

    private User currentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return userRepository.findByUsername(auth.getName()).orElseThrow();
    }
}
