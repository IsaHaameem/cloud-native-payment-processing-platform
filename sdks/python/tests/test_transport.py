"""The request pipeline: what goes on the wire, and what happens when it comes back wrong.

The mirror of ``sdks/node/test/transport.test.mjs``. The two SDKs are meant to be the same
client in two languages, so these assertions are deliberately the same assertions — a
divergence in behaviour should be visible as a difference between these two files.
"""

from __future__ import annotations

import re
import time
from typing import Any, Callable

import httpx
import pytest

import paymentflow
from conftest import API_KEY, Recorder, json_response  # noqa: F401


def test_a_client_cannot_be_built_without_an_api_key(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.delenv("PAYMENTFLOW_API_KEY", raising=False)
    with pytest.raises(paymentflow.PaymentFlowConfigurationError):
        paymentflow.PaymentFlow()


def test_the_api_key_is_read_from_the_environment(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("PAYMENTFLOW_API_KEY", "sk_test_from_env")
    assert paymentflow.PaymentFlow().config.api_key == "sk_test_from_env"


def test_options_that_cannot_work_are_rejected_where_they_are_set() -> None:
    # A key that survived a copy-paste with a trailing newline produces a 401 that reads
    # exactly like a revoked key, hours later, in production.
    with pytest.raises(paymentflow.PaymentFlowConfigurationError):
        paymentflow.PaymentFlow(api_key="sk_test_x\n")
    with pytest.raises(paymentflow.PaymentFlowConfigurationError):
        paymentflow.PaymentFlow(api_key=API_KEY, base_url="not a url")
    with pytest.raises(paymentflow.PaymentFlowConfigurationError):
        paymentflow.PaymentFlow(api_key=API_KEY, timeout=0)
    with pytest.raises(paymentflow.PaymentFlowConfigurationError):
        paymentflow.PaymentFlow(api_key=API_KEY, max_retries=-1)
    # `isinstance(True, int)` is True in Python, so a bool has to be rejected explicitly or
    # `max_retries=True` silently means one retry.
    with pytest.raises(paymentflow.PaymentFlowConfigurationError):
        paymentflow.PaymentFlow(api_key=API_KEY, max_retries=True)


# ── What goes on the wire ───────────────────────────────────────────────────────────────


def test_every_request_carries_the_key_the_revision_and_the_sdk_identity(
    recorder: Callable[..., Recorder]
) -> None:
    rec = recorder(json_response({"id": "pay_1"}))
    client = rec.client()

    client.payments.retrieve("pay_1")

    request = rec.calls[0]
    assert request.headers["Authorization"] == f"Bearer {API_KEY}"
    assert request.headers["PaymentFlow-Version"] == client.config.api_version
    assert re.match(r"^paymentflow-python/\d+\.\d+\.\d+ python/", request.headers["User-Agent"])
    assert str(request.url) == "https://api.test/v1/payments/pay_1"
    assert request.method == "GET"


def test_a_correlation_id_is_sent_when_given_and_not_invented_otherwise(
    recorder: Callable[..., Recorder]
) -> None:
    rec = recorder(json_response({"id": "pay_1"}), json_response({"id": "pay_1"}))
    client = rec.client()

    client.payments.retrieve("pay_1", options=paymentflow.RequestOptions(correlation_id="trace-42"))
    assert rec.calls[0].headers["X-Correlation-Id"] == "trace-42"

    # Omitted rather than filled in with a client-side guess: the platform generates one and
    # returns it, and two sources for one identifier is how they end up disagreeing.
    client.payments.retrieve("pay_1")
    assert "X-Correlation-Id" not in rec.calls[1].headers


def test_path_parameters_are_substituted_and_escaped(recorder: Callable[..., Recorder]) -> None:
    rec = recorder(json_response({"id": "x"}))
    rec.client().events.retrieve("evt/../admin")

    assert str(rec.calls[0].url) == "https://api.test/v1/events/evt%2F..%2Fadmin"


def test_a_missing_path_parameter_fails_before_anything_is_sent(recorder: Callable[..., Recorder]) -> None:
    rec = recorder(json_response({}))
    with pytest.raises(paymentflow.PaymentFlowError):
        rec.client().payments.retrieve("")

    assert rec.calls == [], "nothing reached the network"


def test_query_parameters_are_checked_against_the_published_operation(
    recorder: Callable[..., Recorder]
) -> None:
    rec = recorder(json_response({"data": []}))
    transport = rec.client()._transport  # the check lives below the resource layer

    # The platform ignores a filter it does not recognise and returns an unfiltered page, which
    # looks exactly like a correct answer to a narrower question. Caught at the call instead.
    from paymentflow._generated.operations import OPERATIONS
    from paymentflow._transport import RequestSpec

    with pytest.raises(paymentflow.PaymentFlowError, match="not a query parameter"):
        transport.request(RequestSpec(operation=OPERATIONS["listPayments"], query={"statuses": "captured"}))
    assert rec.calls == []


def test_metadata_is_encoded_as_deep_object_and_a_sequence_repeats_its_name(
    recorder: Callable[..., Recorder]
) -> None:
    rec = recorder(json_response({"data": []}), json_response({"content": [], "last": True}))
    client = rec.client()

    client.payments.list(metadata={"orderId": "A-1234", "channel": "web"}, limit=5)
    query = rec.query(0)
    assert query["metadata[orderId]"] == ["A-1234"]
    assert query["metadata[channel]"] == ["web"]
    assert query["limit"] == ["5"]

    client.webhook_deliveries.list(sort=["createdAt,desc", "id,asc"])
    assert rec.query(1)["sort"] == ["createdAt,desc", "id,asc"]


def test_a_204_returns_nothing_rather_than_failing_to_parse_a_body(
    recorder: Callable[..., Recorder]
) -> None:
    rec = recorder(httpx.Response(204))
    # `delete` is annotated `-> None`, so asserting on its return value is a type error rather
    # than a test. What is worth asserting is that a bodiless 204 does not raise on the way
    # back through the JSON parser.
    rec.client().webhook_endpoints.delete("we_1")
    assert rec.calls[0].method == "DELETE"


def test_unknown_fields_and_unknown_enum_values_ride_through_untouched(
    recorder: Callable[..., Recorder]
) -> None:
    rec = recorder(json_response({"id": "pay_1", "status": "quantum_pending", "somethingNew": {"a": 1}}))

    # §9's forward-compatibility promise: additive changes ship without a new revision, so an
    # SDK that validated responses would turn the platform's safest change into an outage.
    payment: Any = rec.client().payments.retrieve("pay_1")
    assert payment["status"] == "quantum_pending"
    assert payment["somethingNew"] == {"a": 1}


# ── Idempotency ─────────────────────────────────────────────────────────────────────────


def test_a_mutation_that_needs_a_key_gets_one_and_a_read_does_not(
    recorder: Callable[..., Recorder]
) -> None:
    rec = recorder(json_response({"id": "pay_1"}, 201), json_response({"id": "pay_1"}))
    client = rec.client()

    client.payments.create(amount_minor=1000, currency="USD")
    assert re.match(r"^[0-9a-f-]{36}$", rec.calls[0].headers["Idempotency-Key"])

    client.payments.retrieve("pay_1")
    assert "Idempotency-Key" not in rec.calls[1].headers


def test_a_caller_supplied_key_wins_over_the_generated_one(recorder: Callable[..., Recorder]) -> None:
    rec = recorder(json_response({"id": "pay_1"}, 201))
    rec.client().payments.create(
        amount_minor=1000, currency="USD", options=paymentflow.RequestOptions(idempotency_key="order-7")
    )
    assert rec.calls[0].headers["Idempotency-Key"] == "order-7"


def test_a_retried_mutation_reuses_the_key_of_the_attempt_it_is_retrying(
    recorder: Callable[..., Recorder]
) -> None:
    rec = recorder(json_response({"message": "nope"}, 500), json_response({"id": "pay_1"}, 201))

    payment: Any = rec.client(max_retries=2).payments.create(amount_minor=1000, currency="USD")

    # The property this SDK exists to hold. A key regenerated per attempt makes the platform
    # treat the retry as a new request, and the customer is charged twice — under exactly the
    # network conditions that cause retries, which is to say never in a hand-written test.
    assert len(rec.calls) == 2
    assert rec.calls[0].headers["Idempotency-Key"] == rec.calls[1].headers["Idempotency-Key"]
    assert payment["id"] == "pay_1"


# ── Retries ─────────────────────────────────────────────────────────────────────────────


def test_a_500_on_a_read_is_retried_up_to_the_budget_and_then_raised(
    recorder: Callable[..., Recorder]
) -> None:
    rec = recorder(json_response({"message": "boom"}, 500))

    with pytest.raises(paymentflow.ApiError) as raised:
        rec.client(max_retries=2).payments.retrieve("pay_1")

    assert len(rec.calls) == 3, "the first attempt plus two retries"
    assert raised.value.attempts == 3


def test_a_4xx_that_is_not_a_rate_limit_is_never_retried(recorder: Callable[..., Recorder]) -> None:
    rec = recorder(json_response({"type": "invalid_request_error", "message": "no"}, 400))

    with pytest.raises(paymentflow.InvalidRequestError):
        rec.client(max_retries=3).payments.retrieve("pay_1")
    assert len(rec.calls) == 1, "retrying only delays the error the caller needs to see"


def test_a_post_the_platform_does_not_deduplicate_is_not_replayed(
    recorder: Callable[..., Recorder]
) -> None:
    rec = recorder(json_response({"message": "boom"}, 503))

    # `POST /v1/webhook_endpoints` requires no Idempotency-Key, so the platform has no way to
    # recognise a replay. A response that never arrived does not mean a request that never
    # arrived, so retrying could leave two endpoints where the caller asked for one.
    with pytest.raises(paymentflow.ApiError):
        rec.client(max_retries=3).webhook_endpoints.create(url="https://x.test", enabled_events=["a"])
    assert len(rec.calls) == 1


def test_retry_after_is_honoured_over_the_computed_backoff(recorder: Callable[..., Recorder]) -> None:
    rec = recorder(
        json_response(
            {"type": "rate_limit_error", "code": "RATE_LIMIT_EXCEEDED"}, 429, {"Retry-After": "0"}
        ),
        json_response({"id": "pay_1"}),
    )

    payment: Any = rec.client(max_retries=1).payments.retrieve("pay_1")
    assert len(rec.calls) == 2
    assert payment["id"] == "pay_1"


def test_a_retry_after_longer_than_the_sdk_will_wait_ends_the_call(
    recorder: Callable[..., Recorder]
) -> None:
    seconds_until_midnight = 40_000
    rec = recorder(
        json_response(
            {"type": "rate_limit_error", "code": "DAILY_QUOTA_EXCEEDED", "message": "quota exhausted"},
            429,
            {"Retry-After": str(seconds_until_midnight), "RateLimit-Reset": str(seconds_until_midnight)},
        )
    )

    started = time.monotonic()
    with pytest.raises(paymentflow.RateLimitError) as raised:
        rec.client(max_retries=3).payments.retrieve("pay_1")

    # The daily quota clears at 00:00 UTC. Sleeping that out inside a caller's request handler
    # is not honouring the header, it is a hang — so the SDK surrenders and hands back the
    # interval, which is what a caller needs in order to schedule the work (D168).
    assert raised.value.retry_after_seconds == seconds_until_midnight
    assert len(rec.calls) == 1, "it did not sleep, and it did not silently ignore the header"
    assert time.monotonic() - started < 1


def test_rate_limit_reset_is_telemetry_and_never_a_delay(recorder: Callable[..., Recorder]) -> None:
    rec = recorder(
        json_response(
            {"data": [], "hasMore": False},
            200,
            {"RateLimit-Limit": "5000", "RateLimit-Remaining": "4999", "RateLimit-Reset": "43200"},
        )
    )

    # It describes the *daily* window and is present on successful responses too, so treating
    # it as "wait this long" would idle a perfectly healthy client until midnight (D167).
    started = time.monotonic()
    page = rec.client().payments.list()
    assert time.monotonic() - started < 1
    assert page.meta.rate_limit is not None
    assert page.meta.rate_limit.reset_seconds == 43200


def test_a_network_failure_is_retried_and_reported_as_a_connection_error() -> None:
    attempts = 0

    def handle(request: httpx.Request) -> httpx.Response:
        nonlocal attempts
        attempts += 1
        raise httpx.ConnectError("connection refused", request=request)

    client = paymentflow.PaymentFlow(
        api_key=API_KEY,
        base_url="https://api.test",
        max_retries=1,
        http_client=httpx.Client(transport=httpx.MockTransport(handle)),
    )

    with pytest.raises(paymentflow.ApiConnectionError) as raised:
        client.payments.retrieve("pay_1")

    assert attempts == 2
    assert raised.value.status_code is None, "there was no response to have a status"


def test_a_request_that_outlives_its_timeout_is_abandoned() -> None:
    def handle(request: httpx.Request) -> httpx.Response:
        raise httpx.ReadTimeout("too slow", request=request)

    client = paymentflow.PaymentFlow(
        api_key=API_KEY,
        base_url="https://api.test",
        timeout=0.02,
        max_retries=0,
        http_client=httpx.Client(transport=httpx.MockTransport(handle)),
    )

    with pytest.raises(paymentflow.ApiConnectionError, match="timed out"):
        client.payments.retrieve("pay_1")


# ── Errors ──────────────────────────────────────────────────────────────────────────────


def test_the_error_class_comes_from_the_type_field_rather_than_the_status(
    recorder: Callable[..., Recorder]
) -> None:
    cases = [
        ("authentication_error", 401, paymentflow.AuthenticationError),
        ("permission_error", 403, paymentflow.PermissionDeniedError),
        ("invalid_request_error", 400, paymentflow.InvalidRequestError),
        ("idempotency_error", 409, paymentflow.IdempotencyError),
        ("rate_limit_error", 429, paymentflow.RateLimitError),
        ("api_error", 500, paymentflow.ApiError),
    ]

    for error_type, status, expected in cases:
        rec = recorder(json_response({"type": error_type, "message": "x", "code": "C"}, status))
        with pytest.raises(expected):
            rec.client(max_retries=0).payments.retrieve("p")


def test_a_409_conflict_is_distinguishable_from_a_terminal_one(
    recorder: Callable[..., Recorder]
) -> None:
    # Both are 409. One may succeed on a later attempt because a concurrent request is holding
    # the key; the other never will, whatever the caller does.
    conflict = recorder(json_response({"type": "idempotency_error", "code": "IDEMPOTENCY_CONFLICT"}, 409))
    terminal = recorder(json_response({"type": "invalid_request_error", "code": "PAYMENT_NOT_CAPTURABLE"}, 409))

    with pytest.raises(paymentflow.IdempotencyError):
        conflict.client(max_retries=0).payments.capture("p")
    with pytest.raises(paymentflow.InvalidRequestError) as raised:
        terminal.client(max_retries=0).payments.capture("p")
    assert not isinstance(raised.value, paymentflow.IdempotencyError)


def test_an_unrecognised_error_type_falls_back_to_the_status(recorder: Callable[..., Recorder]) -> None:
    rec = recorder(json_response({"type": "teapot_error", "message": "new in a later revision"}, 403))

    # §9 lets new error types ship without a new API revision. An SDK that raised "unknown
    # error type" would make the platform's safest change an incident everywhere.
    with pytest.raises(paymentflow.PermissionDeniedError) as raised:
        rec.client(max_retries=0).payments.retrieve("p")
    assert raised.value.type == "teapot_error"


def test_an_error_body_that_is_not_the_error_contract_still_produces_a_usable_error(
    recorder: Callable[..., Recorder]
) -> None:
    rec = recorder(httpx.Response(502, content=b"<html>502 Bad Gateway</html>"))

    # A load balancer that never reached this platform writes whatever it likes. An error
    # constructor that can itself fail replaces a diagnosable failure with an undiagnosable one.
    with pytest.raises(paymentflow.ApiError) as raised:
        rec.client(max_retries=0).payments.retrieve("p")
    assert raised.value.status_code == 502
    assert "502" in raised.value.message


def test_an_error_carries_everything_the_caller_needs_to_report_it(
    recorder: Callable[..., Recorder]
) -> None:
    rec = recorder(
        json_response(
            {
                "type": "invalid_request_error",
                "code": "AMOUNT_TOO_SMALL",
                "message": "Amount must be positive.",
                "param": "amountMinor",
                "requestId": "req_from_body",
                "correlationId": "corr_1",
                "docUrl": "https://docs.test/AMOUNT_TOO_SMALL",
                "errors": [{"field": "amountMinor", "message": "must be positive"}],
            },
            400,
            {"X-Request-Id": "req_from_header"},
        )
    )

    with pytest.raises(paymentflow.InvalidRequestError) as raised:
        rec.client(max_retries=0).payments.create(amount_minor=0, currency="USD")

    error = raised.value
    assert error.code == "AMOUNT_TOO_SMALL"
    assert error.param == "amountMinor"
    assert error.doc_url == "https://docs.test/AMOUNT_TOO_SMALL"
    assert error.correlation_id == "corr_1"
    assert list(error.field_errors) == [{"field": "amountMinor", "message": "must be positive"}]
    # The body's own requestId wins: it is the value the platform wrote for this failure.
    assert error.request_id == "req_from_body"


# ── Response metadata ───────────────────────────────────────────────────────────────────


def test_a_successful_call_can_be_traced(recorder: Callable[..., Recorder]) -> None:
    rec = recorder(
        json_response(
            {"data": [{"id": "pay_1"}], "hasMore": False},
            200,
            {
                "X-Request-Id": "req_success",
                "X-Correlation-Id": "corr_success",
                "PaymentFlow-Version": "2026-08-01",
                "RateLimit-Limit": "5000",
                "RateLimit-Remaining": "4998",
                "RateLimit-Reset": "3600",
            },
        )
    )

    # `X-Request-Id` was sent downstream and never returned until M22.2, so a caller could
    # learn it only from an error body — and it keys every row of their own request log.
    page = rec.client().payments.list()
    assert page.meta.request_id == "req_success"
    assert page.meta.correlation_id == "corr_success"
    assert page.meta.api_version == "2026-08-01"
    assert page.meta.deprecated is False
    assert page.meta.rate_limit == paymentflow.RateLimitMeta(limit=5000, remaining=4998, reset_seconds=3600)
    assert page.meta.attempts == 1


def test_a_superseded_revision_is_reported_as_deprecated(recorder: Callable[..., Recorder]) -> None:
    rec = recorder(
        json_response(
            {"data": [], "hasMore": False},
            200,
            {"Deprecation": "true", "Sunset": "Wed, 01 Aug 2027 00:00:00 GMT"},
        )
    )
    assert rec.client().payments.list().meta.deprecated is True


def test_the_client_closes_the_http_client_it_created() -> None:
    client = paymentflow.PaymentFlow(api_key=API_KEY, base_url="https://api.test")
    with client:
        pass
    # A client used as a context manager must release its connection pool, or a long-running
    # process that builds one per job leaks sockets until it runs out.
    assert client._transport._http.is_closed


def test_the_client_does_not_close_an_http_client_it_was_given() -> None:
    given = httpx.Client(transport=httpx.MockTransport(lambda request: json_response({})))
    with paymentflow.PaymentFlow(api_key=API_KEY, base_url="https://api.test", http_client=given):
        pass

    # One passed in is the caller's, and may well be shared with the rest of their application.
    assert not given.is_closed
    given.close()

