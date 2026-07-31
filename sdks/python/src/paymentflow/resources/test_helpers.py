"""``client.test_helpers`` — the sandbox controls (M22.6).

Six operations that only exist in test mode. They are grouped under one namespace rather than
scattered across ``cards``, ``decisions`` and ``simulations`` because what they have in common
is the thing worth signalling at the call site: none of this works with a live key, and code
that reaches for it is test-mode code.

The mode is decided by the key alone (§9). This SDK has no ``mode`` option and will not get one
— a switch that appeared to move a client between test and live would be a lie, because the
platform ignores everything except which key was presented.
"""

from __future__ import annotations

from typing import Optional, Sequence

from .._generated.models import DecisionLogEntryResponse, SimulationOverrideResponse, TestCardResponse
from .._generated.operations import OPERATIONS
from .._pagination import OffsetPage
from .._transport import RequestOptions
from ._base import Resource, body_of, query_of

__all__ = ["TestHelpers"]


class TestHelpers(Resource):
    def list_cards(self, *, options: Optional[RequestOptions] = None) -> Sequence[TestCardResponse]:
        """Lists the seeded test cards and what each one does.

        Returns a plain list, because the endpoint does — this catalogue is small and fixed,
        and is not paginated on the wire.
        """
        result: Sequence[TestCardResponse] = self._send(OPERATIONS["listTestCards"], options=options)
        return result

    def list_decisions(
        self,
        *,
        page: Optional[int] = None,
        size: Optional[int] = None,
        sort: Optional[Sequence[str]] = None,
        options: Optional[RequestOptions] = None,
    ) -> OffsetPage[DecisionLogEntryResponse]:
        """Lists authorization decisions the sandbox made, and why."""
        query = query_of(page=page, size=size, sort=None if sort is None else list(sort))
        return self._list_offset(OPERATIONS["listSandboxDecisions"], query, options)

    def list_decisions_for_payment(
        self, payment_id: str, *, options: Optional[RequestOptions] = None
    ) -> Sequence[DecisionLogEntryResponse]:
        """Lists the decisions made for one payment.

        A plain list rather than a page: one payment's decisions are few and the endpoint
        returns them all.
        """
        result: Sequence[DecisionLogEntryResponse] = self._send(
            OPERATIONS["listSandboxDecisionsForPayment"], path={"paymentId": payment_id}, options=options
        )
        return result

    def create_simulation_override(
        self,
        *,
        scenario: str,
        decline_code: Optional[str] = None,
        error_code: Optional[str] = None,
        latency_ms: Optional[int] = None,
        remaining_count: Optional[int] = None,
        duration_seconds: Optional[int] = None,
        options: Optional[RequestOptions] = None,
    ) -> SimulationOverrideResponse:
        """Forces a behaviour for subsequent authorizations, replacing any active override."""
        body = body_of(
            scenario=scenario,
            declineCode=decline_code,
            errorCode=error_code,
            latencyMs=latency_ms,
            remainingCount=remaining_count,
            durationSeconds=duration_seconds,
        )
        result: SimulationOverrideResponse = self._send(
            OPERATIONS["createSimulationOverride"], body=body, options=options
        )
        return result

    def retrieve_active_simulation_override(
        self, *, options: Optional[RequestOptions] = None
    ) -> SimulationOverrideResponse:
        """Retrieves the active override."""
        result: SimulationOverrideResponse = self._send(
            OPERATIONS["getActiveSimulationOverride"], options=options
        )
        return result

    def revoke_active_simulation_override(self, *, options: Optional[RequestOptions] = None) -> None:
        """Revokes the active override. Returns nothing: the API returns 204."""
        self._send(OPERATIONS["revokeActiveSimulationOverride"], options=options)
