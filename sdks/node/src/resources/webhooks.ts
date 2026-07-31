/**
 * `client.webhookEndpoints` and `client.webhookDeliveries` — configuring where events go, and
 * seeing what happened when they went (M22.3).
 *
 * ## The one thing to know about `create`
 *
 * It returns the signing secret, and that is the only time the platform will ever send it.
 * `retrieve` and `list` do not include it. That is deliberate on the platform's side and is
 * reflected here by the return type: `create` and `rotateSecret` resolve to a value that has a
 * `signingSecret`, and nothing else does — so "I'll fetch it again later" does not type-check
 * rather than failing at runtime in whatever code was going to store it.
 *
 * Deliveries are offset-paginated rather than cursor-paginated. That is D139's deliberate
 * exception, not an oversight here: `/v1/webhook_deliveries` and `/v1/test/decisions` were
 * left on the older `PageResponse` envelope, and the SDK reports what the endpoint returns.
 */

import { OPERATIONS } from '../generated/operations.js';
import type {
  WebhookDeliveryResponse,
  WebhookEndpointCreatedResponse,
  WebhookEndpointResponse,
} from '../generated/models.js';
import type { RequestOptions } from '../transport.js';
import type { OffsetPage } from '../pagination.js';
import { Resource } from './base.js';

/** What `webhookEndpoints.create` accepts. */
export interface WebhookEndpointCreateParams {
  /** Where to deliver events. Must be HTTPS. */
  readonly url: string;
  /** Which event types this endpoint should receive. */
  readonly enabledEvents: readonly string[];
  /** A description for your own records. */
  readonly description?: string;
  readonly metadata?: Readonly<Record<string, string>>;
}

/** What `webhookEndpoints.update` accepts. Every field optional: send only what changes. */
export interface WebhookEndpointUpdateParams {
  readonly enabled?: boolean;
  readonly enabledEvents?: readonly string[];
  readonly description?: string;
  readonly metadata?: Readonly<Record<string, string>>;
}

export class WebhookEndpoints extends Resource {
  /**
   * Creates an endpoint and returns it **with its signing secret**.
   *
   * Store the secret now. It is not retrievable afterwards — `rotateSecret` issues a new one
   * rather than showing you this one again.
   */
  create(params: WebhookEndpointCreateParams, options?: RequestOptions): Promise<WebhookEndpointCreatedResponse> {
    return this.send<WebhookEndpointCreatedResponse>(OPERATIONS.createWebhookEndpoint, { body: params, options });
  }

  /** Retrieves one endpoint. Never includes the signing secret. */
  retrieve(id: string, options?: RequestOptions): Promise<WebhookEndpointResponse> {
    return this.send<WebhookEndpointResponse>(OPERATIONS.getWebhookEndpoint, { path: { id }, options });
  }

  /**
   * Lists your endpoints.
   *
   * Returns a plain array, because the endpoint does: this list is not paginated on the wire,
   * and wrapping it in a page object would invent a `hasMore` that no response carries.
   */
  list(options?: RequestOptions): Promise<WebhookEndpointResponse[]> {
    return this.send<WebhookEndpointResponse[]>(OPERATIONS.listWebhookEndpoints, { options });
  }

  /** Updates an endpoint. */
  update(
    id: string,
    params: WebhookEndpointUpdateParams,
    options?: RequestOptions,
  ): Promise<WebhookEndpointResponse> {
    return this.send<WebhookEndpointResponse>(OPERATIONS.updateWebhookEndpoint, {
      path: { id },
      body: params,
      options,
    });
  }

  /** Deletes an endpoint. Resolves with nothing: the API returns 204. */
  del(id: string, options?: RequestOptions): Promise<void> {
    return this.send<void>(OPERATIONS.deleteWebhookEndpoint, { path: { id }, options });
  }

  /**
   * Issues a new signing secret for an endpoint and returns it.
   *
   * As with `create`, this is the only time the new secret is sent.
   */
  rotateSecret(id: string, options?: RequestOptions): Promise<WebhookEndpointCreatedResponse> {
    return this.send<WebhookEndpointCreatedResponse>(OPERATIONS.rotateWebhookEndpointSecret, {
      path: { id },
      options,
    });
  }
}

/** What `webhookDeliveries.list` accepts. Offset pagination, per D139. */
export type WebhookDeliveryListParams = {
  /** Zero-based page index. */
  readonly page?: number;
  /** How many deliveries per page. */
  readonly size?: number;
  /** Sort instructions, such as `createdAt,desc`. */
  readonly sort?: readonly string[];
};

export class WebhookDeliveries extends Resource {
  /** Retrieves one delivery, including its attempts. */
  retrieve(id: string, options?: RequestOptions): Promise<WebhookDeliveryResponse> {
    return this.send<WebhookDeliveryResponse>(OPERATIONS.getWebhookDelivery, { path: { id }, options });
  }

  /** Lists deliveries. The result paginates transparently. */
  list(
    params: WebhookDeliveryListParams = {},
    options?: RequestOptions,
  ): Promise<OffsetPage<WebhookDeliveryResponse>> {
    return this.listOffset<WebhookDeliveryResponse>(OPERATIONS.listWebhookDeliveries, params, options);
  }

  /** Re-sends a delivery. Returns the new delivery, not the original. */
  replay(id: string, options?: RequestOptions): Promise<WebhookDeliveryResponse> {
    return this.send<WebhookDeliveryResponse>(OPERATIONS.replayWebhookDelivery, { path: { id }, options });
  }
}
