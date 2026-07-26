package com.paymentflow.common.query;

import com.paymentflow.common.exception.BadRequestException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Encodes and decodes the opaque, <b>signed</b> pagination cursors every public list
 * endpoint issues (M19, D107).
 *
 * <p><b>Why signed.</b> An unsigned cursor is just a parameter, and a parameter that
 * encodes "start reading from this position, for this merchant, in this mode" is a
 * parameter an attacker will try to edit. Signing turns it from a hint into an
 * assertion the platform can verify, so a forged or edited cursor is rejected with a
 * 400 rather than quietly changing which rows a query considers. This is the same
 * reasoning D100 applies to the internal context, applied to a client-held token.
 *
 * <p>Note the layering: the signature is <em>defence in depth</em>, not the isolation
 * boundary. Every M19 repository method takes {@code merchantId} and {@code mode} from
 * the verified {@code MerchantContext} and ignores whatever a cursor claims (D101), so
 * a forged cursor could not cross a tenant boundary even if it were accepted. What the
 * signature adds is that such an attempt fails loudly and immediately, instead of
 * returning a confusing empty page.
 *
 * <p>The payload is deliberately not encrypted, only authenticated: it contains a
 * timestamp and a row id the client just received in the response body anyway.
 * Encrypting it would imply a confidentiality property that does not exist and cannot
 * be relied on.
 *
 * <p>Reuses the internal-context HMAC secret rather than introducing a fourth key to
 * manage. Both are server-side integrity secrets with the same lifecycle and the same
 * M29-owned Secrets Manager gap; a separate key would double the operational surface
 * for no separation of concern that actually matters here.
 */
public final class CursorCodec {

    private static final String ALGORITHM = "HmacSHA256";
    private static final char FIELD_SEPARATOR = '|';
    /** Truncated to 16 bytes: this authenticates a page position, not a credential, and a shorter cursor is friendlier in a URL. */
    private static final int SIGNATURE_HEX_LENGTH = 32;

    private final String secret;

    public CursorCodec(String secret) {
        this.secret = secret;
    }

    /** The opaque token a client passes back as {@code starting_after}. */
    public String encode(Cursor cursor) {
        String payload = payload(cursor);
        String signed = payload + FIELD_SEPARATOR + sign(payload);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(signed.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Decodes a cursor, verifying both its signature and that it was issued to this
     * caller.
     *
     * @throws BadRequestException if the cursor is malformed, tampered with, or belongs
     *                             to a different merchant or mode — all 400, never 500,
     *                             because every one of them is a client-supplied value
     *                             being wrong rather than the platform failing
     */
    public Cursor decode(String encoded, UUID expectedMerchantId, String expectedMode) {
        String decoded;
        try {
            decoded = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("The pagination cursor is not valid.");
        }

        int lastSeparator = decoded.lastIndexOf(FIELD_SEPARATOR);
        if (lastSeparator < 0) {
            throw new BadRequestException("The pagination cursor is not valid.");
        }
        String payload = decoded.substring(0, lastSeparator);
        String signature = decoded.substring(lastSeparator + 1);

        // Constant-time comparison, and computed before any field is parsed — a tampered
        // cursor must not be able to reach the parsing code at all.
        if (!MessageDigest.isEqual(sign(payload).getBytes(StandardCharsets.UTF_8),
                signature.getBytes(StandardCharsets.UTF_8))) {
            throw new BadRequestException("The pagination cursor is not valid.");
        }

        String[] parts = payload.split("\\" + FIELD_SEPARATOR, -1);
        if (parts.length != 4) {
            throw new BadRequestException("The pagination cursor is not valid.");
        }
        Cursor cursor;
        try {
            cursor = new Cursor(Instant.ofEpochMilli(Long.parseLong(parts[0])), UUID.fromString(parts[1]),
                    UUID.fromString(parts[2]), parts[3]);
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("The pagination cursor is not valid.");
        }

        // A validly-signed cursor from another tenant is still refused. It could not have
        // widened the query (the repository takes merchant/mode from the context), but
        // failing loudly beats returning an empty page nobody can explain.
        if (!cursor.merchantId().equals(expectedMerchantId) || !cursor.mode().equals(expectedMode)) {
            throw new BadRequestException("The pagination cursor is not valid for this request.");
        }
        return cursor;
    }

    private static String payload(Cursor cursor) {
        return cursor.createdAt().toEpochMilli() + String.valueOf(FIELD_SEPARATOR)
                + cursor.id() + FIELD_SEPARATOR
                + cursor.merchantId() + FIELD_SEPARATOR
                + cursor.mode();
    }

    private String sign(String payload) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM));
            String full = HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
            return full.substring(0, SIGNATURE_HEX_LENGTH);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HmacSHA256 is unavailable", e);
        }
    }
}
