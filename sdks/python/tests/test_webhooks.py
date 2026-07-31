"""Webhook signature verification (M22.5).

The mirror of ``sdks/node/test/webhooks.test.mjs``, and like it, driven by the **shared vector
file** — the same
``notification-service/src/test/resources/signature-vectors/webhook-signature-vectors.json``
that the platform's own ``WebhookSigner``, the reference ``verify.js`` and the reference
``verify.py`` are checked against.

Asserting against the vectors rather than against this SDK's own output is what makes these
tests worth anything. A suite that signed a body and then verified it would pass just as
happily if the algorithm were wrong in both directions — and it would pass in *both* languages,
which is exactly the divergence the shared file exists to catch.
"""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any, Dict, List

import pytest

import paymentflow

VECTORS = (
    Path(__file__).resolve().parents[3]
    / "notification-service"
    / "src"
    / "test"
    / "resources"
    / "signature-vectors"
    / "webhook-signature-vectors.json"
)
DOC: Dict[str, Any] = json.loads(VECTORS.read_text(encoding="utf-8"))
VECTOR_LIST: List[Dict[str, Any]] = DOC["vectors"]

SECRET = "whsec_TestVectorSecretDoNotUseInProduction"
EVENT_BODY = json.dumps(
    {
        "id": "evt_3f2504e04f8941d39a0c0305e82c3301",
        "object": "event",
        "type": "payment.captured",
        "apiVersion": "2026-08-01",
        "created": "2026-08-01T12:00:00Z",
        "mode": "test",
        "data": {"object": {"id": "pay_1", "object": "payment", "amountMinor": 5000, "currency": "USD"}},
    }
)
NOW = 1785758400


def verify(body: Any, header: str, secret: str = SECRET, **options: Any) -> Dict[str, Any]:
    """Verifies at a fixed instant, so no test here depends on the wall clock."""
    return paymentflow.construct_event(body, header, secret, options.pop("tolerance", None), now=NOW, **options)


def header_at(timestamp: int, body: Any = EVENT_BODY, secret: str = SECRET) -> str:
    return paymentflow.signature_header_for(secret, timestamp, body)


# ── The shared vectors ──────────────────────────────────────────────────────────────────


def test_the_vector_file_is_present_and_non_empty() -> None:
    # Asserted first, so nothing below can pass by iterating an empty list — the failure mode
    # of every fixture-driven suite.
    assert len(VECTOR_LIST) >= 5
    assert DOC["algorithm"] == "HMAC-SHA256"


@pytest.mark.parametrize("vector", VECTOR_LIST, ids=[v["name"] for v in VECTOR_LIST])
def test_this_sdk_reproduces_every_published_signature_vector(vector: Dict[str, Any]) -> None:
    assert paymentflow.sign_payload(vector["secret"], vector["timestamp"], vector["body"]) == vector["expectedV1"]


@pytest.mark.parametrize("vector", VECTOR_LIST, ids=[v["name"] for v in VECTOR_LIST])
def test_the_signed_payload_is_timestamp_dot_body(vector: Dict[str, Any]) -> None:
    assert vector["signedPayload"] == f"{vector['timestamp']}.{vector['body']}"


def test_a_utf8_body_signs_over_its_bytes() -> None:
    vector = next(v for v in VECTOR_LIST if v["name"] == "unicode_body")

    # The same body as bytes must produce the same signature as the string. A receiver
    # frequently has the raw bytes and never decodes them, and that path has to agree.
    assert paymentflow.sign_payload(vector["secret"], vector["timestamp"], vector["body"]) == vector["expectedV1"]
    assert (
        paymentflow.sign_payload(vector["secret"], vector["timestamp"], vector["body"].encode("utf-8"))
        == vector["expectedV1"]
    )


def test_an_empty_body_still_signs() -> None:
    vector = next(v for v in VECTOR_LIST if v["name"] == "empty_body")
    assert paymentflow.sign_payload(vector["secret"], vector["timestamp"], "") == vector["expectedV1"]


def test_the_whsec_prefix_is_part_of_the_key() -> None:
    vector = VECTOR_LIST[0]

    # Stripping it is the obvious "tidy-up" a reimplementation makes, and it produces a
    # signature that is wrong for every delivery while looking entirely reasonable.
    stripped = paymentflow.sign_payload(vector["secret"][len("whsec_") :], vector["timestamp"], vector["body"])
    assert stripped != vector["expectedV1"]


# ── construct_event: the happy path ─────────────────────────────────────────────────────


