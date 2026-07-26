package com.paymentflow.notification.service;

import com.paymentflow.common.exception.BadRequestException;
import com.paymentflow.common.exception.ConflictException;
import com.paymentflow.common.exception.ResourceNotFoundException;
import com.paymentflow.notification.config.WebhookProperties;
import com.paymentflow.notification.crypto.WebhookSecretCipher;
import com.paymentflow.notification.domain.WebhookEndpoint;
import com.paymentflow.notification.domain.WebhookEventType;
import com.paymentflow.notification.domain.WebhookSubscription;
import com.paymentflow.notification.repository.WebhookEndpointRepository;
import com.paymentflow.notification.repository.WebhookSubscriptionRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Clock;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Owns the endpoint lifecycle (M18.2, §4.5): registration, listing, update, deletion,
 * and secret rotation. Every method takes the caller's verified {@code merchantId} and
 * {@code mode} and passes both to a scoped repository lookup — never a bare
 * {@code findById} — so cross-merchant and cross-mode access resolve to empty and
 * surface as 404, never 403 (D102).
 *
 * <p>The raw {@code whsec_} secret is returned to the caller exactly once and never
 * persisted in the clear: {@link WebhookSecretCipher} encrypts it (AES-256-GCM) on the
 * way in. Encrypted rather than hashed, unlike API keys, because the platform must
 * <em>use</em> it as an HMAC key on every delivery — see D137.
 */
@Service
public class WebhookEndpointService {

    private static final String HTTPS = "https";
    private static final String HTTP = "http";

    private final WebhookEndpointRepository endpointRepository;
    private final WebhookSubscriptionRepository subscriptionRepository;
    private final WebhookProperties properties;
    private final WebhookSecretCipher secretCipher;
    private final Clock clock = Clock.systemUTC();

    public WebhookEndpointService(WebhookEndpointRepository endpointRepository,
                                  WebhookSubscriptionRepository subscriptionRepository,
                                  WebhookProperties properties, WebhookSecretCipher secretCipher) {
        this.endpointRepository = endpointRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.properties = properties;
        this.secretCipher = secretCipher;
    }

    /** An endpoint together with the raw secret that will never be readable again. */
    public record RegisteredEndpoint(WebhookEndpoint endpoint, List<WebhookSubscription> subscriptions,
                                     String rawSecret) {
    }

    @Transactional
    public RegisteredEndpoint register(UUID merchantId, String mode, String url, String description,
                                       Collection<String> enabledEvents, String contactEmail) {
        String normalizedUrl = validateUrl(url);
        Set<String> eventTypes = validateEventTypes(enabledEvents);

        if (endpointRepository.findByMerchantIdAndModeOrderByCreatedAtAsc(merchantId, mode).size()
                >= properties.maxEndpointsPerMode()) {
            throw new ConflictException("This merchant already has the maximum of "
                    + properties.maxEndpointsPerMode() + " webhook endpoints in " + mode + " mode.");
        }
        // Pre-checked for a clean 409 rather than letting the unique index surface as a
        // raw constraint violation; the index below is still the authority under a race.
        if (endpointRepository.findByMerchantIdAndModeAndUrl(merchantId, mode, normalizedUrl).isPresent()) {
            throw new ConflictException("A webhook endpoint for this URL is already registered in " + mode + " mode.");
        }

        String rawSecret = WebhookSecretGenerator.generate();
        WebhookEndpoint endpoint = WebhookEndpoint.register(merchantId, mode, normalizedUrl, description,
                properties.apiVersion(), secretCipher.encrypt(rawSecret),
                WebhookSecretGenerator.storedPrefixOf(rawSecret), contactEmail);

        WebhookEndpoint saved;
        try {
            saved = endpointRepository.saveAndFlush(endpoint);
        } catch (DataIntegrityViolationException concurrentRegistration) {
            throw new ConflictException("A webhook endpoint for this URL is already registered in " + mode + " mode.");
        }

        List<WebhookSubscription> subscriptions = replaceSubscriptions(saved.getId(), eventTypes);
        return new RegisteredEndpoint(saved, subscriptions, rawSecret);
    }

    @Transactional(readOnly = true)
    public List<WebhookEndpoint> list(UUID merchantId, String mode) {
        return endpointRepository.findByMerchantIdAndModeOrderByCreatedAtAsc(merchantId, mode);
    }

    @Transactional(readOnly = true)
    public WebhookEndpoint get(UUID merchantId, String mode, UUID endpointId) {
        return requireEndpoint(merchantId, mode, endpointId);
    }

    @Transactional(readOnly = true)
    public List<WebhookSubscription> subscriptionsOf(UUID endpointId) {
        return subscriptionRepository.findByEndpointId(endpointId);
    }

    /**
     * Subscriptions for many endpoints in one query, keyed by endpoint id — the list
     * view's N+1 avoidance. Endpoints with no subscriptions are absent from the map, so
     * callers read through {@code getOrDefault(id, List.of())}.
     */
    @Transactional(readOnly = true)
    public Map<UUID, List<WebhookSubscription>> subscriptionsOf(Collection<UUID> endpointIds) {
        if (endpointIds.isEmpty()) {
            return Map.of();
        }
        return subscriptionRepository.findByEndpointIdIn(endpointIds).stream()
                .collect(Collectors.groupingBy(WebhookSubscription::getEndpointId));
    }

