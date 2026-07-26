package com.paymentflow.notification.service;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The signature contract (M18.4, D105). This is the one piece of M18 that becomes
 * third-party code the moment it ships — every integrator reimplements the verification
 * side — so it is tested against <b>committed vectors produced by an independent
 * implementation</b> rather than against the signer's agreement with itself, which would
 * prove nothing about the specification.
 *
 * <p>The same {@code webhook-signature-vectors.json} is verified by {@code verify.js} and
 * {@code verify.py} beside it (D136), and is intended to become M22's SDK fixture, so a
 * future SDK that drifts fails against the same file rather than in a merchant's logs.
 */
class WebhookSignerTest {

    private static final String VECTORS = "/signature-vectors/webhook-signature-vectors.json";
    private static final long TOLERANCE_SECONDS = 300;

    private final WebhookSigner signer = new WebhookSigner();

    private static JsonNode vectors() {
        try (InputStream in = WebhookSignerTest.class.getResourceAsStream(VECTORS)) {
            return JsonMapper.builder().build().readTree(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("Signature vectors are missing from the test classpath", e);
        }
    }

    @Test
    void everyCommittedVectorMatchesThisImplementation() {
        JsonNode cases = vectors().get("vectors");
        assertThat(cases).isNotEmpty();

        for (JsonNode vector : cases) {
            String actual = signer.sign(
                    vector.get("body").asString(),
                    vector.get("timestamp").asLong(),
                    vector.get("secret").asString());

            assertThat(actual)
                    .describedAs("vector '%s'", vector.get("name").asString())
                    .isEqualTo(vector.get("expectedV1").asString());
        }
    }

    @Test
    void aUnicodeBodyIsSignedAsUtf8() {
        // Present as its own case because a platform that signs in the JVM's default
        // charset agrees with itself perfectly and disagrees with every other language —
        // the exact failure mode this milestone's cross-language check exists to catch.
        JsonNode unicode = null;
        for (JsonNode vector : vectors().get("vectors")) {
            if ("unicode_body".equals(vector.get("name").asString())) {
                unicode = vector;
            }
        }
        assertThat(unicode).isNotNull();
        assertThat(signer.sign(unicode.get("body").asString(), unicode.get("timestamp").asLong(),
                unicode.get("secret").asString()))
                .isEqualTo(unicode.get("expectedV1").asString());
    }

    @Test
    void theHeaderCarriesTheTimestampAndOneSignaturePerActiveSecret() {
        Instant now = Instant.ofEpochSecond(1785758400L);

        String single = signer.signatureHeader("{}", now, List.of("whsec_one"));
        assertThat(single).startsWith("t=1785758400,v1=");
        assertThat(single.split("v1=")).hasSize(2);

        // During a rotation window a delivery carries both signatures, so a receiver that
        // has switched and one that has not both verify (§4.5's dual-secret window).
        String dual = signer.signatureHeader("{}", now, List.of("whsec_one", "whsec_two"));
        assertThat(dual.split("v1=")).hasSize(3);
        assertThat(signer.verify("{}", dual, "whsec_one", now, TOLERANCE_SECONDS)).isTrue();
        assertThat(signer.verify("{}", dual, "whsec_two", now, TOLERANCE_SECONDS)).isTrue();
        assertThat(signer.verify("{}", dual, "whsec_three", now, TOLERANCE_SECONDS)).isFalse();
    }

    @Test
    void aSignatureOutsideTheToleranceWindowIsRejected() {
        Instant signedAt = Instant.ofEpochSecond(1785758400L);
        String header = signer.signatureHeader("{}", signedAt, List.of("whsec_one"));

        assertThat(signer.verify("{}", header, "whsec_one", signedAt.plusSeconds(299), TOLERANCE_SECONDS)).isTrue();
        // This is the whole reason the timestamp is inside the signed payload (D105): a
        // signature over the body alone would still verify here, forever.
        assertThat(signer.verify("{}", header, "whsec_one", signedAt.plusSeconds(301), TOLERANCE_SECONDS)).isFalse();
        // Skew is absolute — a timestamp far in the future is equally not ours.
        assertThat(signer.verify("{}", header, "whsec_one", signedAt.minusSeconds(301), TOLERANCE_SECONDS)).isFalse();
    }

    @Test
    void aTamperedBodyOrTimestampFailsVerification() {
        Instant now = Instant.ofEpochSecond(1785758400L);
        String body = "{\"amountMinor\":5000}";
        String header = signer.signatureHeader(body, now, List.of("whsec_one"));

        assertThat(signer.verify(body, header, "whsec_one", now, TOLERANCE_SECONDS)).isTrue();
        assertThat(signer.verify("{\"amountMinor\":500000}", header, "whsec_one", now, TOLERANCE_SECONDS)).isFalse();
        // Editing t to move the message into the window fails, because t is signed.
        String movedTimestamp = header.replace("t=1785758400", "t=1785758999");
        assertThat(signer.verify(body, movedTimestamp, "whsec_one", now.plusSeconds(500), TOLERANCE_SECONDS)).isFalse();
    }

    @Test
    void malformedHeadersAreRejectedRatherThanThrowing() {
        Instant now = Instant.ofEpochSecond(1785758400L);

        assertThat(signer.verify("{}", null, "whsec_one", now, TOLERANCE_SECONDS)).isFalse();
        assertThat(signer.verify("{}", "", "whsec_one", now, TOLERANCE_SECONDS)).isFalse();
        assertThat(signer.verify("{}", "garbage", "whsec_one", now, TOLERANCE_SECONDS)).isFalse();
        assertThat(signer.verify("{}", "t=notanumber,v1=aa", "whsec_one", now, TOLERANCE_SECONDS)).isFalse();
        assertThat(signer.verify("{}", "v1=aa", "whsec_one", now, TOLERANCE_SECONDS)).isFalse();
        assertThat(signer.verify("{}", "t=1785758400", "whsec_one", now, TOLERANCE_SECONDS)).isFalse();
    }

    @Test
    void theWhsecPrefixIsPartOfTheKeyAndIsNotStripped() {
        Instant now = Instant.ofEpochSecond(1785758400L);

        // Stated explicitly in the spec and asserted here, because "strip the prefix
        // before using it as a key" is a plausible reading that would silently produce a
        // wrong signature for every delivery.
        assertThat(signer.sign("{}", now.getEpochSecond(), "whsec_abc"))
                .isNotEqualTo(signer.sign("{}", now.getEpochSecond(), "abc"));
    }
}
