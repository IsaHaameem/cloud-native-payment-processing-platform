package dev.paymentflow;

import dev.paymentflow.internal.Json;
import dev.paymentflow.internal.JsonException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Webhook signature verification — the one part of this SDK that matters even to an integrator
 * who skipped the rest.
 *
 * <p>A receiver that does not verify signatures will accept a forged {@code payment.captured}
 * from anyone who learns the URL; one that verifies the body but ignores the timestamp will
 * accept a genuine delivery replayed forever. §4.5 calls the timestamp the detail homegrown
 * implementations most often get wrong, and this class exists so nobody has to get it right
 * twice.
 *
 * <p>The specification is M18's:
 * <pre>
 * PaymentFlow-Signature: t=1785758400,v1=5f2c…9ab
 *
 *   signed_payload = "{t}" + "." + "{raw request body}"
 *   v1             = lowercase hex of HMAC-SHA256(secret, signed_payload)
 *   secret         = the endpoint's whsec_… value as UTF-8 bytes, prefix included
 * </pre>
 * Several {@code v1} values, comma-separated, is how the dual-secret rotation window is
 * expressed. Any match is a match. This implementation is checked against the same five vectors
 * in {@code notification-service/.../webhook-signature-vectors.json} that the platform's own
 * signer and the reference {@code verify.js} / {@code verify.py} are checked against.
 */
public final class Webhooks {

    private Webhooks() {}

    /** The header every delivery carries. */
    public static final String SIGNATURE_HEADER = "PaymentFlow-Signature";

    /**
     * The default tolerance window: five minutes. Wide enough to survive ordinary clock drift
     * between two servers, narrow enough that a captured delivery is not replayable for the rest
     * of the afternoon.
     */
    public static final Duration DEFAULT_TOLERANCE = Duration.ofSeconds(300);

    /**
     * Verifies a delivery and returns its event.
     *
     * @param payload         the <b>raw</b> request body, exactly as received — the signature
     *                        covers the bytes that were sent, and parsing then re-serializing
     *                        does not round-trip them
     * @param signatureHeader the value of the {@code PaymentFlow-Signature} header
     * @param secret          the endpoint's {@code whsec_…} signing secret
     * @throws WebhookSignatureException the header is malformed, or nothing in it matched
     * @throws WebhookTimestampException the signature is valid and its timestamp is out of window
     * @throws WebhookPayloadException   it verified and is not an event envelope
     */
    public static WebhookEvent constructEvent(byte[] payload, String signatureHeader, String secret) {
        return constructEvent(payload, signatureHeader, secret, DEFAULT_TOLERANCE, Instant.now());
    }

    /** As {@link #constructEvent(byte[], String, String)}, with an explicit tolerance window. */
    public static WebhookEvent constructEvent(byte[] payload, String signatureHeader, String secret,
                                              Duration tolerance) {
        return constructEvent(payload, signatureHeader, secret, tolerance, Instant.now());
    }

    /** As above, taking a string body (encoded UTF-8). Prefer the {@code byte[]} form. */
    public static WebhookEvent constructEvent(String payload, String signatureHeader, String secret,
                                              Duration tolerance) {
        return constructEvent(payload.getBytes(StandardCharsets.UTF_8), signatureHeader, secret, tolerance,
                Instant.now());
    }

    /** The full form, with the clock injectable for tests. */
    public static WebhookEvent constructEvent(byte[] payload, String signatureHeader, String secret,
                                              Duration tolerance, Instant now) {
        if (secret == null || secret.isEmpty()) {
            throw new WebhookSignatureException("No signing secret. Pass the endpoint's whsec_… value.");
        }
        if (signatureHeader == null || signatureHeader.isEmpty()) {
            throw new WebhookSignatureException("No " + SIGNATURE_HEADER + " header on the request.");
        }
        if (tolerance == null || tolerance.isNegative()) {
            throw new WebhookSignatureException("tolerance must be a non-negative duration.");
        }
        byte[] body = payload == null ? new byte[0] : payload;

        ParsedHeader parsed = parseHeader(signatureHeader);

        // Signature before timestamp, deliberately: checking the window first would let anyone
        // with the URL and a stopwatch learn whether a body was correctly signed by observing
        // which error came back, and would report a garbage header as "too old".
        String expected = sign(secret, parsed.timestamp, body);
        boolean matched = false;
        for (String candidate : parsed.candidates) {
            if (constantTimeEquals(candidate, expected)) {
                matched = true;
                break;
            }
        }
        if (!matched) {
            throw new WebhookSignatureException(
                    "The signature does not match. Either the secret is wrong, or the payload is not the raw "
                            + "request body — re-serializing the JSON changes the bytes the signature covers.");
        }

        long skew = Math.abs(now.getEpochSecond() - parsed.timestamp);
        if (skew > tolerance.toSeconds()) {
            throw new WebhookTimestampException(
                    "The delivery's timestamp is " + skew + "s away from now, outside the "
                            + tolerance.toSeconds() + "s tolerance. This is a replayed delivery, or a clock is wrong.",
                    parsed.timestamp, skew);
        }

        return parseEvent(new String(body, StandardCharsets.UTF_8));
    }

