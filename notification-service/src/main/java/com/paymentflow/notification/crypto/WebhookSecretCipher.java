package com.paymentflow.notification.crypto;

import com.paymentflow.notification.config.WebhookProperties;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/**
 * Encrypts and decrypts webhook signing secrets at rest (M18.6, D137).
 *
 * <p><b>Why this exists at all.</b> §4.9 states that every secret the platform holds —
 * {@code sk_}, {@code whsec_}, refresh tokens — is "stored only as SHA-256, shown exactly
 * once". That is correct and implementable for the other two, because the platform only
 * ever *verifies* them: hash what the caller presented, compare to the stored digest. It
 * is not implementable for a webhook signing secret, because the platform must **use** it
 * as an HMAC key on every delivery, and a one-way digest cannot produce a signature the
 * merchant — who holds the original — can reproduce. Hashing it would yield a signature
 * no receiver on earth could verify.
 *
 * <p>So the secret is encrypted rather than hashed: recoverable by this service, never
 * readable from a database dump alone. AES-256-GCM, an authenticated cipher, so a
 * tampered ciphertext fails loudly at decryption instead of silently yielding a wrong key
 * and an unverifiable signature. A fresh 12-byte IV per encryption is prepended to the
 * ciphertext, so encrypting the same secret twice never produces the same stored value.
 *
 * <p>The key is derived by SHA-256 over the configured passphrase, giving a well-formed
 * 256-bit key from an arbitrary-length configured string — the same
 * env-var-now/Secrets-Manager-later handling as the internal-context HMAC secret
 * (D18/D73), and carrying the same known issue: the local default is deliberately
 * insecure and M29 owns wiring it to Secrets Manager.
 */
@Component
public class WebhookSecretCipher {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final String ALGORITHM = "AES";
    private static final int IV_LENGTH_BYTES = 12;
    private static final int TAG_LENGTH_BITS = 128;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final SecretKeySpec key;

    public WebhookSecretCipher(WebhookProperties properties) {
        this.key = new SecretKeySpec(sha256(properties.secretEncryptionKey()), ALGORITHM);
    }

    /** Base64 of {@code iv || ciphertext || tag}. */
    public String encrypt(String plaintext) {
        try {
            byte[] iv = new byte[IV_LENGTH_BYTES];
            SECURE_RANDOM.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            byte[] combined = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(ciphertext, 0, combined, iv.length, ciphertext.length);
            return Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            // Never include the plaintext in the message — this exception is logged.
            throw new IllegalStateException("Could not encrypt the webhook signing secret", e);
        }
    }

    public String decrypt(String encoded) {
        try {
            byte[] combined = Base64.getDecoder().decode(encoded);
            byte[] iv = Arrays.copyOfRange(combined, 0, IV_LENGTH_BYTES);
            byte[] ciphertext = Arrays.copyOfRange(combined, IV_LENGTH_BYTES, combined.length);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (Exception e) {
            // GCM authentication failure lands here: the stored value was tampered with,
            // or the configured key changed. Both must fail loudly — signing with a
            // silently wrong key would produce deliveries no merchant could verify, which
            // is far harder to diagnose than a decryption error.
            throw new IllegalStateException("Could not decrypt the webhook signing secret", e);
        }
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
