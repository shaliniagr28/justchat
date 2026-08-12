package com.justchat.service;

import com.justchat.model.User;
import com.justchat.repository.UserRepository;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.core.userdetails.User.UserBuilder;
import org.springframework.stereotype.Service;

@Service
public class ChatUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;

    public ChatUserDetailsService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException("No such user: " + username));
        UserBuilder builder = org.springframework.security.core.userdetails.User.builder();
        return builder
                .username(user.getUsername())
                .password(user.getPasswordHash())
                .authorities("USER")
                .build();
    }
}
