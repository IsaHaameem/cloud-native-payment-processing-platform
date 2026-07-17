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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumSet;
import java.util.Locale;

/** Registration and authentication flows: register, login, token refresh, and logout. */
@Service
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final RefreshTokenService refreshTokenService;
    private final UserMapper userMapper;

    public AuthService(UserRepository userRepository,
                       PasswordEncoder passwordEncoder,
                       JwtService jwtService,
                       RefreshTokenService refreshTokenService,
                       UserMapper userMapper) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.refreshTokenService = refreshTokenService;
        this.userMapper = userMapper;
    }

    @Transactional
    public UserResponse register(RegisterRequest request) {
        String email = normalizeEmail(request.email());
        if (userRepository.existsByEmail(email)) {
            throw new EmailAlreadyExistsException(email);
        }
        User user = User.create(
                email,
                passwordEncoder.encode(request.password()),
                request.fullName(),
                EnumSet.of(Role.USER));
        return userMapper.toResponse(userRepository.save(user));
    }

    @Transactional
    public AuthResponse login(LoginRequest request) {
        String email = normalizeEmail(request.email());
        User user = userRepository.findByEmail(email)
                .orElseThrow(InvalidCredentialsException::new);

        if (!user.isEnabled() || !passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new InvalidCredentialsException();
        }

        return issueTokens(user);
    }

    @Transactional
    public AuthResponse refresh(String refreshToken) {
        RefreshTokenService.RotationResult rotation = refreshTokenService.rotate(refreshToken);
        JwtService.IssuedAccessToken accessToken = jwtService.issueAccessToken(rotation.user());
        return AuthResponse.bearer(accessToken.value(), rotation.refreshToken(), accessToken.expiresInSeconds());
    }

    @Transactional
    public void logout(String refreshToken) {
        refreshTokenService.revoke(refreshToken);
    }

    private AuthResponse issueTokens(User user) {
        JwtService.IssuedAccessToken accessToken = jwtService.issueAccessToken(user);
        String refreshToken = refreshTokenService.issue(user);
        return AuthResponse.bearer(accessToken.value(), refreshToken, accessToken.expiresInSeconds());
    }

    private static String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
