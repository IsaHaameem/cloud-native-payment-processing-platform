package com.paymentflow.identity.config;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import com.paymentflow.identity.security.PemUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.UUID;

/**
 * Builds the RSA signing material and the Spring Security JWT encoder/decoder.
 *
 * <p>Identity signs access tokens with the private key (RS256); the public key is
 * published via JWKS so the gateway and other services can validate tokens without
 * sharing a secret.
 */
@Configuration
public class JwtKeyConfiguration {

    private static final Logger log = LoggerFactory.getLogger(JwtKeyConfiguration.class);

    @Bean
    public RSAKey rsaKey(JwtProperties properties) {
        if (properties.hasConfiguredKeys()) {
            RSAPublicKey publicKey = PemUtils.parsePublicKey(properties.publicKey());
            RSAPrivateKey privateKey = PemUtils.parsePrivateKey(properties.privateKey());
            log.info("Loaded configured RSA JWT signing key.");
            return new RSAKey.Builder(publicKey)
                    .privateKey(privateKey)
                    .keyID(UUID.randomUUID().toString())
                    .build();
        }

        log.warn("No JWT keypair configured — generating an EPHEMERAL RSA keypair. "
                + "Access tokens will not survive a restart. Configure "
                + "paymentflow.security.jwt.private-key/public-key (Secrets Manager) in production.");
        KeyPair keyPair = generateRsaKeyPair();
        return new RSAKey.Builder((RSAPublicKey) keyPair.getPublic())
                .privateKey((RSAPrivateKey) keyPair.getPrivate())
                .keyID(UUID.randomUUID().toString())
                .build();
    }

    @Bean
    public JWKSource<SecurityContext> jwkSource(RSAKey rsaKey) {
        return new ImmutableJWKSet<>(new JWKSet(rsaKey));
    }

    @Bean
    public JwtEncoder jwtEncoder(JWKSource<SecurityContext> jwkSource) {
        return new NimbusJwtEncoder(jwkSource);
    }

    @Bean
    public JwtDecoder jwtDecoder(RSAKey rsaKey, JwtProperties properties) throws Exception {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(rsaKey.toRSAPublicKey())
                .signatureAlgorithm(SignatureAlgorithm.RS256)
                .build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(properties.issuer()));
        return decoder;
    }

    private static KeyPair generateRsaKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException("Unable to generate RSA keypair", e);
        }
    }
}
