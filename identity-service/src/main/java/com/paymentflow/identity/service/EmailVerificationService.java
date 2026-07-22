package com.paymentflow.identity.service;

import com.paymentflow.common.security.OpaqueTokenGenerator;
import com.paymentflow.identity.config.IdentityTokenProperties;
import com.paymentflow.identity.domain.EmailVerification;
import com.paymentflow.identity.domain.User;
import com.paymentflow.identity.event.IdentityEventPublisher;
import com.paymentflow.identity.exception.InvalidTokenException;
import com.paymentflow.identity.repository.EmailVerificationRepository;
import com.paymentflow.identity.repository.UserRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Locale;

/**
 * Issues and consumes email-verification tokens (M15, task 11) — opaque, SHA-256-hashed
 * (D16's established pattern), delivered async via {@code identity.events} so
 * registration's HTTP response never waits on notification-service (D1).
 */
@Service
public class EmailVerificationService {

    private final EmailVerificationRepository emailVerificationRepository;
    private final UserRepository userRepository;
    private final IdentityEventPublisher eventPublisher;
    private final IdentityTokenProperties tokenProperties;
    private final MeterRegistry meterRegistry;

    public EmailVerificationService(EmailVerificationRepository emailVerificationRepository,
                                    UserRepository userRepository, IdentityEventPublisher eventPublisher,
                                    IdentityTokenProperties tokenProperties, MeterRegistry meterRegistry) {
        this.emailVerificationRepository = emailVerificationRepository;
        this.userRepository = userRepository;
        this.eventPublisher = eventPublisher;
        this.tokenProperties = tokenProperties;
        this.meterRegistry = meterRegistry;
    }

    @Transactional
    public void requestVerification(User user) {
        String rawToken = OpaqueTokenGenerator.generate();
        Instant expiresAt = Instant.now().plus(tokenProperties.emailVerificationTtl());
        emailVerificationRepository.save(
                EmailVerification.issue(user.getId(), OpaqueTokenGenerator.sha256Hex(rawToken), expiresAt));
        eventPublisher.publishEmailVerificationRequested(user, rawToken, expiresAt);
        meterRegistry.counter("email_verification_requested_total").increment();
    }

    /** No-op (not an error) for an unknown or already-verified email — no user-enumeration leak. */
    @Transactional
    public void resend(String email) {
        userRepository.findByEmail(normalizeEmail(email))
                .filter(user -> !user.isEmailVerified())
                .ifPresent(this::requestVerification);
    }

    @Transactional
    public void verify(String rawToken) {
        EmailVerification verification = emailVerificationRepository
                .findByTokenHash(OpaqueTokenGenerator.sha256Hex(rawToken))
                .filter(v -> v.isActive(Instant.now()))
                .orElseThrow(InvalidTokenException::new);

        User user = userRepository.findById(verification.getUserId()).orElseThrow(InvalidTokenException::new);
        user.markEmailVerified();
        verification.consume();
        meterRegistry.counter("email_verification_confirmed_total").increment();
    }

    private static String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
