package com.paymentflow.notification.service;

import com.paymentflow.notification.config.WebhookProperties;
import com.paymentflow.notification.crypto.WebhookSecretCipher;
import com.paymentflow.notification.domain.WebhookEndpoint;
import com.paymentflow.notification.domain.WebhookEvent;
import com.paymentflow.notification.domain.WebhookSubscription;
import com.paymentflow.notification.repository.WebhookEndpointRepository;
import com.paymentflow.notification.repository.WebhookSubscriptionRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * Adopts V1's {@code merchants.webhook_url} into a real {@link WebhookEndpoint} the first
 * time an event arrives for a merchant that has a legacy URL but no registered endpoint
 * (D135).
 *
 * <p><b>Why this runs in M18.6 rather than M18.9, where it was planned.</b> M18.6 is the
 * commit where fan-out replaces V1's single-URL delivery. From that commit onward, a
 * merchant who configured {@code webhook_url} before this milestone and has not yet
 * registered an endpoint would receive <em>nothing</em> — silently, with no error
 * anywhere, because "no subscribed endpoints" is a legitimate outcome indistinguishable
 * from "not subscribed". Deferring adoption by three sub-milestones would have meant
 * shipping a silent regression for every existing integration and then fixing it. The
 * decision (D135) is unchanged; only its position moved, and the reason it moved is that
 * the cutover is what creates the need for it.
 *
 * <p>Adoption is deliberately conditional on the merchant having <em>no</em> endpoints in
 * that mode at all. Once they have registered even one, their endpoint list is their own
 * declared intent and the legacy column must not silently add to it — a merchant who
 * registered a new endpoint and deleted nothing would otherwise find events going
 * somewhere they never asked for.
 */
@Service
public class LegacyEndpointAdopter {

    private static final Logger log = LoggerFactory.getLogger(LegacyEndpointAdopter.class);

    private final WebhookEndpointRepository endpointRepository;
    private final WebhookSubscriptionRepository subscriptionRepository;
    private final WebhookSecretCipher secretCipher;
    private final WebhookProperties properties;
    private final MeterRegistry meterRegistry;

    public LegacyEndpointAdopter(WebhookEndpointRepository endpointRepository,
                                 WebhookSubscriptionRepository subscriptionRepository,
                                 WebhookSecretCipher secretCipher, WebhookProperties properties,
                                 MeterRegistry meterRegistry) {
        this.endpointRepository = endpointRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.secretCipher = secretCipher;
        this.properties = properties;
        this.meterRegistry = meterRegistry;
    }

    /**
     * Creates the adopted endpoint if — and only if — this merchant/mode has none.
     * Runs inside the caller's transaction, immediately before fan-out, so the endpoint
     * it creates is visible to that fan-out and the first event after the cutover is
     * delivered rather than dropped.
     */
    public void adoptIfNeeded(WebhookEvent event, String legacyWebhookUrl, String contactEmail) {
        if (legacyWebhookUrl == null || legacyWebhookUrl.isBlank()) {
            return;
        }
        if (endpointRepository.existsByMerchantIdAndMode(event.getMerchantId(), event.getMode())) {
            return;
        }

        // The raw secret is generated, encrypted, and immediately forgotten — nobody is
        // waiting on a response to be shown it. The merchant must rotate to obtain a
        // usable one, which is the honest outcome: V1 had no signing secret at all, so
        // there is no pre-existing value to preserve, and inventing one they cannot see
        // would be worse than one they must explicitly ask for.
        String rawSecret = WebhookSecretGenerator.generate();
        try {
            WebhookEndpoint adopted = endpointRepository.save(WebhookEndpoint.adoptLegacy(
                    event.getMerchantId(), event.getMode(), legacyWebhookUrl.trim(), properties.apiVersion(),
                    secretCipher.encrypt(rawSecret), WebhookSecretGenerator.storedPrefixOf(rawSecret),
                    contactEmail));
            // Wildcard: V1 delivered every payment lifecycle event to the single URL, so
            // anything narrower would silently drop event types the merchant already
            // receives today.
            subscriptionRepository.save(
                    WebhookSubscription.of(adopted.getId(), WebhookSubscription.ALL_EVENT_TYPES));

            meterRegistry.counter("webhook_legacy_endpoints_adopted_total").increment();
            log.info("Adopted legacy webhook URL for merchant {} ({} mode) as endpoint {}",
                    event.getMerchantId(), event.getMode(), adopted.getId());
        } catch (DataIntegrityViolationException concurrentAdoption) {
            // Two events for the same merchant handled concurrently; the unique index on
            // (merchant_id, mode, url) settles it and the loser simply proceeds — the
            // endpoint it needed now exists either way.
            log.debug("Legacy endpoint for merchant {} was adopted concurrently", event.getMerchantId());
        }
    }
}
