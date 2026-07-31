"""The resource namespaces (M22.6), and the pagination they hand back.

The mirror of ``sdks/node/test/resources.test.mjs`` and ``pagination.test.mjs``. The assertion
running through this file is that every method maps to exactly one published operation and
performs exactly one HTTP request. "Do not perform hidden HTTP requests" is not a style
preference for a payments SDK — a convenience method that quietly makes a second chargeable
call is one whose failure modes an integrator cannot reason about.
"""

from __future__ import annotations

import json
import re
from pathlib import Path
from typing import Any, Callable, Dict, List, Tuple

import httpx
import pytest

import paymentflow
from conftest import Recorder, json_response
from paymentflow._generated.operations import OPERATIONS, OperationDescriptor

FIXTURES = Path(__file__).resolve().parents[2] / "shared" / "fixtures"
MODELS: Dict[str, List[str]] = json.loads((FIXTURES / "models.json").read_text(encoding="utf-8"))
RESOURCES = Path(__file__).resolve().parents[1] / "src" / "paymentflow" / "resources"


# ── Coverage ────────────────────────────────────────────────────────────────────────────


def test_every_published_operation_is_reachable_from_a_resource_namespace() -> None:
    source = "\n".join(
        path.read_text(encoding="utf-8") for path in sorted(RESOURCES.glob("*.py"))
    )

    # Derived from the generated table rather than from a list written here, so an endpoint
    # added to the platform shows up as a failure rather than as an SDK that quietly cannot
    # call it. This is the check that makes "implement every approved namespace" verifiable.
    missing = [name for name in OPERATIONS if f'OPERATIONS["{name}"]' not in source]
    assert missing == [], "operations no resource method calls"

    contract = json.loads((FIXTURES / "contract.json").read_text(encoding="utf-8"))
    assert len(OPERATIONS) == contract["operationCount"]


#: One invocation per resource method, with the operation it is supposed to address.
CASES: List[Tuple[str, Callable[[paymentflow.PaymentFlow], Any], OperationDescriptor]] = [
    ("payments.create", lambda c: c.payments.create(amount_minor=1, currency="USD"), OPERATIONS["createPayment"]),
    ("payments.retrieve", lambda c: c.payments.retrieve("p"), OPERATIONS["getPayment"]),
    ("payments.authorize", lambda c: c.payments.authorize("p"), OPERATIONS["authorizePayment"]),
    ("payments.capture", lambda c: c.payments.capture("p"), OPERATIONS["capturePayment"]),
    ("payments.refund", lambda c: c.payments.refund("p"), OPERATIONS["refundPayment"]),
    ("payments.void", lambda c: c.payments.void("p"), OPERATIONS["voidPayment"]),
    ("refunds.retrieve", lambda c: c.refunds.retrieve("r"), OPERATIONS["getRefund"]),
    ("balance.retrieve", lambda c: c.balance.retrieve(), OPERATIONS["getBalance"]),
    ("events.retrieve", lambda c: c.events.retrieve("e"), OPERATIONS["getEvent"]),
    (
        "analytics.retrieve_payment_summary",
        lambda c: c.analytics.retrieve_payment_summary(),
        OPERATIONS["getPaymentAnalytics"],
    ),
    ("usage.retrieve", lambda c: c.usage.retrieve(), OPERATIONS["getUsage"]),
    (
        "webhook_endpoints.create",
        lambda c: c.webhook_endpoints.create(url="https://x.test", enabled_events=[]),
        OPERATIONS["createWebhookEndpoint"],
    ),
    ("webhook_endpoints.retrieve", lambda c: c.webhook_endpoints.retrieve("we"), OPERATIONS["getWebhookEndpoint"]),
    ("webhook_endpoints.list", lambda c: c.webhook_endpoints.list(), OPERATIONS["listWebhookEndpoints"]),
    (
        "webhook_endpoints.update",
        lambda c: c.webhook_endpoints.update("we", enabled=False),
        OPERATIONS["updateWebhookEndpoint"],
    ),
    ("webhook_endpoints.delete", lambda c: c.webhook_endpoints.delete("we"), OPERATIONS["deleteWebhookEndpoint"]),
    (
        "webhook_endpoints.rotate_secret",
        lambda c: c.webhook_endpoints.rotate_secret("we"),
        OPERATIONS["rotateWebhookEndpointSecret"],
    ),
    ("webhook_deliveries.retrieve", lambda c: c.webhook_deliveries.retrieve("wd"), OPERATIONS["getWebhookDelivery"]),
    ("webhook_deliveries.replay", lambda c: c.webhook_deliveries.replay("wd"), OPERATIONS["replayWebhookDelivery"]),
    ("test_helpers.list_cards", lambda c: c.test_helpers.list_cards(), OPERATIONS["listTestCards"]),
    (
        "test_helpers.list_decisions_for_payment",
        lambda c: c.test_helpers.list_decisions_for_payment("p"),
        OPERATIONS["listSandboxDecisionsForPayment"],
    ),
    (
        "test_helpers.create_simulation_override",
        lambda c: c.test_helpers.create_simulation_override(scenario="DECLINE"),
        OPERATIONS["createSimulationOverride"],
    ),
    (
        "test_helpers.retrieve_active_simulation_override",
        lambda c: c.test_helpers.retrieve_active_simulation_override(),
        OPERATIONS["getActiveSimulationOverride"],
    ),
    (
        "test_helpers.revoke_active_simulation_override",
        lambda c: c.test_helpers.revoke_active_simulation_override(),
        OPERATIONS["revokeActiveSimulationOverride"],
    ),
]


