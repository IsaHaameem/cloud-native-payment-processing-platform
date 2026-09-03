package dev.paymentflow;

import dev.paymentflow.internal.Json;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verified against the same five vectors in
 * {@code notification-service/.../webhook-signature-vectors.json} that the platform's own signer
 * and the reference {@code verify.js} / {@code verify.py} are checked against. Two SDKs that
 * agreed only with each other could both be wrong.
 */
class WebhooksTest {

    private static final Path VECTORS = Path.of("..", "..", "notification-service", "src", "test", "resources",
            "signature-vectors", "webhook-signature-vectors.json");

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> vectors() throws Exception {
        Map<String, Object> doc = Json.parseObject(Files.readString(VECTORS, StandardCharsets.UTF_8));
        return (List<Map<String, Object>>) doc.get("vectors");
    }

    @Test
    void everyVectorProducesItsPublishedSignature() throws Exception {
        List<Map<String, Object>> vectors = vectors();
        assertFalse(vectors.isEmpty(), "the shared vector file must exist and be non-empty");
        for (Map<String, Object> vector : vectors) {
            String secret = (String) vector.get("secret");
            long timestamp = ((Number) vector.get("timestamp")).longValue();
            byte[] body = ((String) vector.get("body")).getBytes(StandardCharsets.UTF_8);
            assertEquals(vector.get("expectedV1"), Webhooks.signPayload(secret, timestamp, body),
                    "vector: " + vector.get("name"));
        }
    }

    @Test
    void constructEventVerifiesAGenuineDeliveryAndReturnsItsEvent() throws Exception {
        Map<String, Object> vector = vectors().stream()
                .filter(v -> v.get("name").equals("realistic_payment_authorized"))
                .findFirst().orElseThrow();
        String secret = (String) vector.get("secret");
        long timestamp = ((Number) vector.get("timestamp")).longValue();
        byte[] body = ((String) vector.get("body")).getBytes(StandardCharsets.UTF_8);
        String header = Webhooks.signatureHeaderFor(secret, timestamp, body);

        WebhookEvent event = Webhooks.constructEvent(body, header, secret, Duration.ofMinutes(5),
                Instant.ofEpochSecond(timestamp));

        assertEquals("evt_3f2504e04f8941d39a0c0305e82c3301", event.id());
        assertEquals("payment.authorized", event.type());
        assertEquals("2026-08-01", event.apiVersion());
        assertEquals("payment", event.dataObject().get("object"));
    }

    @Test
    void aTamperedBodyIsAHostileSignatureFailure() throws Exception {
        Map<String, Object> vector = vectors().get(0);
        String secret = (String) vector.get("secret");
        long timestamp = ((Number) vector.get("timestamp")).longValue();
        byte[] original = ((String) vector.get("body")).getBytes(StandardCharsets.UTF_8);
        String header = Webhooks.signatureHeaderFor(secret, timestamp, original);

        byte[] tampered = ((String) vector.get("body") + " ").getBytes(StandardCharsets.UTF_8);
        assertThrows(WebhookSignatureException.class,
                () -> Webhooks.constructEvent(tampered, header, secret, Duration.ofMinutes(5),
                        Instant.ofEpochSecond(timestamp)));
    }

    @Test
    void aValidButStaleDeliveryIsATimestampFailureNotASignatureFailure() throws Exception {
        Map<String, Object> vector = vectors().stream()
                .filter(v -> v.get("name").equals("realistic_payment_authorized"))
                .findFirst().orElseThrow();
        String secret = (String) vector.get("secret");
        long timestamp = ((Number) vector.get("timestamp")).longValue();
        byte[] body = ((String) vector.get("body")).getBytes(StandardCharsets.UTF_8);
        String header = Webhooks.signatureHeaderFor(secret, timestamp, body);

        WebhookTimestampException e = assertThrows(WebhookTimestampException.class,
                () -> Webhooks.constructEvent(body, header, secret, Duration.ofSeconds(300),
                        Instant.ofEpochSecond(timestamp + 3600)));
        assertEquals(timestamp, e.timestamp());
        assertTrue(e.skewSeconds() >= 3600);
    }

    @Test
    void aRotationWindowHeaderWithTwoSignaturesVerifiesIfEitherMatches() {
        String secret = "whsec_current";
        long ts = 1785758400L;
        byte[] body = "{\"id\":\"evt_x\",\"type\":\"payment.captured\",\"data\":{}}".getBytes(StandardCharsets.UTF_8);
        String good = Webhooks.signPayload(secret, ts, body);
        String header = "t=" + ts + ",v1=0000000000000000000000000000000000000000000000000000000000000000,v1=" + good;

        WebhookEvent event = Webhooks.constructEvent(body, header, secret, Duration.ofMinutes(5), Instant.ofEpochSecond(ts));
        assertEquals("evt_x", event.id());
    }

    @Test
    void aHeaderWithNoTimestampIsRejected() {
        assertThrows(WebhookSignatureException.class,
                () -> Webhooks.constructEvent(new byte[0], "v1=abc", "whsec_x", Duration.ofMinutes(5), Instant.now()));
    }
}
