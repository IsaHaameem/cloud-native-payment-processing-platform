package com.paymentflow.common.query;

import com.paymentflow.common.exception.BadRequestException;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Cursor encoding, and specifically every way a client can get it wrong (M19.1, D107).
 *
 * <p>A cursor is the only server-issued token a client is expected to hand back
 * verbatim, which makes it the one list parameter an attacker will try to edit. The
 * signature is defence in depth — the repository layer takes merchant and mode from the
 * verified context regardless (D101) — but "it would not have worked anyway" is a poor
 * reason to leave a forged token accepted, so every tamper is asserted to fail loudly.
 */
class CursorCodecTest {

    private static final String SECRET = "test-only-cursor-secret";
    private static final Instant CREATED_AT = Instant.parse("2026-08-01T12:00:00.123Z");

    private final CursorCodec codec = new CursorCodec(SECRET);
    private final UUID merchantId = UUID.randomUUID();
    private final UUID rowId = UUID.randomUUID();

    private Cursor aCursor() {
        return new Cursor(CREATED_AT, rowId, merchantId, "test");
    }

    @Test
    void aCursorRoundTripsExactly() {
        Cursor decoded = codec.decode(codec.encode(aCursor()), merchantId, "test");

        assertThat(decoded.createdAt()).isEqualTo(CREATED_AT);
        assertThat(decoded.id()).isEqualTo(rowId);
        assertThat(decoded.merchantId()).isEqualTo(merchantId);
        assertThat(decoded.mode()).isEqualTo("test");
    }

    @Test
    void millisecondPrecisionSurvivesTheRoundTrip() {
        // The keyset predicate is (created_at, id) < (:createdAt, :id). If the encoded
        // timestamp lost precision, the boundary row would be re-returned or skipped —
        // exactly the bug cursors exist to prevent.
        Instant precise = Instant.parse("2026-08-01T12:00:00.999Z");
        Cursor decoded = codec.decode(codec.encode(new Cursor(precise, rowId, merchantId, "live")), merchantId, "live");

        assertThat(decoded.createdAt()).isEqualTo(precise);
    }

    @Test
    void theCursorIsOpaqueAndUrlSafe() {
        String encoded = codec.encode(aCursor());

        // Base64URL: no +, / or = that would need escaping in a query string.
        assertThat(encoded).doesNotContain("+", "/", "=");
        // Not readable at a glance — a client must treat it as a token, not parse it.
        assertThat(encoded).doesNotContain(merchantId.toString(), rowId.toString());
    }

    @Test
    void anEditedPayloadIsRejected() {
        String encoded = codec.encode(aCursor());
        String decodedText = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);

        // Move the position far into the future while keeping the original signature.
        String tampered = decodedText.replace(String.valueOf(CREATED_AT.toEpochMilli()), "99999999999999");
        String reEncoded = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(tampered.getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> codec.decode(reEncoded, merchantId, "test"))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void aCursorForAnotherMerchantIsRejectedEvenThoughItIsValidlySigned() {
        // The platform signed this one — it is not a forgery, it simply belongs to
        // someone else. Refused explicitly rather than silently returning an empty page,
        // which is the difference between a clear error and an unexplainable one.
        String encoded = codec.encode(aCursor());

        assertThatThrownBy(() -> codec.decode(encoded, UUID.randomUUID(), "test"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("not valid for this request");
    }

    @Test
    void aCursorFromTheOtherModeIsRejected() {
        String encoded = codec.encode(aCursor());

        assertThatThrownBy(() -> codec.decode(encoded, merchantId, "live"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("not valid for this request");
    }

    @Test
    void aCursorSignedWithADifferentSecretIsRejected() {
        String foreign = new CursorCodec("some-other-deployments-secret").encode(aCursor());

        assertThatThrownBy(() -> codec.decode(foreign, merchantId, "test"))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void malformedCursorsAreRejectedAsBadRequestsRatherThanCrashing() {
        // Every one of these is a client-supplied value being wrong. None may surface as
        // a 500: a garbage query parameter is not a platform failure.
        for (String bad : new String[]{
                "not-base64-!!!",
                Base64.getUrlEncoder().withoutPadding().encodeToString("no-separator".getBytes(StandardCharsets.UTF_8)),
                Base64.getUrlEncoder().withoutPadding().encodeToString("a|b|c".getBytes(StandardCharsets.UTF_8)),
                Base64.getUrlEncoder().withoutPadding().encodeToString("".getBytes(StandardCharsets.UTF_8)),
        }) {
            assertThatThrownBy(() -> codec.decode(bad, merchantId, "test"))
                    .describedAs("cursor '%s'", bad)
                    .isInstanceOf(BadRequestException.class);
        }
    }

    @Test
    void aWellFormedButUnparseablePayloadIsRejectedRatherThanThrowing() {
        // Correct field count and a valid signature, but the fields are not a timestamp
        // and two UUIDs. Reaches the parsing code, so it proves parsing is guarded too.
        CursorCodec sameSecret = new CursorCodec(SECRET);
        String payload = "not-a-number|not-a-uuid|" + merchantId + "|test";
        String signed = sameSecret.encode(aCursor());
        // Rebuild with the bogus payload but a signature computed over it, by round-tripping
        // through a codec that will sign whatever it is given.
        String forged = Base64.getUrlEncoder().withoutPadding().encodeToString(
                (payload + "|" + signatureOf(payload)).getBytes(StandardCharsets.UTF_8));

        assertThat(signed).isNotBlank();
        assertThatThrownBy(() -> codec.decode(forged, merchantId, "test"))
                .isInstanceOf(BadRequestException.class);
    }

    /** Recomputes what the codec would sign, so the test can build a validly-signed-but-invalid payload. */
    private static String signatureOf(String payload) {
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return java.util.HexFormat.of()
                    .formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)))
                    .substring(0, 32);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
