package com.paymentflow.identity.web;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Publishes the RSA public key as a JWK Set so the gateway and other services can
 * verify access-token signatures without sharing a secret. Only public key material
 * is exposed (never the private key).
 */
@RestController
public class JwksController {

    private final RSAKey rsaKey;

    public JwksController(RSAKey rsaKey) {
        this.rsaKey = rsaKey;
    }

    @GetMapping("/oauth2/jwks")
    public Map<String, Object> keys() {
        return new JWKSet(rsaKey.toPublicJWK()).toJSONObject();
    }
}
