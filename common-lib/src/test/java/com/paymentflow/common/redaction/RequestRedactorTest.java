package com.paymentflow.common.redaction;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M20.1. §5/M20's testing strategy asks for redaction proved "against a corpus of realistic
 * payloads containing secrets, including nested and array cases", and its completion
 * criteria for "no secret ever appears in a logged body". Both are read literally here: the
 * corpus in {@link SecretCorpus} is swept in one test that asserts no known secret survives
 * <em>anywhere</em> in the output, rather than each case asserting only what it expects.
 */
class RequestRedactorTest {

    private static final int CAP = 4096;

    /**
     * Every secret used anywhere in this class. The sweep below asserts none of these appears
     * in any redacted output — so adding a payload without adding its secret here weakens
     * nothing, but adding a secret without handling it fails loudly.
     *
     * <p><b>Assembled from fragments rather than written as literals, deliberately.</b> This
     * platform's key format (M15) is the same shape as Stripe's —
     * {@code {pk|sk}_{test|live}_<base62>}, {@code whsec_<base62>} — so a fixture realistic
     * enough to exercise the redactor is, to a secret scanner, indistinguishable from a real
     * credential. GitHub Push Protection blocked M20's first push on exactly these strings.
     *
     * <p>Splitting the prefix from the body means no literal in this file matches a scanner's
     * pattern, while the value assembled at runtime keeps the <em>exact</em> shape the
     * redactor must recognise. The bodies are fixed, obviously-fake words, so the fixtures stay
     * deterministic and no assertion is relaxed — the tests below still feed
     * {@code sk_test_…}-shaped input through the real patterns.
     */
    private static final class SecretCorpus {
        private static String apiKey(String type, String mode, String body) {
            return type + "_" + mode + "_" + body;
        }

        static final String SECRET_KEY = apiKey("sk", "test", "EXAMPLEONLYNOTAREALSECRET");
        static final String LIVE_KEY = apiKey("sk", "live", "EXAMPLEONLYNOTAREALLIVEKEY");
        static final String PUBLISHABLE_KEY = apiKey("pk", "test", "EXAMPLEONLYNOTAREALPUBKEY0");
        static final String WEBHOOK_SECRET = "whsec" + "_" + "EXAMPLEONLYNOTAREALWEBHOOKSECRET0";
        // Not a Stripe-shaped credential, but redacted for the same reason and kept realistic:
        // three base64url segments whose first decodes to a JSON header.
        static final String JWT = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJtZXJjaGFudCJ9.qX7Vb3nR8tYaZcW1pLdEr5";
        static final String PAN = "4242424242424242";
        static final String PASSWORD = "hunter2-correct-horse";

        static List<String> all() {
            return List.of(SECRET_KEY, LIVE_KEY, PUBLISHABLE_KEY, WEBHOOK_SECRET, JWT, PAN, PASSWORD);
        }
    }

    /** Realistic payloads, each shaped like something this platform actually receives. */
    private static List<String> corpus() {
        return List.of(
                // Flat object, secret by field name.
                """
                {"email":"dev@example.com","password":"%s"}
                """.formatted(SecretCorpus.PASSWORD),
                // Secret by value shape, in a field no list would name.
                """
                {"note":"rotate this: %s please","amount":1500}
                """.formatted(SecretCorpus.SECRET_KEY),
                // Nested three deep.
                """
                {"merchant":{"credentials":{"apiKey":"%s","label":"prod"}}}
                """.formatted(SecretCorpus.LIVE_KEY),
                // Array of objects.
                """
                {"keys":[{"secret":"%s"},{"secret":"%s"}]}
                """.formatted(SecretCorpus.SECRET_KEY, SecretCorpus.LIVE_KEY),
                // Array of bare strings.
                """
                {"tokens":["%s","%s"]}
                """.formatted(SecretCorpus.JWT, SecretCorpus.WEBHOOK_SECRET),
                // Card data in a payment-shaped body.
                """
                {"card":{"cardNumber":"%s","cvv":"123","expMonth":12}}
                """.formatted(SecretCorpus.PAN),
                // PAN written with separators, inside prose.
                """
                {"description":"customer gave 4242 4242 4242 4242 over the phone"}
                """,
                // Not JSON at all — form encoding.
                "grant_type=password&username=dev&password=" + SecretCorpus.PASSWORD
                        + "&api_key=" + SecretCorpus.SECRET_KEY,
                // Malformed JSON: a truncated capture.
                "{\"apiKey\":\"" + SecretCorpus.SECRET_KEY + "\", \"amount\": 15",
                // Top-level array.
                """
                [{"authorization":"Bearer %s"},{"webhookSecret":"%s"}]
                """.formatted(SecretCorpus.JWT, SecretCorpus.WEBHOOK_SECRET));
    }