    /**
     * Computes the {@code v1} value for a body. Exported so a caller can build a signed request
     * in their own tests without reimplementing the specification — the moment they would get it
     * subtly wrong and then write a test that passes against their own mistake.
     */
    public static String signPayload(String secret, long timestampEpochSeconds, byte[] payload) {
        return sign(secret, timestampEpochSeconds, payload == null ? new byte[0] : payload);
    }

    /** Builds a full header value, for the same reason {@link #signPayload} is exported. */
    public static String signatureHeaderFor(String secret, long timestampEpochSeconds, byte[] payload) {
        return "t=" + timestampEpochSeconds + ",v1=" + signPayload(secret, timestampEpochSeconds, payload);
    }

    // ── Internals ───────────────────────────────────────────────────────────────────────────

    private static String sign(String secret, long timestamp, byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            mac.update((timestamp + ".").getBytes(StandardCharsets.UTF_8));
            mac.update(body);
            return toHex(mac.doFinal());
        } catch (java.security.GeneralSecurityException e) {
            throw new WebhookSignatureException("HMAC-SHA256 is unavailable on this JVM: " + e.getMessage());
        }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            out.append(Character.forDigit((b >> 4) & 0xF, 16));
            out.append(Character.forDigit(b & 0xF, 16));
        }
        return out.toString();
    }

    private record ParsedHeader(long timestamp, List<String> candidates) {}

    private static ParsedHeader parseHeader(String header) {
        Long timestamp = null;
        List<String> candidates = new ArrayList<>();
        for (String element : header.split(",")) {
            int separator = element.indexOf('=');
            if (separator < 0) {
                continue;
            }
            String key = element.substring(0, separator).trim();
            String value = element.substring(separator + 1).trim();
            if (key.equals("t")) {
                if (!value.matches("\\d+")) {
                    throw new WebhookSignatureException(
                            "The " + SIGNATURE_HEADER + " header's timestamp is not an integer.");
                }
                timestamp = Long.parseLong(value);
            } else if (key.equals("v1") && !value.isEmpty()) {
                candidates.add(value);
            }
            // A future v2 alongside v1 is how this scheme would gain a second algorithm; an
            // unfamiliar field is skipped rather than rejected.
        }
        if (timestamp == null) {
            throw new WebhookSignatureException("The " + SIGNATURE_HEADER + " header has no t= timestamp.");
        }
        if (candidates.isEmpty()) {
            throw new WebhookSignatureException("The " + SIGNATURE_HEADER + " header has no v1= signature.");
        }
        return new ParsedHeader(timestamp, List.copyOf(candidates));
    }

    private static boolean constantTimeEquals(String candidate, String expected) {
        byte[] a = candidate.getBytes(StandardCharsets.UTF_8);
        byte[] b = expected.getBytes(StandardCharsets.UTF_8);
        // MessageDigest.isEqual is constant-time; it also handles the length check, and a v1 is
        // always 64 hex characters so a candidate of another length is malformed, not a near miss.
        return MessageDigest.isEqual(a, b);
    }

    @SuppressWarnings("unchecked")
    private static WebhookEvent parseEvent(String raw) {
        Object parsed;
        try {
            parsed = Json.parse(raw);
        } catch (JsonException e) {
            throw new WebhookPayloadException("The delivery verified but its body is not JSON.", e);
        }
        if (!(parsed instanceof Map)) {
            throw new WebhookPayloadException("The delivery verified but its body is not a JSON object.");
        }
        Map<String, Object> map = (Map<String, Object>) parsed;
        Object id = map.get("id");
        Object type = map.get("type");
        Object data = map.get("data");
        if (!(id instanceof String) || !(type instanceof String) || !(data instanceof Map)) {
            throw new WebhookPayloadException(
                    "The delivery verified but is not an event envelope — id, type and data are required.");
        }
        return new WebhookEvent(
                (String) id,
                map.get("object") instanceof String s ? s : null,
                (String) type,
                map.get("apiVersion") instanceof String s ? s : null,
                map.get("created") instanceof String s ? s : null,
                map.get("mode") instanceof String s ? s : null,
                (Map<String, Object>) data);
    }
}
