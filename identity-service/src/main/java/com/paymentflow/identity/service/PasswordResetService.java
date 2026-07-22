package com.paymentflow.identity.service;

import com.paymentflow.common.security.OpaqueTokenGenerator;
import com.paymentflow.identity.config.IdentityTokenProperties;
import com.paymentflow.identity.domain.PasswordReset;
import com.paymentflow.identity.domain.User;
import com.paymentflow.identity.event.IdentityEventPublisher;
import com.paymentflow.identity.exception.InvalidTokenException;
import com.paymentflow.identity.repository.PasswordResetRepository;
import com.paymentflow.identity.repository.RefreshTokenRepository;
import com.paymentflow.identity.repository.UserRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Locale;

/**
 * Requests and confirms password resets (M15, task 11). {@link #requestReset} always
 * completes normally regardless of whether the email exists — confirming existence
 * either way would be a user-enumeration leak, the same non-leaking stance the
 * platform already takes for cross-tenant lookups (D28). Confirming a reset revokes
 * every existing refresh token for the account, forcing re-login everywhere — the
 * password just changed, so every previously-issued session should not outlive that.
 */
@Service
public class PasswordResetService {

    private final PasswordResetRepository passwordResetRepository;
    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final IdentityEventPublisher eventPublisher;
    private final IdentityTokenProperties tokenProperties;
    private final MeterRegistry meterRegistry;

    public PasswordResetService(PasswordResetRepository passwordResetRepository, UserRepository userRepository,
                                RefreshTokenRepository refreshTokenRepository, PasswordEncoder passwordEncoder,
                                IdentityEventPublisher eventPublisher, IdentityTokenProperties tokenProperties,
                                MeterRegistry meterRegistry) {
        this.passwordResetRepository = passwordResetRepository;
        this.userRepository = userRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.passwordEncoder = passwordEncoder;
        this.eventPublisher = eventPublisher;
        this.tokenProperties = tokenProperties;
        this.meterRegistry = meterRegistry;
    }

    @Transactional
    public void requestReset(String email) {
        userRepository.findByEmail(normalizeEmail(email)).ifPresent(user -> {
            String rawToken = OpaqueTokenGenerator.generate();
            Instant expiresAt = Instant.now().plus(tokenProperties.passwordResetTtl());
            passwordResetRepository.save(
                    PasswordReset.issue(user.getId(), OpaqueTokenGenerator.sha256Hex(rawToken), expiresAt));
            eventPublisher.publishPasswordResetRequested(user, rawToken, expiresAt);
            meterRegistry.counter("password_reset_requested_total").increment();
        });
    }

    @Transactional
    public void confirmReset(String rawToken, String newPassword) {
        PasswordReset reset = passwordResetRepository.findByTokenHash(OpaqueTokenGenerator.sha256Hex(rawToken))
                .filter(r -> r.isActive(Instant.now()))
                .orElseThrow(InvalidTokenException::new);

        User user = userRepository.findById(reset.getUserId()).orElseThrow(InvalidTokenException::new);
        user.changePasswordHash(passwordEncoder.encode(newPassword));
        reset.consume();
        refreshTokenRepository.revokeAllForUser(user.getId());
        meterRegistry.counter("password_reset_confirmed_total").increment();
    }

    private static String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
