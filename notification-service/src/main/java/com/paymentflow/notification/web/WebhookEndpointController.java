package com.paymentflow.notification.web;

import com.paymentflow.common.dto.error.ApiError;
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
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
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

    /**
     * Declared with its description in {@code OpenApiConfig}. Set per operation rather
     * than as a class-level {@code @Tag}, which springdoc adds to every operation instead
     * of treating as an overridable default (M21.1); the platform's controllers are
     * uniform on this so the behaviour is never relied on by accident.
     */
    static final String WEBHOOK_ENDPOINTS_TAG = "Webhook endpoints";

    /** Reused across four operations; an annotation attribute must be a constant. */
    private static final String ENDPOINT_NOT_FOUND_DESCRIPTION = """
            No such endpoint. Also returned when it exists but belongs to another merchant, \
            or to the other mode — never `403`, which would confirm it exists.""";

    private final WebhookEndpointService webhookEndpointService;
    private final WebhookEndpointMapper mapper;

    public WebhookEndpointController(WebhookEndpointService webhookEndpointService, WebhookEndpointMapper mapper) {
        this.webhookEndpointService = webhookEndpointService;
        this.mapper = mapper;
    }

    /** The one response that carries a raw {@code whsec_} secret — it is unrecoverable afterwards. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(tags = WEBHOOK_ENDPOINTS_TAG, operationId = "createWebhookEndpoint",
            summary = "Register a webhook endpoint",
            description = """
                    Registers a URL to receive events, and returns the signing secret.

                    **The signing secret is shown exactly once, here.** It cannot be \
                    retrieved afterwards — only a hash is kept — so store it before you do \
                    anything else. Every delivery to this endpoint is signed with it, and \
                    verifying that signature is what distinguishes a genuine delivery from \
                    anyone who has learned your URL.

                    The URL must be reachable over HTTPS from the public internet. Private, \
                    link-local and cloud-metadata addresses are refused at registration and \
                    re-checked at delivery time, so a hostname cannot be repointed at one \
                    later.""")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "The endpoint is registered. This "
                    + "response carries the signing secret; it is not shown again."),
            @ApiResponse(responseCode = "400", description = """
                    The endpoint was refused: a URL that is not HTTPS, resolves to a private \
                    or metadata address, or an empty `enabledEvents`.""",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(ref = ApiError.SCHEMA_REF))),
            @ApiResponse(responseCode = "409", description = "You already have an endpoint "
                    + "registered at this URL in this mode.",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(ref = ApiError.SCHEMA_REF)))})
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
    @Operation(tags = WEBHOOK_ENDPOINTS_TAG, operationId = "listWebhookEndpoints",
            summary = "List your webhook endpoints",
            description = """
                    Returns every endpoint registered for your merchant in the mode of the \
                    key you called with, including any the platform has auto-disabled. Not \
                    paginated: the number of endpoints per merchant and mode is capped, so \
                    this is always one short list.""")
    @ApiResponse(responseCode = "200", description = "Your webhook endpoints.")
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
    @Operation(tags = WEBHOOK_ENDPOINTS_TAG, operationId = "getWebhookEndpoint",
            summary = "Retrieve a webhook endpoint",
            description = """
                    Returns one endpoint, including its consecutive failure count and — if it \
                    has been disabled — whether you disabled it or the platform did.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The endpoint."),
            @ApiResponse(responseCode = "404", description = ENDPOINT_NOT_FOUND_DESCRIPTION,
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(ref = ApiError.SCHEMA_REF)))})
    public WebhookEndpointResponse get(@Parameter(description = "The endpoint to retrieve.")
                                       @PathVariable UUID id) {
        MerchantContext context = requireContext();
        WebhookEndpoint endpoint = webhookEndpointService.get(context.merchantId(), context.mode(), id);
        return mapper.toResponse(endpoint, webhookEndpointService.subscriptionsOf(endpoint.getId()));
    }

    @PatchMapping("/{id}")
    @Operation(tags = WEBHOOK_ENDPOINTS_TAG, operationId = "updateWebhookEndpoint",
            summary = "Update a webhook endpoint",
            description = """
                    Changes an endpoint's description, subscriptions, metadata, or enabled \
                    state. A partial update: omitted fields are left alone, so re-enabling an \
                    endpoint does not require re-sending its subscription list and risking \
                    truncating it.

                    The URL cannot be changed. It is half of the endpoint's identity, and \
                    repointing one would keep its delivery history attached to a destination \
                    that never received any of it — register a new endpoint and delete this \
                    one.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The updated endpoint."),
            @ApiResponse(responseCode = "400", description = "The update failed validation — "
                    + "an `enabledEvents` naming an unknown event type, for instance.",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(ref = ApiError.SCHEMA_REF))),
            @ApiResponse(responseCode = "404", description = ENDPOINT_NOT_FOUND_DESCRIPTION,
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(ref = ApiError.SCHEMA_REF)))})
    public WebhookEndpointResponse update(@Parameter(description = "The endpoint to update.")
                                          @PathVariable UUID id,
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
    @Operation(tags = WEBHOOK_ENDPOINTS_TAG, operationId = "deleteWebhookEndpoint",
            summary = "Delete a webhook endpoint",
            description = """
                    Removes an endpoint permanently. Nothing further is delivered to it, and \
                    its signing secret stops being valid. To stop deliveries temporarily, \
                    set `enabled` to false instead — that is reversible, and this is not.""")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "The endpoint is gone."),
            @ApiResponse(responseCode = "404", description = ENDPOINT_NOT_FOUND_DESCRIPTION,
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(ref = ApiError.SCHEMA_REF)))})
    public ResponseEntity<Void> delete(@Parameter(description = "The endpoint to delete.")
                                       @PathVariable UUID id) {
        MerchantContext context = requireContext();
        webhookEndpointService.delete(context.merchantId(), context.mode(), id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/rotate_secret")
    @Operation(tags = WEBHOOK_ENDPOINTS_TAG, operationId = "rotateWebhookEndpointSecret",
            summary = "Rotate an endpoint's signing secret",
            description = """
                    Issues a new signing secret for the endpoint and returns it — the second \
                    and only other place a raw secret is ever shown.

                    **Both secrets verify during a grace window**, so you can deploy the new \
                    one without dropping in-flight deliveries: rotate, deploy, and let the \
                    old secret lapse. Verifying against either during that window is the \
                    intended behaviour, not a weakness.

                    Rotate when a secret may have been exposed, or when you have lost the \
                    one you were shown at registration — it cannot be recovered, only \
                    replaced.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The endpoint, with its new "
                    + "signing secret. Not shown again."),
            @ApiResponse(responseCode = "404", description = ENDPOINT_NOT_FOUND_DESCRIPTION,
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(ref = ApiError.SCHEMA_REF)))})
    public WebhookEndpointCreatedResponse rotateSecret(
            @Parameter(description = "The endpoint whose secret to rotate.")
            @PathVariable UUID id) {
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
