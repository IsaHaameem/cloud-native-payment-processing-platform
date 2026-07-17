package com.paymentflow.identity;

import com.paymentflow.identity.domain.Role;
import com.paymentflow.identity.domain.User;
import com.paymentflow.identity.dto.AuthResponse;
import com.paymentflow.identity.dto.LoginRequest;
import com.paymentflow.identity.dto.RefreshTokenRequest;
import com.paymentflow.identity.dto.RegisterRequest;
import com.paymentflow.identity.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.ObjectMapper;

import java.util.EnumSet;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class IdentityIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:17-alpine"));

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;

    @Test
    void registerThenLoginThenAccessProtectedEndpoint() throws Exception {
        String email = "flow@example.com";
        register(email, "password123");

        AuthResponse tokens = login(email, "password123");

        mockMvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer " + tokens.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(email))
                .andExpect(jsonPath("$.roles[0]").value("USER"));
    }

    @Test
    void duplicateRegistrationReturnsConflict() throws Exception {
        String email = "dupe@example.com";
        register(email, "password123");

        RegisterRequest again = new RegisterRequest(email, "password123", "Dupe");
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(again)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));
    }

    @Test
    void accessingProtectedEndpointWithoutTokenReturns401() throws Exception {
        mockMvc.perform(get("/api/v1/users/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void nonAdminListingUsersReturns403() throws Exception {
        String email = "plainuser@example.com";
        register(email, "password123");
        AuthResponse tokens = login(email, "password123");

        mockMvc.perform(get("/api/v1/users").header("Authorization", "Bearer " + tokens.accessToken()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void adminCanListUsers() throws Exception {
        String email = "admin-list@example.com";
        userRepository.save(User.create(email, passwordEncoder.encode("password123"),
                "Admin", EnumSet.of(Role.USER, Role.ADMIN)));

        AuthResponse tokens = login(email, "password123");

        mockMvc.perform(get("/api/v1/users").header("Authorization", "Bearer " + tokens.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.totalElements").isNumber());
    }

    @Test
    void refreshRotatesTokenAndOldRefreshTokenIsRejected() throws Exception {
        String email = "rotate@example.com";
        register(email, "password123");
        AuthResponse first = login(email, "password123");

        // First refresh succeeds and returns a new refresh token.
        String rotatedBody = mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RefreshTokenRequest(first.refreshToken()))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        AuthResponse rotated = objectMapper.readValue(rotatedBody, AuthResponse.class);
        assert !rotated.refreshToken().equals(first.refreshToken());

        // Re-using the original (now revoked) refresh token must fail.
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RefreshTokenRequest(first.refreshToken()))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void logoutRevokesRefreshToken() throws Exception {
        String email = "logout@example.com";
        register(email, "password123");
        AuthResponse tokens = login(email, "password123");

        mockMvc.perform(post("/api/v1/auth/logout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RefreshTokenRequest(tokens.refreshToken()))))
                .andExpect(status().isNoContent());

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RefreshTokenRequest(tokens.refreshToken()))))
                .andExpect(status().isUnauthorized());
    }

    private void register(String email, String password) throws Exception {
        RegisterRequest request = new RegisterRequest(email, password, "Test User");
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated());
    }

    private AuthResponse login(String email, String password) throws Exception {
        LoginRequest request = new LoginRequest(email, password);
        String body = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readValue(body, AuthResponse.class);
    }
}
