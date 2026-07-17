package com.paymentflow.identity.config;

import com.paymentflow.identity.domain.Role;
import com.paymentflow.identity.domain.User;
import com.paymentflow.identity.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumSet;
import java.util.Locale;

/**
 * Seeds a dev admin account so the ADMIN-only endpoints can be exercised locally.
 * Active only under the {@code local} profile — never in production.
 */
@Component
@Profile("local")
public class DevDataInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DevDataInitializer.class);

    private final DevAdminProperties properties;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public DevDataInitializer(DevAdminProperties properties,
                              UserRepository userRepository,
                              PasswordEncoder passwordEncoder) {
        this.properties = properties;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!properties.enabled() || properties.email() == null || properties.password() == null) {
            return;
        }
        String email = properties.email().trim().toLowerCase(Locale.ROOT);
        if (userRepository.existsByEmail(email)) {
            return;
        }
        User admin = User.create(
                email,
                passwordEncoder.encode(properties.password()),
                properties.fullName(),
                EnumSet.of(Role.USER, Role.ADMIN));
        userRepository.save(admin);
        log.info("Seeded dev admin account '{}' (local profile).", email);
    }
}
