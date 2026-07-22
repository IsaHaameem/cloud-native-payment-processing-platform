package com.paymentflow.identity.mapper;

import com.paymentflow.identity.domain.Role;
import com.paymentflow.identity.domain.User;
import com.paymentflow.identity.dto.UserResponse;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.stream.Collectors;

/** Maps {@link User} entities to their outbound {@link UserResponse} representation. */
@Component
public class UserMapper {

    public UserResponse toResponse(User user) {
        Set<String> roleNames = user.getRoles().stream()
                .map(Role::name)
                .collect(Collectors.toUnmodifiableSet());
        return new UserResponse(
                user.getId(),
                user.getEmail(),
                user.getFullName(),
                roleNames,
                user.isEnabled(),
                user.isEmailVerified(),
                user.getCreatedAt());
    }
}
