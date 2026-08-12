package com.justchat.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.justchat.model.User;
import com.justchat.repository.UserRepository;
import com.justchat.service.AuthService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AuthControllerTest {

    private final AuthService authService = mock(AuthService.class);
    private final AuthenticationManager authenticationManager = mock(AuthenticationManager.class);
    private final SecurityContextRepository securityContextRepository = mock(SecurityContextRepository.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        AuthController controller = new AuthController(authService, authenticationManager, securityContextRepository, userRepository);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void registerCreatesTheUserAndAuthenticatesTheSession() throws Exception {
        User created = userWithId(1L, "alice");
        when(authService.register("alice", "s3cret")).thenReturn(created);
        when(authenticationManager.authenticate(any())).thenReturn(authenticated("alice"));

        mockMvc.perform(post("/api/auth/register")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("username", "alice", "password", "s3cret"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.username").value("alice"));

        verify(securityContextRepository).saveContext(any(), any(), any());
    }

    @Test
    void registerRejectsBlankCredentialsWithoutCallingTheAuthService() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("username", "", "password", ""))))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(authService);
    }

    @Test
    void registerReturnsConflictWhenTheUsernameIsAlreadyTaken() throws Exception {
        when(authService.register("alice", "s3cret")).thenThrow(new IllegalArgumentException("Username already taken"));

        mockMvc.perform(post("/api/auth/register")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("username", "alice", "password", "s3cret"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("Username already taken"));

        verifyNoInteractions(authenticationManager);
    }

    @Test
    void loginAuthenticatesAndReturnsTheUserSummary() throws Exception {
        when(authenticationManager.authenticate(any())).thenReturn(authenticated("alice"));
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(userWithId(1L, "alice")));

        mockMvc.perform(post("/api/auth/login")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("username", "alice", "password", "s3cret"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("alice"));
    }

    @Test
    void loginWithBadCredentialsReturnsUnauthorized() throws Exception {
        when(authenticationManager.authenticate(any())).thenThrow(new BadCredentialsException("bad creds"));

        mockMvc.perform(post("/api/auth/login")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("username", "alice", "password", "wrong"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Invalid username or password"));

        verifyNoInteractions(userRepository);
    }

    @Test
    void meReturnsTheCurrentUserWhenAuthenticated() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(authenticated("alice"));
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(userWithId(1L, "alice")));

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/auth/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("alice"));
    }

    @Test
    void meReturnsUnauthorizedWhenThereIsNoSession() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/auth/me"))
                .andExpect(status().isUnauthorized());
    }

    private static Authentication authenticated(String username) {
        return new UsernamePasswordAuthenticationToken(username, null, java.util.List.of());
    }

    private static User userWithId(Long id, String username) {
        User user = new User(username, "hashed");
        try {
            var field = User.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(user, id);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
        return user;
    }
}
