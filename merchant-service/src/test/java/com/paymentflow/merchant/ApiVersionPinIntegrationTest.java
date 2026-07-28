package com.paymentflow.merchant;

import com.paymentflow.common.dto.version.ApiVersions;
import com.paymentflow.merchant.domain.Merchant;
import com.paymentflow.merchant.repository.MerchantRepository;
import com.paymentflow.merchant.service.ApiVersionPinService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pinning a merchant to an API revision on their first call (M21.5, §4.10).
 *
 * <p>The behaviour that matters is not "the pin is written" — it is that it is written
 * <em>once</em> and never moved. A pin that could drift would silently migrate a merchant
 * onto a new contract, which is the exact failure date-based versioning exists to prevent,
 * and it would do so invisibly: their integration would simply start receiving a different
 * shape one day.
 *
 * <p>Runs against a real Postgres because the write, the constraint and the re-read inside a
 * fresh transaction are the parts worth checking, and none of them exist in a mock.
 */
@SpringBootTest
@Testcontainers
class ApiVersionPinIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:17-alpine"));

    @Autowired
    private MerchantRepository merchantRepository;
    @Autowired
    private ApiVersionPinService pinService;

    private Merchant newMerchant() {
        Merchant merchant = Merchant.onboard(UUID.randomUUID(), "Acme " + UUID.randomUUID(),
                "billing+" + UUID.randomUUID() + "@acme.test");
        return merchantRepository.save(merchant);
    }

    @Test
    void aNewMerchantHasNoPinUntilTheyCall() {
        // Null means "has not called yet", not "use the default" — the distinction the
        // migration deliberately encodes by having no column default. A default would have
        // pinned every historical merchant to whatever revision was current on migration
        // day, including ones that have never made a request.
        assertThat(newMerchant().getPinnedApiVersion()).isNull();
    }

    @Test
    void theFirstCallPinsTheMerchantToTheCurrentRevision() {
        Merchant merchant = newMerchant();

        String pinned = pinService.pinIfUnset(merchant);

        assertThat(pinned).isEqualTo(ApiVersions.CURRENT.toString());
        assertThat(merchantRepository.findById(merchant.getId()).orElseThrow().getPinnedApiVersion())
                .isEqualTo(ApiVersions.CURRENT.toString());
    }

    @Test
    void asubsequentCallDoesNotMoveThePin() {
        // The whole promise. Simulated by pinning to a revision that is *not* current and
        // confirming a later call leaves it alone — if the guard were absent this would
        // silently become the current revision, and the merchant's integration would change
        // shape underneath them.
        Merchant merchant = newMerchant();
        merchant.pinApiVersionIfUnset(ApiVersions.V2026_07_27.toString());
        merchantRepository.save(merchant);

        String pinned = pinService.pinIfUnset(merchantRepository.findById(merchant.getId()).orElseThrow());

        assertThat(pinned).isEqualTo(ApiVersions.V2026_07_27.toString());
        assertThat(merchantRepository.findById(merchant.getId()).orElseThrow().getPinnedApiVersion())
                .isEqualTo(ApiVersions.V2026_07_27.toString());
    }

    @Test
    void pinningIsIdempotentAcrossManyCalls() {
        // This runs on every authenticated request, so "does nothing after the first" is a
        // correctness property and a performance one.
        Merchant merchant = newMerchant();

        for (int i = 0; i < 5; i++) {
            pinService.pinIfUnset(merchantRepository.findById(merchant.getId()).orElseThrow());
        }

        assertThat(merchantRepository.findById(merchant.getId()).orElseThrow().getPinnedApiVersion())
                .isEqualTo(ApiVersions.CURRENT.toString());
    }

    @Test
    void theEntityRefusesToMoveAnExistingPin() {
        // The invariant lives on the entity rather than in the service, so there is no path
        // — including a future admin tool — that can overwrite a pin by accident.
        Merchant merchant = newMerchant();

        assertThat(merchant.pinApiVersionIfUnset("2026-07-27")).isTrue();
        assertThat(merchant.pinApiVersionIfUnset("2026-08-01")).isFalse();
        assertThat(merchant.getPinnedApiVersion()).isEqualTo("2026-07-27");
    }

    @Test
    void theDatabaseRefusesAPinThatIsNotADate() {
        // The gateway treats an unparseable pin as "not pinned" and falls forward, so a
        // malformed value would be silently ignored rather than loudly rejected. The
        // constraint makes it unstorable instead — the same reasoning as M20.5's positive
        // rate-limit checks.
        Merchant merchant = newMerchant();
        merchant.pinApiVersionIfUnset("not-a-date");

        assertThatThrownBy(() -> merchantRepository.saveAndFlush(merchant))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }
}