def test_a_well_formed_delivery_returns_its_event() -> None:
    event = verify(EVENT_BODY, header_at(NOW))

    assert event["id"] == "evt_3f2504e04f8941d39a0c0305e82c3301"
    assert event["type"] == "payment.captured"
    assert event["apiVersion"] == "2026-08-01"
    assert event["data"]["object"]["id"] == "pay_1"


def test_the_raw_body_may_be_bytes() -> None:
    assert verify(EVENT_BODY.encode("utf-8"), header_at(NOW))["type"] == "payment.captured"


def test_a_rotated_endpoint_sends_both_signatures_and_either_verifies() -> None:
    current = "whsec_TheNewSecretAfterRotation"

    # The dual-secret rotation window on the wire: t=…,v1=<new>,v1=<old>. A receiver that has
    # switched and one that has not must both succeed, or rotation is an outage.
    header = (
        f"t={NOW}"
        f",v1={paymentflow.sign_payload(current, NOW, EVENT_BODY)}"
        f",v1={paymentflow.sign_payload(SECRET, NOW, EVENT_BODY)}"
    )

    assert verify(EVENT_BODY, header, current)["type"] == "payment.captured"
    assert verify(EVENT_BODY, header, SECRET)["type"] == "payment.captured"


def test_an_unknown_field_in_the_header_is_ignored() -> None:
    # A future `v2` alongside `v1` is how this scheme would gain a second algorithm. A verifier
    # that rejected the whole header on sight of one would break on the day that shipped.
    assert verify(EVENT_BODY, f"{header_at(NOW)},v2=notyetinvented")["type"] == "payment.captured"


def test_verification_needs_no_client_and_matches_the_namespace_on_one() -> None:
    # A receiver is often a different process that holds no API key. Requiring a client would
    # mean either handing it a secret key it does not need, or not verifying.
    client = paymentflow.PaymentFlow(api_key="sk_test_webhooks", base_url="https://api.test")
    assert client.webhooks.construct_event(EVENT_BODY, header_at(NOW), SECRET, now=NOW) == verify(
        EVENT_BODY, header_at(NOW)
    )
    assert client.webhooks.SIGNATURE_HEADER == paymentflow.SIGNATURE_HEADER
    client.close()


# ── construct_event: rejection ──────────────────────────────────────────────────────────


def test_a_tampered_body_is_rejected() -> None:
    # One appended space. The whole point of the scheme.
    with pytest.raises(paymentflow.WebhookSignatureError):
        verify(EVENT_BODY + " ", header_at(NOW))


def test_a_signature_under_the_wrong_secret_is_rejected() -> None:
    with pytest.raises(paymentflow.WebhookSignatureError):
        verify(EVENT_BODY, header_at(NOW), "whsec_NotTheRightSecretAtAll")


def test_a_reserialized_body_is_rejected_and_the_message_says_why() -> None:
    # The single most common way a correct integration fails: json.loads then json.dumps does
    # not round-trip bytes, so the signature covers something the caller no longer has.
    sent = json.dumps(json.loads(EVENT_BODY), indent=2)
    reserialized = json.dumps(json.loads(sent), separators=(",", ":"))
    assert sent != reserialized

    with pytest.raises(paymentflow.WebhookSignatureError, match="raw request body"):
        verify(reserialized, header_at(NOW, sent))


def test_a_stale_delivery_is_a_timestamp_problem_not_a_signature_problem() -> None:
    old = NOW - 8000
    with pytest.raises(paymentflow.WebhookTimestampError) as raised:
        verify(EVENT_BODY, header_at(old))

    # §7.1 requires these to be distinct: a valid signature arriving late is a replay or a
    # skewed clock, and neither is "your secret is wrong".
    assert not isinstance(raised.value, paymentflow.WebhookSignatureError)
    assert raised.value.timestamp == old
    assert raised.value.skew_seconds == 8000


def test_a_timestamp_far_in_the_future_is_rejected_too() -> None:
    # Absolute skew, not just "too old".
    with pytest.raises(paymentflow.WebhookTimestampError) as raised:
        verify(EVENT_BODY, header_at(NOW + 8000))
    assert raised.value.skew_seconds == 8000


def test_the_tolerance_window_is_inclusive_at_its_edge() -> None:
    tolerance = 300
    assert verify(EVENT_BODY, header_at(NOW - tolerance), tolerance=tolerance)["type"] == "payment.captured"
    with pytest.raises(paymentflow.WebhookTimestampError):
        verify(EVENT_BODY, header_at(NOW - tolerance - 1), tolerance=tolerance)


