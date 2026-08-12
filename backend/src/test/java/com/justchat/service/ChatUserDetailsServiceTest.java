package com.justchat.service;

import com.justchat.model.User;
import com.justchat.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChatUserDetailsServiceTest {

    @Mock
    private UserRepository userRepository;

    private ChatUserDetailsService userDetailsService;

    @BeforeEach
    void setUp() {
        userDetailsService = new ChatUserDetailsService(userRepository);
    }

    @Test
    void loadUserByUsernameMapsTheStoredHashAndGrantsTheUserAuthority() {
        User user = new User("alice", "hashed-password");
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(user));

        UserDetails details = userDetailsService.loadUserByUsername("alice");

        assertThat(details.getUsername()).isEqualTo("alice");
        assertThat(details.getPassword()).isEqualTo("hashed-password");
        assertThat(details.getAuthorities())
                .extracting(Object::toString)
                .containsExactly("USER");
    }

    @Test
    void loadUserByUsernameThrowsWhenNoSuchUserExists() {
        when(userRepository.findByUsername("ghost")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userDetailsService.loadUserByUsername("ghost"))
                .isInstanceOf(UsernameNotFoundException.class)
                .hasMessageContaining("ghost");
    }
}
