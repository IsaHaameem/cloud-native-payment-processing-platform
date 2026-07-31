"""Shared fixtures for the Python SDK's behavioural suites (M22.5).

Every test here drives a real client through an ``httpx`` transport stub, which is the reason
§7.1 makes the HTTP client injectable. Asserting on the request the SDK *would have sent* is
the only way to test the properties that matter — that a retry reuses its idempotency key, that
a ``POST`` the platform does not deduplicate is never replayed — because those are properties
of requests a healthy server never sees twice.

``httpx.MockTransport`` is the library's own hook for this, so nothing here monkey-patches the
client or reaches past its public surface.
"""

from __future__ import annotations

import json
from typing import Any, Callable, Dict, List, Optional, Sequence

import httpx
import pytest

import paymentflow

API_KEY = "sk_test_python"
BASE_URL = "https://api.test"


class Recorder:
    """Replies from a queued script, and remembers every request it was asked to make."""

    def __init__(self, *replies: httpx.Response) -> None:
        self._replies: Sequence[httpx.Response] = replies or (json_response({}),)
        self._index = 0
        #: Every request the SDK issued, in order.
        self.calls: List[httpx.Request] = []

    def handle(self, request: httpx.Request) -> httpx.Response:
        self.calls.append(request)
        reply = self._replies[min(self._index, len(self._replies) - 1)]
        self._index += 1
        # Rebuilt rather than returned: an httpx.Response carries read state, so a reply reused
        # across the attempts of a retried call would come back already consumed.
        return httpx.Response(reply.status_code, headers=reply.headers, content=reply.content)

    @property
    def transport(self) -> httpx.MockTransport:
        return httpx.MockTransport(self.handle)

    def client(self, **options: Any) -> paymentflow.PaymentFlow:
        return paymentflow.PaymentFlow(
            api_key=API_KEY,
            base_url=BASE_URL,
            http_client=httpx.Client(transport=self.transport),
            **options,
        )

    def query(self, index: int = 0) -> Dict[str, List[str]]:
        """The query string of one recorded call, as a multi-value mapping."""
        params = self.calls[index].url.params
        return {key: params.get_list(key) for key in params.keys()}

    def body(self, index: int = 0) -> Any:
        raw = self.calls[index].content
        return json.loads(raw) if raw else None


def json_response(payload: Any, status_code: int = 200, headers: Optional[Dict[str, str]] = None) -> httpx.Response:
    return httpx.Response(
        status_code,
        content=json.dumps(payload).encode("utf-8"),
        headers={"Content-Type": "application/json", **(headers or {})},
    )


@pytest.fixture
def recorder() -> Callable[..., Recorder]:
    """Builds a recorder from a reply script."""

    def build(*replies: httpx.Response) -> Recorder:
        return Recorder(*replies)

    return build