def test_the_default_tolerance_is_five_minutes() -> None:
    assert paymentflow.DEFAULT_TOLERANCE_SECONDS == 300

    # Exercised through the default path rather than only asserted as a constant.
    with pytest.raises(paymentflow.WebhookTimestampError):
        verify(EVENT_BODY, header_at(NOW - 301))
    assert verify(EVENT_BODY, header_at(NOW - 299))["type"] == "payment.captured"


def test_the_signature_is_checked_before_the_timestamp() -> None:
    # Otherwise a garbage header reads as "too old", which sends an integrator to look at their
    # clock — and the ordering would leak whether a body was correctly signed.
    with pytest.raises(paymentflow.WebhookSignatureError):
        verify(EVENT_BODY + " ", header_at(NOW - 8000))


@pytest.mark.parametrize(
    "header,expected",
    [
        ("", "No PaymentFlow-Signature header"),
        ("nonsense", "no `t=` timestamp"),
        ("v1=abc", "no `t=` timestamp"),
        (f"t={NOW}", "no `v1=` signature"),
        ("t=,v1=abc", "timestamp is not an integer"),
        ("t=notanumber,v1=abc", "timestamp is not an integer"),
        ("t=0x10,v1=abc", "timestamp is not an integer"),
    ],
)
def test_a_malformed_header_is_rejected_with_a_message_naming_what_is_wrong(
    header: str, expected: str
) -> None:
    with pytest.raises(paymentflow.WebhookSignatureError, match=expected.replace("`", "`")):
        verify(EVENT_BODY, header)


def test_an_empty_timestamp_is_not_read_as_epoch_zero() -> None:
    # `int("")` raises, and a naive `try/except` around it that fell through would turn a
    # malformed header into "fifty-six years of clock skew".
    with pytest.raises(paymentflow.WebhookSignatureError) as raised:
        verify(EVENT_BODY, "t=,v1=abc")
    assert not isinstance(raised.value, paymentflow.WebhookTimestampError)


def test_a_missing_secret_is_refused() -> None:
    with pytest.raises(paymentflow.WebhookSignatureError):
        verify(EVENT_BODY, header_at(NOW), "")


def test_a_negative_tolerance_is_refused() -> None:
    with pytest.raises(paymentflow.WebhookSignatureError):
        verify(EVENT_BODY, header_at(NOW), tolerance=-1)


def test_a_signature_of_the_wrong_length_is_rejected_cleanly() -> None:
    # `hmac.compare_digest` raises on mismatched types, and a truncated `v1` must come back as
    # a verification failure rather than as an exception escaping from inside the SDK.
    with pytest.raises(paymentflow.WebhookSignatureError):
        verify(EVENT_BODY, f"t={NOW},v1=deadbeef")


@pytest.mark.parametrize("body", ["not json at all", "[1,2,3]", '"a string"', '{"id":"evt_1"}', "{}"])
def test_a_verified_body_that_is_not_an_event_envelope_is_a_distinct_error(body: str) -> None:
    with pytest.raises(paymentflow.WebhookPayloadError) as raised:
        verify(body, header_at(NOW, body))
    # Not a signature error: the delivery was authentic, so telling the caller their secret is
    # wrong would send them to fix something that is not broken.
    assert not isinstance(raised.value, paymentflow.WebhookSignatureError)


def test_an_event_carrying_unknown_fields_rides_through_untouched() -> None:
    body = json.dumps(
        {
            "id": "evt_1",
            "object": "event",
            "type": "payment.something_invented_later",
            "data": {"object": {"id": "pay_1"}, "previousAttributes": {"status": "authorized"}},
            "aFieldFromTheFuture": True,
        }
    )

    # New event types and new envelope fields are additive and ship without a new API revision.
    event = verify(body, header_at(NOW, body))
    assert event["type"] == "payment.something_invented_later"
    assert event["aFieldFromTheFuture"] is True
    assert event["data"]["previousAttributes"] == {"status": "authorized"}


def test_every_webhook_error_narrows_from_one_base() -> None:
    errors = [
        paymentflow.WebhookSignatureError("x"),
        paymentflow.WebhookTimestampError("x", timestamp=1, skew_seconds=2),
        paymentflow.WebhookPayloadError("x"),
    ]

    for error in errors:
        assert isinstance(error, paymentflow.WebhookVerificationError)
        assert isinstance(error, paymentflow.PaymentFlowError)
        # There was no request, so there is no status to report.
        assert error.status_code is None
