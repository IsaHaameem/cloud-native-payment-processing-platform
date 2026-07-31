"""``client.events`` — the event log behind webhooks (M22.6).

The same events your endpoints receive, readable after the fact. This is what a receiver
reconciles against when it suspects it missed one, so both methods are read-only: an event is a
record of something that happened and is not a thing a caller creates.
"""

from __future__ import annotations

from typing import Optional

from .._generated.models import EventResponse
from .._generated.operations import OPERATIONS
from .._pagination import CursorPage
from .._transport import RequestOptions
from ._base import Resource, query_of

__all__ = ["Events"]


class Events(Resource):
    def retrieve(self, event_id: str, *, options: Optional[RequestOptions] = None) -> EventResponse:
        """Retrieves one event."""
        result: EventResponse = self._send(OPERATIONS["getEvent"], path={"id": event_id}, options=options)
        return result

    def list(
        self,
        *,
        limit: Optional[int] = None,
        starting_after: Optional[str] = None,
        type: Optional[str] = None,
        created_after: Optional[str] = None,
        created_before: Optional[str] = None,
        options: Optional[RequestOptions] = None,
    ) -> CursorPage[EventResponse]:
        """Lists your events, most recent first.

        ``type`` filters to one event type, such as ``payment.captured``. It shadows the
        builtin inside this method and nowhere else, which is the trade the wire name is worth:
        the alternative is an ``event_type`` argument that matches neither the API nor the
        Node SDK.
        """
        query = query_of(
            limit=limit,
            starting_after=starting_after,
            type=type,
            created_after=created_after,
            created_before=created_before,
        )
        return self._list_cursor(OPERATIONS["listEvents"], query, options)
