package com.paymentflow.notification.web;

import com.paymentflow.common.exception.UnauthorizedException;
import com.paymentflow.common.security.MerchantContext;
import com.paymentflow.common.security.MerchantContextHolder;
import com.paymentflow.notification.domain.WebhookEndpoint;
import com.paymentflow.notification.domain.WebhookSubscription;
import com.paymentflow.notification.dto.CreateWebhookEndpointRequest;
import com.paymentflow.notification.dto.UpdateWebhookEndpointRequest;
import com.paymentflow.notification.dto.WebhookEndpointCreatedResponse;
import com.paymentflow.notification.dto.WebhookEndpointResponse;
import com.paymentflow.notification.mapper.WebhookEndpointMapper;
import com.paymentflow.notification.service.WebhookEndpointService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The public, key-authenticated webhook-endpoint management API (M18.2, §4.5) —
 * notification-service's first HTTP surface of any kind. Reachable only through the
 * gateway's API-key path, which asserts the same HMAC-signed internal context every
 * other {@code /v1} route uses (D100), and gated on the {@code webhooks:manage} scope at
 * the gateway.
 *
 * <p>{@code merchantId} and {@code mode} come from the verified {@link MerchantContext}
 * and never from a path, query, or body field — the same §7 barrier ① that
 * {@code SimulationController} and {@code DecisionLogController} enforce, and the reason
 * this API has no IDOR surface to test for rather than a tested-and-found-safe one.
 *
 * <p>The {@code /api/v1} dashboard mirror named in §5/M18 task 2 is deliberately absent:
 * it is deferred to M23 along with the portal that would call it (D133).
 */
@RestController
@RequestMapping("/v1/webhook_endpoints")
public class WebhookEndpointController {

    private final WebhookEndpointService webhookEndpointService;
    private final WebhookEndpointMapper mapper;

    public WebhookEndpointController(WebhookEndpointService webhookEndpointService, WebhookEndpointMapper mapper) {
        this.webhookEndpointService = webhookEndpointService;
        this.mapper = mapper;
    }

    /** The one response that carries a raw {@code whsec_} secret — it is unrecoverable afterwards. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public WebhookEndpointCreatedResponse create(@Valid @RequestBody CreateWebhookEndpointRequest request) {
        MerchantContext context = requireContext();
        WebhookEndpointService.RegisteredEndpoint registered = webhookEndpointService.register(
                context.merchantId(), context.mode(), request.url(), request.description(), request.enabledEvents(),
                // Carried on the verified context (D118) so notification-service never has
                // to call merchant-service to learn where to send an auto-disable notice.
                context.contactEmail(),
                mapper.writeMetadata(request.metadata()));
        return new WebhookEndpointCreatedResponse(
                mapper.toResponse(registered.endpoint(), registered.subscriptions()), registered.rawSecret());
    }

    @GetMapping
    public List<WebhookEndpointResponse> list() {
        MerchantContext context = requireContext();
        List<WebhookEndpoint> endpoints = webhookEndpointService.list(context.merchantId(), context.mode());
        Map<UUID, List<WebhookSubscription>> subscriptions =
                webhookEndpointService.subscriptionsOf(endpoints.stream().map(WebhookEndpoint::getId).toList());
        return endpoints.stream()
                .map(endpoint -> mapper.toResponse(endpoint, subscriptions.getOrDefault(endpoint.getId(), List.of())))
                .toList();
    }

    @GetMapping("/{id}")
    public WebhookEndpointResponse get(@PathVariable UUID id) {
        MerchantContext context = requireContext();
        WebhookEndpoint endpoint = webhookEndpointService.get(context.merchantId(), context.mode(), id);
        return mapper.toResponse(endpoint, webhookEndpointService.subscriptionsOf(endpoint.getId()));
    }

    @PatchMapping("/{id}")
    public WebhookEndpointResponse update(@PathVariable UUID id,
                                          @Valid @RequestBody UpdateWebhookEndpointRequest request) {
        MerchantContext context = requireContext();
        WebhookEndpoint endpoint = webhookEndpointService.update(context.merchantId(), context.mode(), id,
                request.description(), request.enabled(), request.enabledEvents(),
                // The map and the "was it sent at all?" flag are both needed: an omitted
                // `metadata` leaves the stored value alone, while `"metadata": {}` clears
                // it, and writeMetadata collapses both to null.
                mapper.writeMetadata(request.metadata()), request.metadata() != null);
        return mapper.toResponse(endpoint, webhookEndpointService.subscriptionsOf(endpoint.getId()));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        MerchantContext context = requireContext();
        webhookEndpointService.delete(context.merchantId(), context.mode(), id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/rotate_secret")
    public WebhookEndpointCreatedResponse rotateSecret(@PathVariable UUID id) {
        MerchantContext context = requireContext();
        WebhookEndpointService.RegisteredEndpoint rotated =
                webhookEndpointService.rotateSecret(context.merchantId(), context.mode(), id);
        return new WebhookEndpointCreatedResponse(
                mapper.toResponse(rotated.endpoint(), rotated.subscriptions()), rotated.rawSecret());
    }

    private static MerchantContext requireContext() {
        return MerchantContextHolder.get()
                .orElseThrow(() -> new UnauthorizedException("A verified internal context is required."));
    }
}
