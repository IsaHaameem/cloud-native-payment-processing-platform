package com.paymentflow.notification.service;

import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * Computes and verifies the {@code PaymentFlow-Signature} header (M18.4, D105/§4.5).
 *
 * <h2>The specification</h2>
 * <pre>
 * PaymentFlow-Signature: t=1785758400,v1=5f2c…9ab
 *
 *   signed_payload = "{t}" + "." + "{raw request body}"
 *   v1             = lowercase hex of HMAC-SHA256(secret, signed_payload)
 *   secret         = the endpoint's whsec_… value, used as UTF-8 bytes verbatim
 *                    (the "whsec_" prefix is part of the key — it is not stripped)
 * </pre>
 *
 * <p>A receiver verifies by recomputing {@code v1} over {@code t} and the exact bytes it
 * received, comparing in constant time, and <b>rejecting a {@code t} outside its own
 * tolerance window</b>. The timestamp being inside the signed payload is the entire
 * point (D105): a signature over the body alone is replayable forever, because nothing
 * in it binds the message to a moment. This is the single detail homegrown webhook
 * implementations most often get wrong, which is why it is spelled out here and in the
 * merchant-facing guide rather than left implicit in the code.
 *
 * <p>Multiple {@code v1} values may appear in one header, comma-separated. That is how
 * the dual-secret rotation window (§4.5) is expressed on the wire: during rotation a
 * delivery is signed with both the current and the superseded secret, so a receiver that
 * has already switched and a receiver that has not both verify successfully against
 * whichever one they hold. A verifier accepts if <em>any</em> candidate matches.
 *
 * <p>Deliberately not reusing {@code common-lib}'s {@code InternalContextSigner}: that
 * signs a fixed set of pipe-delimited internal fields with a platform-wide secret for a
 * service-to-service hop, and its canonical string is an internal detail free to change.
 * This one signs an arbitrary body with a per-endpoint secret and is a frozen, publicly
 * documented, third-party-implemented contract. Sharing an implementation would couple a
 * changeable internal format to an unchangeable external one — the same reason
 * {@code WebhookEventType} does not reuse payment-service's internal event names.
 */
@Component
public class WebhookSigner {

    /** The header a merchant reads to verify a delivery. */
    public static final String SIGNATURE_HEADER = "PaymentFlow-Signature";

    private static final String ALGORITHM = "HmacSHA256";
    private static final String TIMESTAMP_FIELD = "t";
    private static final String SIGNATURE_FIELD = "v1";
    private static final char FIELD_SEPARATOR = ',';
    private static final char SIGNED_PAYLOAD_SEPARATOR = '.';

    /**
     * Builds the header value for a body signed at {@code timestamp} with one or more
     * secrets (the current one, plus the superseded one during a rotation window).
     */
    public String signatureHeader(String body, Instant timestamp, List<String> secrets) {
        long epochSecond = timestamp.getEpochSecond();
        StringBuilder header = new StringBuilder()
                .append(TIMESTAMP_FIELD).append('=').append(epochSecond);
        for (String secret : secrets) {
            header.append(FIELD_SEPARATOR)
                    .append(SIGNATURE_FIELD).append('=').append(sign(body, epochSecond, secret));
        }
        return header.toString();
    }

    /** The lowercase hex HMAC-SHA256 of {@code "{timestamp}.{body}"} under one secret. */
    public String sign(String body, long epochSecond, String secret) {
        return hmacHex(secret, epochSecond + String.valueOf(SIGNED_PAYLOAD_SEPARATOR) + body);
    }

    /**
     * Verifies a header the way a merchant's own code would — recomputing over the body
     * received, comparing in constant time, and enforcing the tolerance window. Exists so
     * this platform's tests can assert the documented algorithm is what the documented
     * algorithm verifies, rather than asserting the signer agrees with itself.
     */
    public boolean verify(String body, String signatureHeader, String secret, Instant now, long toleranceSeconds) {
        if (signatureHeader == null || signatureHeader.isBlank()) {
            return false;
        }

        Long timestamp = null;
        List<String> candidates = new ArrayList<>();
        for (String element : signatureHeader.split(String.valueOf(FIELD_SEPARATOR))) {
            String[] pair = element.trim().split("=", 2);
            if (pair.length != 2) {
                continue;
            }
            if (TIMESTAMP_FIELD.equals(pair[0])) {
                try {
                    timestamp = Long.parseLong(pair[1]);
                } catch (NumberFormatException e) {
                    return false;
                }
            } else if (SIGNATURE_FIELD.equals(pair[0])) {
                candidates.add(pair[1]);
            }
        }
        if (timestamp == null || candidates.isEmpty()) {
            return false;
        }
        // Absolute skew, not just "too old": a timestamp far in the future is equally a
        // sign the header was not produced by us for this delivery.
        if (Math.abs(now.getEpochSecond() - timestamp) > toleranceSeconds) {
            return false;
        }

        String expected = sign(body, timestamp, secret);
        boolean matched = false;
        for (String candidate : candidates) {
            // No short-circuit: every candidate is compared in constant time, and the
            // loop does not break on success, so the work done is independent of which
            // (or whether a) signature matched.
            matched |= MessageDigest.isEqual(
                    expected.getBytes(StandardCharsets.UTF_8), candidate.getBytes(StandardCharsets.UTF_8));
        }
        return matched;
    }

    private static String hmacHex(String secret, String signedPayload) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM));
            return HexFormat.of().formatHex(mac.doFinal(signedPayload.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HmacSHA256 is unavailable", e);
        }
    }
}
