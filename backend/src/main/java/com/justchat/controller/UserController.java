package com.justchat.controller;

import com.justchat.dto.MessageDto;
import com.justchat.dto.UserSummaryDto;
import com.justchat.model.User;
import com.justchat.repository.UserRepository;
import com.justchat.service.MessageService;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api")
public class UserController {

    private final UserRepository userRepository;
    private final MessageService messageService;

    public UserController(UserRepository userRepository, MessageService messageService) {
        this.userRepository = userRepository;
        this.messageService = messageService;
    }

    @GetMapping("/users")
    public List<UserSummaryDto> listUsers(@RequestParam(required = false) String q) {
        Long currentUserId = currentUser().getId();
        List<User> users = (q == null || q.isBlank())
                ? userRepository.findAll()
                : userRepository.findByUsernameContainingIgnoreCase(q);
        return users.stream()
                .filter(u -> !u.getId().equals(currentUserId))
                .map(UserSummaryDto::new)
                .toList();
    }

    @GetMapping("/messages/thread/{otherUserId}")
    public List<MessageDto> thread(@PathVariable Long otherUserId) {
        Long currentUserId = currentUser().getId();
        return messageService.findThread(currentUserId, otherUserId).stream()
                .map(MessageDto::new)
                .toList();
    }

    private User currentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return userRepository.findByUsername(auth.getName()).orElseThrow();
    }
}