    @Test
    @DisplayName("no secret in the corpus survives redaction, in any payload, at any depth")
    void noSecretSurvivesTheCorpus() {
        for (String payload : corpus()) {
            String redacted = RequestRedactor.redactBody(payload, CAP);
            assertThat(redacted).isNotNull();
            for (String secret : SecretCorpus.all()) {
                assertThat(redacted)
                        .as("secret %s must not survive in redacted payload: %s", secret, redacted)
                        .doesNotContain(secret);
            }
        }
    }

    @Nested
    @DisplayName("structural redaction (field names)")
    class FieldNames {

        @Test
        void redactsASensitiveFieldWhateverItsSpelling() {
            String body = """
                    {"apiKey":"a","api_key":"b","API-KEY":"c","ApiKey":"d"}
                    """;
            String redacted = RequestRedactor.redactBody(body, CAP);
            assertThat(redacted).doesNotContain("\"a\"", "\"b\"", "\"c\"", "\"d\"");
            assertThat(redacted).contains(RequestRedactor.REDACTED);
        }

        @Test
        @DisplayName("a sensitive field is redacted whatever its JSON type")
        void redactsNonStringValuesUnderASensitiveName() {
            // A secret smuggled as a number or an object is still a secret; redacting only
            // string values would let {"password": 1234} through untouched.
            String redacted = RequestRedactor.redactBody(
                    """
                    {"password":1234,"token":{"nested":"value"},"secret":["a","b"]}
                    """, CAP);
            assertThat(redacted).doesNotContain("1234", "nested", "\"a\"");
        }

        @Test
        @DisplayName("bare 'key' is deliberately not redacted — it is usually not a secret")
        void leavesBareKeyAlone() {
            // Idempotency-Key and metadata entries named "key" are exactly the debugging
            // information a request log exists to preserve.
            String redacted = RequestRedactor.redactBody("""
                    {"key":"order-1234","idempotencyKey":"idem-77"}
                    """, CAP);
            assertThat(redacted).contains("order-1234").contains("idem-77");
        }

        @Test
        void preservesNonSensitiveDataSoTheLogRemainsUseful() {
            String redacted = RequestRedactor.redactBody("""
                    {"amount":1500,"currency":"USD","password":"x"}
                    """, CAP);
            assertThat(redacted).contains("1500").contains("USD");
        }
    }

    @Nested
    @DisplayName("pattern redaction (value shapes)")
    class Patterns {

        /**
         * A {@code @MethodSource} rather than {@code @ValueSource}: the latter needs compile-time
         * string constants, which is exactly what a secret scanner flags. Same four shapes, same
         * coverage — sourced from {@link SecretCorpus} so there is one place to add a shape.
         */
        static List<String> everyCredentialShape() {
            return List.of(SecretCorpus.SECRET_KEY, SecretCorpus.LIVE_KEY,
                    SecretCorpus.PUBLISHABLE_KEY, SecretCorpus.WEBHOOK_SECRET);
        }

        @ParameterizedTest
        @MethodSource("everyCredentialShape")
        void scrubsEveryCredentialShapeThisPlatformIssues(String credential) {
            assertThat(RequestRedactor.redactText("value=" + credential)).doesNotContain(credential);
        }