def test_every_resource_method_makes_exactly_one_request_to_the_operation_it_names(
    recorder: Callable[..., Recorder]
) -> None:
    # One call per method, checked against the descriptor rather than against a hand-written
    # URL: a method wired to the wrong operation would otherwise pass a test that transcribed
    # the same mistake.
    for name, invoke, descriptor in CASES:
        rec = recorder(json_response({}))
        invoke(rec.client())

        assert len(rec.calls) == 1, f"{name} makes exactly one request"
        assert rec.calls[0].method == descriptor["method"], f"{name} uses the documented method"
        template = re.sub(r"\{\w+\}", "[^/]+", descriptor["path"])
        assert re.fullmatch(template, rec.calls[0].url.path), f"{name} calls {descriptor['path']}"


def test_a_body_is_sent_only_where_the_contract_says_the_operation_takes_one(
    recorder: Callable[..., Recorder]
) -> None:
    for name, invoke, descriptor in CASES:
        rec = recorder(json_response({}))
        invoke(rec.client())

        sent = bool(rec.calls[0].content)
        if descriptor["has_request_body"]:
            # `payments.refund` and `webhook_endpoints.update` send `{}` when nothing was
            # supplied, which is a body the API accepts.
            assert sent, f"{name} sends a body"
            assert rec.calls[0].headers["Content-Type"] == "application/json"
        else:
            assert not sent, f"{name} sends no body"


def test_every_body_field_this_sdk_sends_exists_in_the_contract(
    recorder: Callable[..., Recorder]
) -> None:
    """The guard on D179's hand-written field mapping.

    Each method writes the contract's field names out explicitly, so the mapping is local and
    reviewable rather than performed by a runtime name-mangler. This is what stops it drifting:
    every key any method actually sends is checked against the model the contract publishes for
    that operation, so a typo or a renamed field fails here rather than being ignored by the
    platform and discovered as a field that silently never took effect.
    """
    calls: List[Tuple[str, Callable[[paymentflow.PaymentFlow], Any], str]] = [
        (
            "createPayment",
            lambda c: c.payments.create(
                amount_minor=1,
                currency="USD",
                description="d",
                payment_method_token="tok",
                metadata={"k": "v"},
            ),
            "CreatePaymentRequest",
        ),
        (
            "refundPayment",
            lambda c: c.payments.refund("p", amount_minor=1, reason="r", metadata={"k": "v"}),
            "RefundRequest",
        ),
        (
            "createWebhookEndpoint",
            lambda c: c.webhook_endpoints.create(
                url="https://x.test", enabled_events=["a"], description="d", metadata={"k": "v"}
            ),
            "CreateWebhookEndpointRequest",
        ),
        (
            "updateWebhookEndpoint",
            lambda c: c.webhook_endpoints.update(
                "we", enabled=True, enabled_events=["a"], description="d", metadata={"k": "v"}
            ),
            "UpdateWebhookEndpointRequest",
        ),
        (
            "createSimulationOverride",
            lambda c: c.test_helpers.create_simulation_override(
                scenario="DECLINE",
                decline_code="dc",
                error_code="ec",
                latency_ms=1,
                remaining_count=2,
                duration_seconds=3,
            ),
            "CreateSimulationOverrideRequest",
        ),
    ]

    for operation_id, invoke, model in calls:
        rec = recorder(json_response({}))
        invoke(rec.client())
        sent = json.loads(rec.calls[0].content)

        published = MODELS[model]
        unknown = sorted(set(sent) - set(published))
        assert unknown == [], f"{operation_id} sends fields {model} does not declare"

        # And every documented field is reachable, so a parameter quietly dropped from a
        # signature fails too. The two directions together pin the mapping exactly.
        assert sorted(sent) == sorted(published), f"{operation_id} does not offer every field of {model}"


