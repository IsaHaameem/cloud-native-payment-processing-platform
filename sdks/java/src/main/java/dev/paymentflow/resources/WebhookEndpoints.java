package dev.paymentflow.resources;

import dev.paymentflow.RequestOptions;
import dev.paymentflow.internal.Transport;
import dev.paymentflow.model.Operations;
import dev.paymentflow.model.WebhookEndpointCreatedResponse;
import dev.paymentflow.model.WebhookEndpointResponse;

import java.util.List;
import java.util.Map;

/**
 * {@code client.webhookEndpoints()} — where events are delivered, and their signing secrets.
 *
 * <p>{@link #create} and {@link #rotateSecret} return a {@link WebhookEndpointCreatedResponse}
 * that carries the {@code signingSecret}; {@link #retrieve} and {@link #list} return a
 * {@link WebhookEndpointResponse} that does not. That difference is the platform's — the secret
 * is sent exactly once — and the return types make "I'll fetch it again later" not compile.
 */
public final class WebhookEndpoints extends Resource {

    public WebhookEndpoints(Transport transport) {
        super(transport);
    }

    /** What {@link #create} accepts. */
    public static final class CreateParams {

        String url;
        List<String> enabledEvents;
        String description;
        Map<String, String> metadata;

        /** Where to deliver events. Must be reachable over HTTPS from the public internet. Required. */
        public CreateParams url(String url) {
            this.url = url;
            return this;
        }

        /** The event types to send here. At least one; {@code ["*"]} for everything. Required. */
        public CreateParams enabledEvents(List<String> events) {
            this.enabledEvents = events;
            return this;
        }

        public CreateParams description(String description) {
            this.description = description;
            return this;
        }

        public CreateParams metadata(Map<String, String> metadata) {
            this.metadata = metadata;
            return this;
        }
    }

    public static CreateParams params() {
        return new CreateParams();
    }

    /** What {@link #update} accepts. Every field optional: send only what changes. */
    public static final class UpdateParams {

        Boolean enabled;
        List<String> enabledEvents;
        String description;
        Map<String, String> metadata;

        public UpdateParams enabled(boolean enabled) {
            this.enabled = enabled;
            return this;
        }

        public UpdateParams enabledEvents(List<String> events) {
            this.enabledEvents = events;
            return this;
        }

        public UpdateParams description(String description) {
            this.description = description;
            return this;
        }

        public UpdateParams metadata(Map<String, String> metadata) {
            this.metadata = metadata;
            return this;
        }
    }

    public static UpdateParams updateParams() {
        return new UpdateParams();
    }

    /** Creates an endpoint and returns it <b>with its signing secret</b>. Store the secret now. */
    public WebhookEndpointCreatedResponse create(CreateParams params, RequestOptions options) {
        Object body = body()
                .put("url", params.url)
                .put("enabledEvents", params.enabledEvents)
                .put("description", params.description)
                .put("metadata", params.metadata)
                .build();
        return send(Operations.CREATE_WEBHOOK_ENDPOINT, null, null, body, opts(options),
                WebhookEndpointCreatedResponse.class);
    }

    public WebhookEndpointCreatedResponse create(CreateParams params) {
        return create(params, null);
    }

    /** Retrieves one endpoint. Never includes the signing secret. */
    public WebhookEndpointResponse retrieve(String id, RequestOptions options) {
        return send(Operations.GET_WEBHOOK_ENDPOINT, Map.of("id", id), null, null, opts(options),
                WebhookEndpointResponse.class);
    }

    public WebhookEndpointResponse retrieve(String id) {
        return retrieve(id, null);
    }

    /** Lists your endpoints. A plain list — this endpoint is not paginated on the wire. */
    public List<WebhookEndpointResponse> list(RequestOptions options) {
        return sendList(Operations.LIST_WEBHOOK_ENDPOINTS, null, null, opts(options), WebhookEndpointResponse.class);
    }

    public List<WebhookEndpointResponse> list() {
        return list(null);
    }

    /** Updates an endpoint. */
    public WebhookEndpointResponse update(String id, UpdateParams params, RequestOptions options) {
        UpdateParams p = params == null ? new UpdateParams() : params;
        Object body = body()
                .put("enabled", p.enabled)
                .put("enabledEvents", p.enabledEvents)
                .put("description", p.description)
                .put("metadata", p.metadata)
                .build();
        return send(Operations.UPDATE_WEBHOOK_ENDPOINT, Map.of("id", id), null, body, opts(options),
                WebhookEndpointResponse.class);
    }

    /** Deletes an endpoint. The API returns 204. */
    public void delete(String id, RequestOptions options) {
        sendVoid(Operations.DELETE_WEBHOOK_ENDPOINT, Map.of("id", id), null, opts(options));
    }

    public void delete(String id) {
        delete(id, null);
    }

    /** Issues a new signing secret and returns it. As with {@link #create}, sent only once. */
    public WebhookEndpointCreatedResponse rotateSecret(String id, RequestOptions options) {
        return send(Operations.ROTATE_WEBHOOK_ENDPOINT_SECRET, Map.of("id", id), null, null, opts(options),
                WebhookEndpointCreatedResponse.class);
    }

    public WebhookEndpointCreatedResponse rotateSecret(String id) {
        return rotateSecret(id, null);
    }
}
