"""``client.webhook_endpoints`` and ``client.webhook_deliveries`` — configuring where events go,
and seeing what happened when they went (M22.6).

The one thing to know about ``create``
--------------------------------------
It returns the signing secret, and that is the only time the platform will ever send it.
``retrieve`` and ``list`` do not include it. ``rotate_secret`` issues a *new* one rather than
showing you this one again.

Deliveries are offset-paginated rather than cursor-paginated. That is D139's deliberate
exception, not an oversight here: ``/v1/webhook_deliveries`` and ``/v1/test/decisions`` were
left on the older ``PageResponse`` envelope, and the SDK reports what the endpoint returns.
"""

from __future__ import annotations

from typing import Mapping, Optional, Sequence

from .._generated.models import (
    WebhookDeliveryResponse,
    WebhookEndpointCreatedResponse,
    WebhookEndpointResponse,
)
from .._generated.operations import OPERATIONS
from .._pagination import OffsetPage
from .._transport import RequestOptions
from ._base import Resource, body_of, query_of

__all__ = ["WebhookDeliveries", "WebhookEndpoints"]


class WebhookEndpoints(Resource):
    def create(
        self,
        *,
        url: str,
        enabled_events: Sequence[str],
        description: Optional[str] = None,
        metadata: Optional[Mapping[str, str]] = None,
        options: Optional[RequestOptions] = None,
    ) -> WebhookEndpointCreatedResponse:
        """Creates an endpoint and returns it **with its signing secret**.

        Store the secret now. It is not retrievable afterwards.
        """
        body = body_of(
            url=url,
            enabledEvents=list(enabled_events),
            description=description,
            metadata=metadata,
        )
        result: WebhookEndpointCreatedResponse = self._send(
            OPERATIONS["createWebhookEndpoint"], body=body, options=options
        )
        return result

    def retrieve(self, endpoint_id: str, *, options: Optional[RequestOptions] = None) -> WebhookEndpointResponse:
        """Retrieves one endpoint. Never includes the signing secret."""
        result: WebhookEndpointResponse = self._send(
            OPERATIONS["getWebhookEndpoint"], path={"id": endpoint_id}, options=options
        )
        return result

    def list(self, *, options: Optional[RequestOptions] = None) -> Sequence[WebhookEndpointResponse]:
        """Lists your endpoints.

        Returns a plain list, because the endpoint does: this list is not paginated on the
        wire, and wrapping it in a page object would invent a ``has_more`` that no response
        carries. The set is capped at 16 per mode.
        """
        result: Sequence[WebhookEndpointResponse] = self._send(
            OPERATIONS["listWebhookEndpoints"], options=options
        )
        return result

    def update(
        self,
        endpoint_id: str,
        *,
        enabled: Optional[bool] = None,
        enabled_events: Optional[Sequence[str]] = None,
        description: Optional[str] = None,
        metadata: Optional[Mapping[str, str]] = None,
        options: Optional[RequestOptions] = None,
    ) -> WebhookEndpointResponse:
        """Updates an endpoint. Send only what changes."""
        body = body_of(
            enabled=enabled,
            enabledEvents=None if enabled_events is None else list(enabled_events),
            description=description,
            metadata=metadata,
        )
        result: WebhookEndpointResponse = self._send(
            OPERATIONS["updateWebhookEndpoint"], path={"id": endpoint_id}, body=body, options=options
        )
        return result

    def delete(self, endpoint_id: str, *, options: Optional[RequestOptions] = None) -> None:
        """Deletes an endpoint. Returns nothing: the API returns 204.

        Spelled ``delete`` rather than the Node SDK's ``del``, which is a Python keyword. This
        is the one place the two SDKs' method names differ, and the difference is forced by the
        languages rather than chosen.
        """
        self._send(OPERATIONS["deleteWebhookEndpoint"], path={"id": endpoint_id}, options=options)

    def rotate_secret(
        self, endpoint_id: str, *, options: Optional[RequestOptions] = None
    ) -> WebhookEndpointCreatedResponse:
        """Issues a new signing secret for an endpoint and returns it.

        As with ``create``, this is the only time the new secret is sent.
        """
        result: WebhookEndpointCreatedResponse = self._send(
            OPERATIONS["rotateWebhookEndpointSecret"], path={"id": endpoint_id}, options=options
        )
        return result


class WebhookDeliveries(Resource):
    def retrieve(self, delivery_id: str, *, options: Optional[RequestOptions] = None) -> WebhookDeliveryResponse:
        """Retrieves one delivery, including its attempts."""
        result: WebhookDeliveryResponse = self._send(
            OPERATIONS["getWebhookDelivery"], path={"id": delivery_id}, options=options
        )
        return result

    def list(
        self,
        *,
        page: Optional[int] = None,
        size: Optional[int] = None,
        sort: Optional[Sequence[str]] = None,
        options: Optional[RequestOptions] = None,
    ) -> OffsetPage[WebhookDeliveryResponse]:
        """Lists deliveries. The result paginates transparently.

        ``sort`` takes instructions such as ``"createdAt,desc"``.
        """
        query = query_of(page=page, size=size, sort=None if sort is None else list(sort))
        return self._list_offset(OPERATIONS["listWebhookDeliveries"], query, options)

    def replay(self, delivery_id: str, *, options: Optional[RequestOptions] = None) -> WebhookDeliveryResponse:
        """Re-sends a delivery. Returns the new delivery, not the original."""
        result: WebhookDeliveryResponse = self._send(
            OPERATIONS["replayWebhookDelivery"], path={"id": delivery_id}, options=options
        )
        return result
