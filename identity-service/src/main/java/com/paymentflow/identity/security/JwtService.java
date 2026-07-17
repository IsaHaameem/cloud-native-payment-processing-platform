package com.paymentflow.identity.security;

import com.paymentflow.identity.config.JwtProperties;
import com.paymentflow.identity.domain.Role;
import com.paymentflow.identity.domain.User;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Issues short-lived, RS256-signed access tokens carrying the user's id, email and roles. */
@Service
public class JwtService {

    private final JwtEncoder jwtEncoder;
    private final JwtProperties properties;

    public JwtService(JwtEncoder jwtEncoder, JwtProperties properties) {
        this.jwtEncoder = jwtEncoder;
        this.properties = properties;
    }

    public IssuedAccessToken issueAccessToken(User user) {
        Instant now = Instant.now();
        Instant expiresAt = now.plus(properties.accessTokenTtl());

        List<String> roles = user.getRoles().stream().map(Role::name).toList();

        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(properties.issuer())
                .issuedAt(now)
                .expiresAt(expiresAt)
                .subject(user.getId().toString())
                .id(UUID.randomUUID().toString())
                .claim("email", user.getEmail())
                .claim("roles", roles)
                .build();

        JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).build();
        String value = jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();

        return new IssuedAccessToken(value, expiresAt, properties.accessTokenTtl().toSeconds());
    }

    /** An issued access token together with its absolute expiry and lifetime in seconds. */
    public record IssuedAccessToken(String value, Instant expiresAt, long expiresInSeconds) {
    }
}
