package com.paymentflow.identity.service;

import com.paymentflow.common.security.OpaqueTokenGenerator;
import com.paymentflow.identity.config.JwtProperties;
import com.paymentflow.identity.domain.RefreshToken;
import com.paymentflow.identity.domain.User;
import com.paymentflow.identity.exception.InvalidTokenException;
import com.paymentflow.identity.repository.RefreshTokenRepository;
import com.paymentflow.identity.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Manages opaque refresh tokens with rotation: a raw high-entropy token is returned to
 * the client while only its SHA-256 hash is persisted. Each use revokes the presented
 * token and issues a fresh one, so a stolen-and-replayed token is detectable and short-lived.
 */
@Service
public class RefreshTokenService {

    private final RefreshTokenRepository refreshTokenRepository;
    private final UserRepository userRepository;
    private final JwtProperties jwtProperties;

    public RefreshTokenService(RefreshTokenRepository refreshTokenRepository,
                               UserRepository userRepository,
                               JwtProperties jwtProperties) {
        this.refreshTokenRepository = refreshTokenRepository;
        this.userRepository = userRepository;
        this.jwtProperties = jwtProperties;
    }

    /** Issues a new refresh token for the user and returns the raw value (shown once). */
    @Transactional
    public String issue(User user) {
        String rawToken = OpaqueTokenGenerator.generate();
        Instant expiresAt = Instant.now().plus(jwtProperties.refreshTokenTtl());
        refreshTokenRepository.save(RefreshToken.issue(user.getId(), OpaqueTokenGenerator.sha256Hex(rawToken), expiresAt));
        return rawToken;
    }

    /** Validates and rotates a presented refresh token, returning the owner and the new raw token. */
    @Transactional
    public RotationResult rotate(String presentedToken) {
        RefreshToken stored = refreshTokenRepository.findByTokenHash(OpaqueTokenGenerator.sha256Hex(presentedToken))
                .orElseThrow(InvalidTokenException::new);

        if (!stored.isActive(Instant.now())) {
            throw new InvalidTokenException();
        }

        User user = userRepository.findById(stored.getUserId())
                .filter(User::isEnabled)
                .orElseThrow(InvalidTokenException::new);

        stored.revoke();
        String newRawToken = issue(user);
        return new RotationResult(user, newRawToken);
    }

    /** Revokes a presented refresh token. Idempotent: unknown tokens are ignored. */
    @Transactional
    public void revoke(String presentedToken) {
        refreshTokenRepository.findByTokenHash(OpaqueTokenGenerator.sha256Hex(presentedToken))
                .ifPresent(RefreshToken::revoke);
    }

    /** Result of a rotation: the token owner and the freshly issued raw refresh token. */
    public record RotationResult(User user, String refreshToken) {
    }
}