def test_an_optional_parameter_left_out_is_omitted_rather_than_sent_as_null(
    recorder: Callable[..., Recorder]
) -> None:
    rec = recorder(json_response({}))
    rec.client().payments.create(amount_minor=1, currency="USD")

    # Sending `{"description": null}` is not the same request as omitting it — the platform
    # reads the first as "set this to nothing".
    assert json.loads(rec.calls[0].content) == {"amountMinor": 1, "currency": "USD"}


# ── Return shapes ───────────────────────────────────────────────────────────────────────


def test_a_method_returns_exactly_the_resource_the_api_returned(
    recorder: Callable[..., Recorder]
) -> None:
    body = {"id": "pay_1", "status": "captured", "amountMinor": 1000, "refunds": [{"id": "re_1"}]}
    rec = recorder(json_response(body))

    # No envelope, no ``data`` wrapper, no derived fields. What the API returned is what a
    # caller can match against the documentation they read.
    assert rec.client().payments.retrieve("pay_1") == body


def test_refunding_a_payment_returns_the_payment(recorder: Callable[..., Recorder]) -> None:
    rec = recorder(json_response({"id": "pay_1", "object": "payment", "refunds": [{"id": "re_1"}]}))

    # Reshaping this into the refund would need either a second request or a guess about which
    # element of ``refunds`` is the new one. Both are worse than returning what arrived.
    result: Any = rec.client().payments.refund("pay_1", amount_minor=500)
    assert result["object"] == "payment"


def test_an_unpaginated_list_is_a_plain_list(recorder: Callable[..., Recorder]) -> None:
    rec = recorder(json_response([{"id": "we_1"}, {"id": "we_2"}]))

    # ``/v1/webhook_endpoints`` returns a bare array. Wrapping it would mean inventing a
    # ``has_more`` that no response carries.
    endpoints = rec.client().webhook_endpoints.list()
    assert isinstance(endpoints, list)
    assert len(endpoints) == 2


def test_the_analytics_and_usage_windows_are_sent_as_from_and_to(
    recorder: Callable[..., Recorder]
) -> None:
    rec = recorder(json_response({}), json_response({}))
    client = rec.client()

    # `from` is a Python keyword, so the parameter is `from_` and has to arrive as `from`. A
    # trailing underscore that reached the wire would be silently ignored by the platform and
    # the caller would get an unfiltered summary.
    client.analytics.retrieve_payment_summary(from_="2026-07-01T00:00:00Z", to="2026-08-01T00:00:00Z")
    assert rec.query(0)["from"] == ["2026-07-01T00:00:00Z"]
    assert rec.query(0)["to"] == ["2026-08-01T00:00:00Z"]

    client.usage.retrieve(from_="2026-07-01", to="2026-07-31")
    assert rec.query(1)["from"] == ["2026-07-01"]


# ── Pagination ──────────────────────────────────────────────────────────────────────────

#: Every cursor-paginated list, with the filter it was called with.
CURSOR_LISTS: List[Tuple[str, Callable[[paymentflow.PaymentFlow], Any]]] = [
    ("payments", lambda c: c.payments.list(status="captured")),
    ("refunds", lambda c: c.refunds.list(payment="pay_1")),
    ("events", lambda c: c.events.list(type="payment.captured")),
    ("balance_transactions", lambda c: c.balance_transactions.list(limit=25)),
    ("request_logs", lambda c: c.request_logs.list(status_code=200)),
]

#: Every offset-paginated list — the two D139 left on the older envelope.
OFFSET_LISTS: List[Tuple[str, Callable[[paymentflow.PaymentFlow], Any]]] = [
    ("webhook_deliveries", lambda c: c.webhook_deliveries.list(size=1)),
    ("test_helpers.list_decisions", lambda c: c.test_helpers.list_decisions(size=1)),
]

