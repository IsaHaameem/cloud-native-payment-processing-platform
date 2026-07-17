package com.paymentflow.identity.dto;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

public record UserResponse(
        UUID id,
        String email,
        String fullName,
        Set<String> roles,
        boolean enabled,
        Instant createdAt) {
}
