package com.paymentflow.identity.security;

import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/** Parses PEM-encoded RSA keys (X.509 public / PKCS#8 private). */
public final class PemUtils {

    private PemUtils() {
    }

    public static RSAPublicKey parsePublicKey(String pem) {
        try {
            X509EncodedKeySpec spec = new X509EncodedKeySpec(decode(pem));
            return (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(spec);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse RSA public key", e);
        }
    }

    public static RSAPrivateKey parsePrivateKey(String pem) {
        try {
            PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(decode(pem));
            return (RSAPrivateKey) KeyFactory.getInstance("RSA").generatePrivate(spec);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse RSA private key", e);
        }
    }

    private static byte[] decode(String pem) {
        String base64 = pem
                .replaceAll("-----BEGIN [^-]*-----", "")
                .replaceAll("-----END [^-]*-----", "")
                .replaceAll("\\s", "");
        return Base64.getDecoder().decode(base64);
    }
}