#: The lists that are not paginated on the wire and must not pretend to be.
PLAIN_LISTS: List[Tuple[str, Callable[[paymentflow.PaymentFlow], Any]]] = [
    ("webhook_endpoints", lambda c: c.webhook_endpoints.list()),
    ("test_helpers.list_cards", lambda c: c.test_helpers.list_cards()),
    ("test_helpers.list_decisions_for_payment", lambda c: c.test_helpers.list_decisions_for_payment("p")),
]


def test_every_list_method_is_accounted_for_by_one_of_the_three_tables(
    recorder: Callable[..., Recorder]
) -> None:
    client = recorder(json_response({"data": [], "hasMore": False})).client()
    named = {name for name, _ in CURSOR_LISTS + OFFSET_LISTS + PLAIN_LISTS}

    found = []
    for namespace in [
        "payments", "refunds", "balance", "balance_transactions", "events",
        "analytics", "request_logs", "usage", "webhook_endpoints",
        "webhook_deliveries", "test_helpers",
    ]:
        resource = getattr(client, namespace)
        for method in dir(type(resource)):
            if method.startswith("list"):
                found.append(namespace if method == "list" else f"{namespace}.{method}")

    # Derived from the client rather than transcribed, so a list method added without a
    # decision about how it paginates shows up here as a failure.
    assert [name for name in found if name not in named] == [], "list methods with no pagination test"
    assert len(found) >= 9


@pytest.mark.parametrize("name,invoke", CURSOR_LISTS, ids=[name for name, _ in CURSOR_LISTS])
def test_every_cursor_list_iterates_across_pages_carrying_its_filters(
    name: str, invoke: Callable[[paymentflow.PaymentFlow], Any], recorder: Callable[..., Recorder]
) -> None:
    rec = recorder(
        json_response({"data": [{"id": "a"}, {"id": "b"}], "hasMore": True, "nextCursor": "cur_1"}),
        json_response({"data": [{"id": "c"}], "hasMore": False}),
    )

    seen = [item["id"] for item in invoke(rec.client())]

    assert seen == ["a", "b", "c"], f"{name} walks both pages"
    assert len(rec.calls) == 2

    second = rec.query(1)
    assert second["starting_after"] == ["cur_1"], f"{name} sends the cursor"
    # Whatever filter the first call carried must be on the second. A page fetched with
    # different filters than the cursor was minted under returns a set that never existed.
    for key, value in rec.query(0).items():
        assert second[key] == value, f"{name} keeps {key} across pages"


@pytest.mark.parametrize("name,invoke", CURSOR_LISTS, ids=[name for name, _ in CURSOR_LISTS])
def test_every_cursor_list_exposes_the_same_manual_controls(
    name: str, invoke: Callable[[paymentflow.PaymentFlow], Any], recorder: Callable[..., Recorder]
) -> None:
    rec = recorder(
        json_response({"data": [{"id": "a"}], "hasMore": True, "nextCursor": "cur_1"}),
        json_response({"data": [{"id": "b"}], "hasMore": False}),
    )

    page = invoke(rec.client())
    assert page.data == [{"id": "a"}]
    assert page.has_more is True
    assert page.next_cursor == "cur_1"
    assert len(page) == 1
    assert page.meta.status_code == 200

    last = page.next_page()
    assert last is not None and last.has_more is False
    assert last.next_page() is None


@pytest.mark.parametrize("name,invoke", CURSOR_LISTS, ids=[name for name, _ in CURSOR_LISTS])
def test_every_cursor_list_stops_making_requests_when_the_caller_stops(
    name: str, invoke: Callable[[paymentflow.PaymentFlow], Any], recorder: Callable[..., Recorder]
) -> None:
    rec = recorder(
        json_response({"data": [{"id": "a"}], "hasMore": True, "nextCursor": "cur_1"}),
        json_response({"data": [{"id": "b"}], "hasMore": False}),
    )

    for _item in invoke(rec.client()):
        break

    assert len(rec.calls) == 1, f"{name} does not fetch the rest of the account after a break"


@pytest.mark.parametrize("name,invoke", CURSOR_LISTS, ids=[name for name, _ in CURSOR_LISTS])
def test_a_cursor_page_with_a_cursor_and_no_flag_is_not_the_last(
    name: str, invoke: Callable[[paymentflow.PaymentFlow], Any], recorder: Callable[..., Recorder]
) -> None:
    rec = recorder(
        json_response({"data": [{"id": "a"}], "nextCursor": "cur_1"}),
        json_response({"data": [{"id": "b"}], "hasMore": False}),
    )

    # Defaulting to ``False`` would silently truncate — a failure that looks correct on a small
    # account and loses data on a large one.
    assert [item["id"] for item in invoke(rec.client())] == ["a", "b"]
    assert len(rec.calls) == 2


