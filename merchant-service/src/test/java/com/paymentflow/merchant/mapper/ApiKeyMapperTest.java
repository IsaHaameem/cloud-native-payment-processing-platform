package com.paymentflow.merchant.mapper;

import com.paymentflow.merchant.domain.ApiKey;
import com.paymentflow.merchant.domain.ApiKeyType;
import com.paymentflow.merchant.domain.KeyMode;
import com.paymentflow.merchant.dto.ApiKeyIssuedResponse;
import com.paymentflow.merchant.dto.ApiKeyResponse;
import com.paymentflow.merchant.service.ApiKeyService;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the dashboard is allowed to learn about a key (M23.5).
 *
 * <p>Two properties, and both are one-directional: the management view must carry enough to tell a
 * retiring key from a healthy one, and neither view may ever carry the secret except the one that
 * exists to carry it exactly once.
 */
class ApiKeyMapperTest {

    private final ApiKeyMapper mapper = new ApiKeyMapper();

    /**
     * The M23.5 regression. Before this field existed, a rotated-out key came back with
     * {@code revokedAt} and {@code expiresAt} both null — identical on the wire to the replacement
     * that had just superseded it — so a dashboard could not say which of two identically named
     * keys was about to stop working.
     */
    @Test
    void theManagementViewCarriesTheGraceDeadlineOfARotatedOutKey() {
        ApiKey rotatedOut = key();
        rotatedOut.rotateWithGrace(Duration.ofHours(24));

        ApiKeyResponse response = mapper.toResponse(rotatedOut);

        assertThat(response.graceExpiresAt())
                .describedAs("the moment this key stops authenticating")
                .isNotNull()
                .isEqualTo(rotatedOut.getGraceExpiresAt());
        assertThat(response.revokedAt()).isNull();
    }

    @Test
    void aKeyThatWasNeverRotatedHasNoGraceDeadline() {
        assertThat(mapper.toResponse(key()).graceExpiresAt()).isNull();
    }

    /**
     * The management view is returned by {@code GET .../api-keys} on every page load, so a secret
     * on it would be a secret rendered indefinitely. It carries the 12-character visible prefix
     * instead, which is what identifies a key without being able to authenticate as one.
     */
    @Test
    void theManagementViewCarriesAPrefixAndNoWayToReconstructTheSecret() {
        ApiKeyResponse response = mapper.toResponse(key());

        assertThat(response.keyPrefix()).isEqualTo("sk_live_abc1");
        assertThat(response.toString())
                .describedAs("no field of the management view may contain the hash or the secret")
                .doesNotContain("somehash");
    }

    /** The one view that does carry it, and the only moment it is knowable. */
    @Test
    void theIssuedViewCarriesTheRawSecretExactlyWhereItIsExpected() {
        ApiKeyIssuedResponse issued =
                mapper.toIssuedResponse(new ApiKeyService.IssuedApiKey("sk_live_abc1_therest", key()));

        assertThat(issued.apiKey()).isEqualTo("sk_live_abc1_therest");
        assertThat(issued.keyPrefix()).isEqualTo("sk_live_abc1");
    }

    private static ApiKey key() {
        return ApiKey.issue(UUID.randomUUID(), ApiKeyType.SECRET, KeyMode.LIVE, "Prod key",
                "sk_live_abc1", "somehash", List.of("*"), null);
    }
}