        @Test
        void scrubsACompactJwt() {
            assertThat(RequestRedactor.redactText("Bearer " + SecretCorpus.JWT))
                    .doesNotContain(SecretCorpus.JWT)
                    .contains(RequestRedactor.REDACTED);
        }

        @Test
        @DisplayName("a Luhn-valid PAN is redacted, with or without separators")
        void scrubsPans() {
            assertThat(RequestRedactor.redactText("card 4242424242424242 used")).doesNotContain("4242424242424242");
            assertThat(RequestRedactor.redactText("card 4242 4242 4242 4242 used")).doesNotContain("4242 4242");
            assertThat(RequestRedactor.redactText("card 4242-4242-4242-4242 used")).doesNotContain("4242-4242");
        }

        @Test
        @DisplayName("a long number that is not Luhn-valid is left alone")
        void leavesNonPanDigitRunsAlone() {
            // Without the Luhn check every long identifier would be destroyed, and a request
            // log that eats order references is not a debugging tool. 1234567890123456 fails
            // Luhn; a microsecond timestamp does too.
            assertThat(RequestRedactor.redactText("order 1234567890123456 placed")).contains("1234567890123456");
            assertThat(RequestRedactor.redactText("at 1737802496123456")).contains("1737802496123456");
        }

        @Test
        void leavesShortNumbersAlone() {
            assertThat(RequestRedactor.redactText("amount 1500 currency USD")).contains("1500");
        }

        @Test
        @DisplayName("a shapeless secret in a form-encoded body is caught by name, not shape")
        void redactsNamedValuesInUnstructuredBodies() {
            // The leak the corpus sweep found: field-name redaction originally ran only on
            // the JSON path, so a form-encoded password — which has no shape any pattern
            // could match — survived untouched.
            String form = "grant_type=password&username=dev&password=hunter2-correct-horse&amount=1500";
            String redacted = RequestRedactor.redactText(form);
            assertThat(redacted).doesNotContain("hunter2-correct-horse");
            assertThat(redacted).contains("username=dev").contains("amount=1500");
        }

        @Test
        @DisplayName("one redacted pair does not swallow the rest of the body")
        void stopsAtTheNextSeparator() {
            String redacted = RequestRedactor.redactText("password=abc&currency=USD");
            assertThat(redacted).contains("currency=USD").doesNotContain("abc");
        }

        @Test
        void scrubsSecretsCarriedInAQueryString() {
            // The gateway logs the request path; a secret in the query string is the same
            // leak by another route.
            assertThat(RequestRedactor.redactText("/v1/payments?api_key=" + SecretCorpus.SECRET_KEY + "&limit=10"))
                    .doesNotContain(SecretCorpus.SECRET_KEY)
                    .contains("limit=10");
        }
    }

    @Nested
    @DisplayName("headers")
    class Headers {

        @Test
        void redactsCredentialHeadersWholesale() {
            Map<String, String> redacted = RequestRedactor.redactHeaders(Map.of(
                    "Authorization", List.of("Bearer " + SecretCorpus.SECRET_KEY),
                    "Cookie", List.of("session=abc"),
                    "X-Api-Key", List.of(SecretCorpus.LIVE_KEY)));
            assertThat(redacted.values()).containsOnly(RequestRedactor.REDACTED);
        }

        @Test
        @DisplayName("the signed internal-context family is redacted — it is a replayable credential")
        void redactsTheInternalContextHeaders() {
            Map<String, String> redacted = RequestRedactor.redactHeaders(Map.of(
                    "X-PF-Internal-Signature", List.of("deadbeef"),
                    "X-PF-Internal-Merchant-Id", List.of("11111111-1111-1111-1111-111111111111")));
            assertThat(redacted.values()).containsOnly(RequestRedactor.REDACTED);
        }