@pytest.mark.parametrize("name,invoke", CURSOR_LISTS, ids=[name for name, _ in CURSOR_LISTS])
def test_an_empty_cursor_list_iterates_zero_times(
    name: str, invoke: Callable[[paymentflow.PaymentFlow], Any], recorder: Callable[..., Recorder]
) -> None:
    rec = recorder(json_response({"data": [], "hasMore": False}))

    assert list(invoke(rec.client())) == []
    assert len(rec.calls) == 1, f"{name} does not ask for a page that cannot exist"


@pytest.mark.parametrize("name,invoke", OFFSET_LISTS, ids=[name for name, _ in OFFSET_LISTS])
def test_every_offset_list_iterates_by_page_index_and_reports_totals(
    name: str, invoke: Callable[[paymentflow.PaymentFlow], Any], recorder: Callable[..., Recorder]
) -> None:
    rec = recorder(
        json_response({"content": [{"id": "a"}], "page": 0, "size": 1, "totalElements": 2, "totalPages": 2, "last": False}),
        json_response({"content": [{"id": "b"}], "page": 1, "size": 1, "totalElements": 2, "totalPages": 2, "last": True}),
    )

    page = invoke(rec.client())
    assert page.total_elements == 2, f"{name} reports a total, unlike a cursor page"
    assert page.total_pages == 2
    assert page.page == 0
    assert page.has_more is True
    assert page.content == [{"id": "a"}]

    assert [item["id"] for item in page] == ["a", "b"]
    assert rec.query(0)["page"] == ["0"]
    assert rec.query(1)["page"] == ["1"]


@pytest.mark.parametrize("name,invoke", OFFSET_LISTS, ids=[name for name, _ in OFFSET_LISTS])
def test_an_offset_list_derives_has_more_when_last_is_absent(
    name: str, invoke: Callable[[paymentflow.PaymentFlow], Any], recorder: Callable[..., Recorder]
) -> None:
    rec = recorder(
        json_response({"content": [{"id": "a"}], "page": 0, "size": 1, "totalElements": 2, "totalPages": 2}),
        json_response({"content": [{"id": "b"}], "page": 1, "size": 1, "totalElements": 2, "totalPages": 2}),
    )

    page = invoke(rec.client())
    assert page.has_more is True, f"{name} works out that page 0 of 2 is not the last"
    second = page.next_page()
    assert second is not None and second.has_more is False
    assert second.next_page() is None


def test_an_offset_list_honours_a_starting_page_index(recorder: Callable[..., Recorder]) -> None:
    rec = recorder(
        json_response({"content": [{"id": "c"}], "page": 2, "size": 1, "totalElements": 3, "totalPages": 3, "last": True})
    )
    rec.client().webhook_deliveries.list(page=2, size=1)
    assert rec.query(0)["page"] == ["2"]


@pytest.mark.parametrize("name,invoke", PLAIN_LISTS, ids=[name for name, _ in PLAIN_LISTS])
def test_an_unpaginated_list_has_no_page_machinery(
    name: str, invoke: Callable[[paymentflow.PaymentFlow], Any], recorder: Callable[..., Recorder]
) -> None:
    rec = recorder(json_response([{"id": "a"}, {"id": "b"}]))

    result = invoke(rec.client())
    assert isinstance(result, list)
    assert len(result) == 2
    assert not hasattr(result, "has_more"), f"{name} invents no has_more"
    assert not hasattr(result, "next_page"), f"{name} invents no next_page"
    assert len(rec.calls) == 1


def test_both_page_types_satisfy_the_same_iteration_contract(
    recorder: Callable[..., Recorder]
) -> None:
    cursor = recorder(json_response({"data": [{"id": "a"}], "hasMore": False})).client().payments.list()
    offset = (
        recorder(
            json_response({"content": [{"id": "a"}], "page": 0, "size": 1, "totalElements": 1, "totalPages": 1, "last": True})
        )
        .client()
        .webhook_deliveries.list()
    )

    # The shared surface a caller can rely on without knowing which shape they were handed.
    for name, page in [("cursor", cursor), ("offset", offset)]:
        assert isinstance(page, paymentflow.Page), f"{name} is a Page"
        assert isinstance(page.has_more, bool)
        assert page.next_page() is None
        assert page.meta.status_code == 200
        assert [item["id"] for item in page] == ["a"], f"{name} yields its one item"
