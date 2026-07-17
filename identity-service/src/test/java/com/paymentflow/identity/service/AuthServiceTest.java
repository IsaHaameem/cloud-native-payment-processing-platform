package com.paymentflow.identity.service;

import com.paymentflow.identity.domain.Role;
import com.paymentflow.identity.domain.User;
import com.paymentflow.identity.dto.AuthResponse;
import com.paymentflow.identity.dto.LoginRequest;
import com.paymentflow.identity.dto.RegisterRequest;
import com.paymentflow.identity.dto.UserResponse;
import com.paymentflow.identity.exception.EmailAlreadyExistsException;
import com.paymentflow.identity.exception.InvalidCredentialsException;
import com.paymentflow.identity.mapper.UserMapper;
import com.paymentflow.identity.repository.UserRepository;
import com.paymentflow.identity.security.JwtService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.EnumSet;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private UserRepository userRepository;
    @Mock
    private JwtService jwtService;
    @Mock
    private RefreshTokenService refreshTokenService;
    @Spy
    private UserMapper userMapper = new UserMapper();
    @Spy
    private org.springframework.security.crypto.password.PasswordEncoder passwordEncoder =
            new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder(4);

    @InjectMocks
    private AuthService authService;

    @Test
    void registerRejectsDuplicateEmail() {
        when(userRepository.existsByEmail("dup@example.com")).thenReturn(true);

        assertThatThrownBy(() -> authService.register(
                new RegisterRequest("Dup@Example.com", "password123", "Dup")))
                .isInstanceOf(EmailAlreadyExistsException.class);

        verify(userRepository, never()).save(any());
    }

    @Test
    void registerNormalizesEmailEncodesPasswordAndAssignsUserRole() {
        when(userRepository.existsByEmail("new@example.com")).thenReturn(false);
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        UserResponse response = authService.register(
                new RegisterRequest("  New@Example.com  ", "password123", "New User"));

        assertThat(response.email()).isEqualTo("new@example.com");
        assertThat(response.roles()).containsExactly("USER");

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        User saved = captor.getValue();
        assertThat(saved.getRoles()).containsExactly(Role.USER);
        assertThat(saved.getPasswordHash()).isNotEqualTo("password123");
        assertThat(passwordEncoder.matches("password123", saved.getPasswordHash())).isTrue();
    }

    @Test
    void loginRejectsWrongPassword() {
        User user = User.create("user@example.com",
                new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder(4).encode("correct"),
                "User", EnumSet.of(Role.USER));
        when(userRepository.findByEmail("user@example.com")).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> authService.login(new LoginRequest("user@example.com", "wrong")))
                .isInstanceOf(InvalidCredentialsException.class);
    }

    @Test
    void loginReturnsTokenPairOnSuccess() {
        User user = User.create("user@example.com",
                new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder(4).encode("correct"),
                "User", EnumSet.of(Role.USER));
        when(userRepository.findByEmail("user@example.com")).thenReturn(Optional.of(user));
        when(jwtService.issueAccessToken(user))
                .thenReturn(new JwtService.IssuedAccessToken("access-token", Instant.now(), 900));
        when(refreshTokenService.issue(user)).thenReturn("refresh-token");

        AuthResponse response = authService.login(new LoginRequest("user@example.com", "correct"));

        assertThat(response.accessToken()).isEqualTo("access-token");
        assertThat(response.refreshToken()).isEqualTo("refresh-token");
        assertThat(response.tokenType()).isEqualTo("Bearer");
        assertThat(response.expiresIn()).isEqualTo(900);
    }
}