    @Transactional
    public WebhookEndpoint update(UUID merchantId, String mode, UUID endpointId, String description, Boolean enabled,
                                  Collection<String> enabledEvents) {
        WebhookEndpoint endpoint = requireEndpoint(merchantId, mode, endpointId);

        if (description != null) {
            endpoint.updateDescription(description);
        }
        if (enabled != null) {
            // enable() also clears an auto-disable and resets the failure streak, which is
            // exactly what a merchant means by "I've fixed it, turn it back on".
            if (enabled) {
                endpoint.enable();
            } else {
                endpoint.disable();
            }
        }
        if (enabledEvents != null) {
            replaceSubscriptions(endpointId, validateEventTypes(enabledEvents));
        }
        return endpointRepository.save(endpoint);
    }

    @Transactional
    public void delete(UUID merchantId, String mode, UUID endpointId) {
        // Subscriptions go with it via the FK's ON DELETE CASCADE; delivery attempts are
        // cascaded from webhook_deliveries, not from here, so a deleted endpoint does not
        // erase the history of what was already sent to it.
        endpointRepository.delete(requireEndpoint(merchantId, mode, endpointId));
    }

    /**
     * Issues a new signing secret and keeps the superseded one verifying for
     * {@code secretRotationGracePeriod} (§4.5's dual-secret window) — so an endpoint can
     * roll without dropping the deliveries already signed with the old secret and still
     * in flight through the retry schedule.
     */
    @Transactional
    public RegisteredEndpoint rotateSecret(UUID merchantId, String mode, UUID endpointId) {
        WebhookEndpoint endpoint = requireEndpoint(merchantId, mode, endpointId);
        String rawSecret = WebhookSecretGenerator.generate();
        endpoint.rotateSecret(secretCipher.encrypt(rawSecret),
                WebhookSecretGenerator.storedPrefixOf(rawSecret), properties.secretRotationGracePeriod(),
                clock.instant());
        return new RegisteredEndpoint(endpointRepository.save(endpoint),
                subscriptionRepository.findByEndpointId(endpointId), rawSecret);
    }

    private WebhookEndpoint requireEndpoint(UUID merchantId, String mode, UUID endpointId) {
        return endpointRepository.findByIdAndMerchantIdAndMode(endpointId, merchantId, mode)
                .orElseThrow(() -> ResourceNotFoundException.of("WebhookEndpoint", endpointId));
    }

    /** Wholesale replacement rather than a diff: the set is tiny and a replacement has no partial-failure mode. */
    private List<WebhookSubscription> replaceSubscriptions(UUID endpointId, Set<String> eventTypes) {
        subscriptionRepository.deleteByEndpointId(endpointId);
        subscriptionRepository.flush();
        return subscriptionRepository.saveAll(eventTypes.stream()
                .map(eventType -> WebhookSubscription.of(endpointId, eventType))
                .toList());
    }

    /**
     * HTTPS-only in every environment that has {@code requireHttps} on (§4.5). The
     * property exists because every local and test sink in this repository is
     * {@code http://localhost:…}; relaxing it in the test/local profiles is a documented
     * exception rather than a silent one.
     */
    private String validateUrl(String url) {
        URI uri;
        try {
            uri = new URI(url.trim());
        } catch (URISyntaxException e) {
            throw new BadRequestException("url is not a valid URL.");
        }
        if (uri.getScheme() == null || uri.getHost() == null) {
            throw new BadRequestException("url must be absolute, including a scheme and a host.");
        }
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        if (properties.requireHttps() && !HTTPS.equals(scheme)) {
            throw new BadRequestException("url must use https.");
        }
        if (!HTTPS.equals(scheme) && !HTTP.equals(scheme)) {
            throw new BadRequestException("url must use https.");
        }
        if (uri.getUserInfo() != null) {
            // Credentials in a webhook URL would be logged verbatim in every delivery-attempt
            // row; refuse them rather than redact them after the fact.
            throw new BadRequestException("url must not contain embedded credentials.");
        }
        return uri.toString();
    }

    /**
     * Every subscription must be {@code "*"} or a name from the documented vocabulary.
     * Accepting an arbitrary string would let a merchant subscribe to a typo and then
     * spend a day debugging why the events "aren't arriving" — the single most likely
     * self-inflicted integration failure this API has.
     */
    private static Set<String> validateEventTypes(Collection<String> enabledEvents) {
        Set<String> normalized = enabledEvents.stream()
                .map(String::trim)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        List<String> unknown = normalized.stream()
                .filter(eventType -> !WebhookSubscription.ALL_EVENT_TYPES.equals(eventType))
                .filter(eventType -> WebhookEventType.fromCanonical(eventType).isEmpty())
                .toList();
        if (!unknown.isEmpty()) {
            throw new BadRequestException("Unknown event type(s): " + String.join(", ", unknown)
                    + ". Supported: *, " + WebhookEventType.documentedVocabulary() + ".");
        }
        // A wildcard alongside explicit types is redundant, not an error — the wildcard
        // already matches everything, so the explicit entries are collapsed into it and
        // the stored set stays honest about what the endpoint actually receives.
        if (normalized.contains(WebhookSubscription.ALL_EVENT_TYPES)) {
            return Set.of(WebhookSubscription.ALL_EVENT_TYPES);
        }
        return normalized;
    }
}
