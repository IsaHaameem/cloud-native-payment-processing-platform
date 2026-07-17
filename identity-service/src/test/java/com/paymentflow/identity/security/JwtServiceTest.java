package com.paymentflow.identity.security;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import com.paymentflow.identity.config.JwtProperties;
import com.paymentflow.identity.domain.Role;
import com.paymentflow.identity.domain.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class JwtServiceTest {

    private final JwtProperties properties =
            new JwtProperties("https://issuer.test", Duration.ofMinutes(15), Duration.ofDays(7), null, null);

    private JwtService jwtService;
    private JwtDecoder decoder;

    @BeforeEach
    void setUp() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair keyPair = generator.generateKeyPair();

        RSAKey rsaKey = new RSAKey.Builder((RSAPublicKey) keyPair.getPublic())
                .privateKey((RSAPrivateKey) keyPair.getPrivate())
                .keyID("test-key")
                .build();
        JWKSource<SecurityContext> jwkSource = new ImmutableJWKSet<>(new JWKSet(rsaKey));

        jwtService = new JwtService(new NimbusJwtEncoder(jwkSource), properties);
        decoder = NimbusJwtDecoder.withPublicKey((RSAPublicKey) keyPair.getPublic())
                .signatureAlgorithm(SignatureAlgorithm.RS256)
                .build();
    }

    @Test
    @SuppressWarnings("unchecked")
    void issuesAccessTokenWithExpectedClaims() {
        User user = User.create("alice@example.com", "hash", "Alice", EnumSet.of(Role.USER, Role.ADMIN));
        UUID id = UUID.randomUUID();
        ReflectionTestUtils.setField(user, "id", id);

        JwtService.IssuedAccessToken issued = jwtService.issueAccessToken(user);
        Jwt jwt = decoder.decode(issued.value());

        assertThat(jwt.getSubject()).isEqualTo(id.toString());
        assertThat(jwt.getClaimAsString("email")).isEqualTo("alice@example.com");
        assertThat((List<String>) jwt.getClaim("roles")).containsExactlyInAnyOrder("USER", "ADMIN");
        assertThat(jwt.getIssuer()).hasToString("https://issuer.test");
        assertThat(issued.expiresInSeconds()).isEqualTo(Duration.ofMinutes(15).toSeconds());
        assertThat(jwt.getExpiresAt()).isNotNull();
    }
}
