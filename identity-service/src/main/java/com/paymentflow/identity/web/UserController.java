package com.paymentflow.identity.web;

import com.paymentflow.common.dto.page.PageResponse;
import com.paymentflow.identity.dto.UserResponse;
import com.paymentflow.identity.service.UserService;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** User account queries. {@code /me} needs any valid token; listing requires ADMIN. */
@RestController
@RequestMapping("/api/v1/users")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping("/me")
    public UserResponse currentUser(@AuthenticationPrincipal Jwt jwt) {
        return userService.getById(UUID.fromString(jwt.getSubject()));
    }

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public PageResponse<UserResponse> listUsers(Pageable pageable) {
        return userService.list(pageable);
    }
}
