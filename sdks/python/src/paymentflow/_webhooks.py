"""Webhook signature verification (M22.5).

The Python half of M22.4's ``webhooks.ts``: same specification, same decisions, same shared
vector file. A receiver that does not verify will accept a forged ``payment.captured`` from
anyone who learns the URL, and a receiver that verifies the *body* but ignores the *timestamp*
will accept a genuine delivery replayed forever.

The specification, which is M18.4's and not this SDK's::

    PaymentFlow-Signature: t=1785758400,v1=5f2c…9ab

      signed_payload = "{t}" + "." + "{raw request body}"
      v1             = lowercase hex of HMAC-SHA256(secret, signed_payload)
      secret         = the endpoint's whsec_… value as UTF-8 bytes, prefix included

Several ``v1`` values may appear in one header, comma-separated: that is how the dual-secret
rotation window is expressed on the wire, so a receiver that has already switched and one that
has not both verify. Any match is a match.

Checked against ``notification-service/src/test/resources/signature-vectors/`` — the same five
vectors the platform's own signer, the reference ``verify.js`` and the reference ``verify.py``
are checked against.
"""

from __future__ import annotations

import hmac
import json
import re
import time
from hashlib import sha256
from typing import Any, Dict, Final, List, Mapping, Optional, Tuple, Union

from ._errors import WebhookPayloadError, WebhookSignatureError, WebhookTimestampError

__all__ = ["DEFAULT_TOLERANCE_SECONDS", "SIGNATURE_HEADER", "construct_event", "signature_header_for", "sign_payload"]

#: The header every delivery carries.
SIGNATURE_HEADER: Final[str] = "PaymentFlow-Signature"

#: The default tolerance window, in seconds.
#:
#: Five minutes, which is what the merchant-facing guide recommends. Wide enough to survive
#: ordinary clock drift between two servers, narrow enough that a captured delivery is not
#: replayable for the rest of the afternoon.
DEFAULT_TOLERANCE_SECONDS: Final[int] = 300

_DIGITS = re.compile(r"^\d+$")


def construct_event(
    payload: Union[str, bytes, bytearray],
    signature_header: str,
    secret: str,
    tolerance: Optional[int] = None,
    *,
    now: Optional[int] = None,
) -> Dict[str, Any]:
    """Verifies a delivery and returns its event.

    :param payload: the **raw** request body, exactly as received. See below.
    :param signature_header: the value of the ``PaymentFlow-Signature`` header.
    :param secret: the endpoint's ``whsec_…`` signing secret.
    :param tolerance: seconds of allowed clock skew. Defaults to 300.
    :param now: the current time in epoch seconds. For tests; defaults to the system clock.

    :raises WebhookSignatureError: the header is malformed, or nothing in it matched.
    :raises WebhookTimestampError: the signature is valid and its timestamp is out of window.
    :raises WebhookPayloadError: it verified and is not an event envelope.

    The raw body, and why it has to be raw
    --------------------------------------
    The signature covers the bytes that were sent. ``json.loads`` followed by ``json.dumps``
    does not round-trip them — key order, whitespace and number formatting are all free to
    change — so a caller who passes a re-serialized object will get a signature failure on a
    delivery that was perfectly valid. In Flask that means ``request.get_data()``, not
    ``request.get_json()``; in FastAPI, ``await request.body()``. This is the single most
    common way a correct integration fails.
    """
    tolerance_seconds = DEFAULT_TOLERANCE_SECONDS if tolerance is None else tolerance
    current = int(time.time()) if now is None else now

    if not isinstance(secret, str) or not secret:
        raise WebhookSignatureError("No signing secret. Pass the endpoint's `whsec_…` value.")
    if not isinstance(signature_header, str) or not signature_header:
        raise WebhookSignatureError(f"No {SIGNATURE_HEADER} header on the request.")
    if tolerance_seconds < 0:
        raise WebhookSignatureError("`tolerance` must be a non-negative number of seconds.")

    timestamp, candidates = _parse_header(signature_header)

    # Order matters, and this order is deliberate: the signature is checked *before* the
    # timestamp. Checking the window first would let anyone with the URL and a stopwatch learn
    # whether a body was correctly signed by observing which error came back — and it would
    # report a garbage header as "too old", which sends an integrator to look at their clock.
    body = _as_bytes(payload)
    expected = _sign(secret, timestamp, body)
    if not any(hmac.compare_digest(candidate, expected) for candidate in candidates):
        raise WebhookSignatureError(
            "The signature does not match. Either the secret is wrong, or the payload is not "
            "the raw request body — re-serializing the JSON changes the bytes it covers."
        )

    skew = abs(current - timestamp)
    if skew > tolerance_seconds:
        # Absolute skew, not just "too old". A timestamp far in the future is equally a sign
        # the header was not produced for this delivery at this moment.
        raise WebhookTimestampError(
            f"The delivery's timestamp is {skew}s away from now, outside the "
            f"{tolerance_seconds}s tolerance. This is a replayed delivery, or one of the two "
            "clocks is wrong.",
            timestamp=timestamp,
            skew_seconds=skew,
        )

    return _parse_event(body)