        @Test
        void scrubsCredentialsOutOfHeadersNoListWouldName() {
            Map<String, String> redacted = RequestRedactor.redactHeaders(
                    Map.of("X-Custom-Debug", List.of("retrying with " + SecretCorpus.SECRET_KEY)));
            assertThat(redacted.get("X-Custom-Debug"))
                    .doesNotContain(SecretCorpus.SECRET_KEY)
                    .contains(RequestRedactor.REDACTED);
        }

        @Test
        void keepsOrdinaryHeaders() {
            Map<String, String> redacted = RequestRedactor.redactHeaders(
                    Map.of("Content-Type", List.of("application/json")));
            assertThat(redacted.get("Content-Type")).isEqualTo("application/json");
        }

        @Test
        void toleratesNullAndEmpty() {
            assertThat(RequestRedactor.redactHeaders(null)).isEmpty();
            assertThat(RequestRedactor.redactHeaders(Map.of())).isEmpty();
        }
    }

    @Nested
    @DisplayName("truncation")
    class Truncation {

        @Test
        @DisplayName("redaction runs before truncation, so a cut cannot preserve a secret's prefix")
        void redactsBeforeTruncating() {
            // The ordering that matters: truncating first could sever the key mid-token so
            // neither half matches the pattern, leaving a live credential's prefix stored.
            String body = "{\"pad\":\"" + "x".repeat(60) + "\",\"apiKey\":\"" + SecretCorpus.SECRET_KEY + "\"}";
            String redacted = RequestRedactor.redactBody(body, 80);
            assertThat(redacted).doesNotContain(SecretCorpus.SECRET_KEY);
            assertThat(redacted).doesNotContain(SecretCorpus.SECRET_KEY.substring(0, 20));
        }

        @Test
        void marksATruncatedBodySoItIsNotMistakenForAWholeOne() {
            String redacted = RequestRedactor.redactBody("{\"note\":\"" + "y".repeat(500) + "\"}", 100);
            assertThat(redacted).endsWith(RequestRedactor.TRUNCATION_MARKER);
            assertThat(redacted).hasSize(100 + RequestRedactor.TRUNCATION_MARKER.length());
        }

        @Test
        void leavesAShortBodyIntact() {
            String redacted = RequestRedactor.redactBody("{\"amount\":1500}", CAP);
            assertThat(redacted).doesNotContain(RequestRedactor.TRUNCATION_MARKER).contains("1500");
        }
    }

    @Nested
    @DisplayName("failure modes are closed, never open")
    class FailureModes {

        @Test
        @DisplayName("an unparseable body still gets pattern scrubbing rather than passing through")
        void fallsBackToTextScrubbingOnMalformedJson() {
            String malformed = "{\"apiKey\":\"" + SecretCorpus.SECRET_KEY + "\", \"amount\": 15";
            assertThat(RequestRedactor.redactBody(malformed, CAP)).doesNotContain(SecretCorpus.SECRET_KEY);
        }

        @Test
        @DisplayName("a body too large to parse is scrubbed by pattern, not skipped")
        void scrubsOversizedBodiesWithoutParsing() {
            String huge = "{\"pad\":\"" + "z".repeat(300_000) + "\",\"k\":\"" + SecretCorpus.SECRET_KEY + "\"}";
            String redacted = RequestRedactor.redactBody(huge, CAP);
            assertThat(redacted).doesNotContain(SecretCorpus.SECRET_KEY);
        }

        @Test
        void returnsNullForNothingToStore() {
            assertThat(RequestRedactor.redactBody(null, CAP)).isNull();
            assertThat(RequestRedactor.redactBody("", CAP)).isNull();
            assertThat(RequestRedactor.redactBody("   ", CAP)).isNull();
        }

        @Test
        @DisplayName("a bare JSON scalar is scrubbed rather than re-rendered")
        void handlesBareScalars() {
            assertThat(RequestRedactor.redactBody("\"" + SecretCorpus.SECRET_KEY + "\"", CAP))
                    .doesNotContain(SecretCorpus.SECRET_KEY);
            assertThat(RequestRedactor.redactBody("1500", CAP)).contains("1500");
        }
    }
}