def sign_payload(secret: str, timestamp: int, payload: Union[str, bytes, bytearray]) -> str:
    """Computes the ``v1`` value for a body: hex HMAC-SHA256 over ``"{timestamp}.{body}"``.

    Exported so a caller can build a signed request in their own tests without reimplementing
    the specification from the guide — which is the moment they would get it subtly wrong, and
    then write a test that passes against their own mistake.
    """
    return _sign(secret, timestamp, _as_bytes(payload))


def signature_header_for(secret: str, timestamp: int, payload: Union[str, bytes, bytearray]) -> str:
    """Builds a full header value, for the same reason :func:`sign_payload` is exported."""
    return f"t={timestamp},v1={sign_payload(secret, timestamp, payload)}"


# ── Internals ───────────────────────────────────────────────────────────────────────────


def _as_bytes(payload: Union[str, bytes, bytearray]) -> bytes:
    if isinstance(payload, str):
        return payload.encode("utf-8")
    return bytes(payload)


def _sign(secret: str, timestamp: int, body: bytes) -> str:
    mac = hmac.new(secret.encode("utf-8"), digestmod=sha256)
    mac.update(f"{timestamp}.".encode("utf-8"))
    mac.update(body)
    return mac.hexdigest()


def _parse_header(header: str) -> Tuple[int, List[str]]:
    timestamp: Optional[int] = None
    candidates: List[str] = []

    for element in header.split(","):
        separator = element.find("=")
        if separator < 0:
            continue
        key = element[:separator].strip()
        value = element[separator + 1 :].strip()
        if key == "t":
            # Not `int(value)` alone: that accepts '  12 ' and '+12', and an empty value would
            # otherwise raise a bare ValueError out of the SDK.
            if not _DIGITS.match(value):
                raise WebhookSignatureError(f"The {SIGNATURE_HEADER} header's timestamp is not an integer.")
            timestamp = int(value)
        elif key == "v1":
            if value:
                candidates.append(value)
        # Unknown fields are skipped rather than rejected. A future `v2` alongside `v1` is how
        # this scheme would gain a second algorithm, and a verifier that refused the whole
        # header on sight of an unfamiliar field would break on the day that shipped.

    if timestamp is None:
        raise WebhookSignatureError(f"The {SIGNATURE_HEADER} header has no `t=` timestamp.")
    if not candidates:
        raise WebhookSignatureError(f"The {SIGNATURE_HEADER} header has no `v1=` signature.")
    return timestamp, candidates


def _parse_event(body: bytes) -> Dict[str, Any]:
    try:
        parsed = json.loads(body)
    except ValueError:
        raise WebhookPayloadError("The delivery verified but its body is not JSON.") from None
    if not isinstance(parsed, dict):
        raise WebhookPayloadError("The delivery verified but its body is not a JSON object.")

    event: Mapping[str, Any] = parsed
    # Exactly the three fields the documented return shape promises, and no more. Validating
    # the rest would break §9's forward-compatibility promise the first time a field was added.
    if not isinstance(event.get("id"), str) or not isinstance(event.get("type"), str) or not isinstance(
        event.get("data"), dict
    ):
        raise WebhookPayloadError(
            "The delivery verified but is not an event envelope — `id`, `type` and `data` are required."
        )
    return parsed
