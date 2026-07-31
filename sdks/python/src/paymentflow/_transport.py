"""The request pipeline: one HTTP attempt, wrapped in the retry loop that makes it safe (M22.5).

Behaviourally identical to the Node SDK's ``transport.ts`` — same decisions, same order, same
guarantees — because §7.1 requires the two to be the same client in two languages. Only the
spelling is Python's.

The one property this file exists to hold
-----------------------------------------
A retried mutation must reuse the ``Idempotency-Key`` of the attempt it is retrying. The key is
therefore generated **once per logical call**, before the loop, and never inside it. §7.1 calls
this the SDK's single most important correctness property, and it is: a key regenerated per
attempt turns "the platform deduplicated your retry" into "you charged the customer twice", and
it does so only under the network conditions that make retries happen.

What is safe to retry
---------------------
Not "429 and 5xx". A response never arriving does not mean the request never arrived, so
replaying a call the platform does not deduplicate can perform it twice. This SDK retries only
what it can replay safely (D169): ``GET`` and ``DELETE``, which HTTP defines as idempotent, and
any request carrying an ``Idempotency-Key``. ``POST /v1/webhook_endpoints`` is retried by
neither rule, because a second attempt would create a second endpoint.
"""

from __future__ import annotations

import json
import random
import time
import uuid
from urllib.parse import quote
from dataclasses import dataclass, field
from typing import Any, Dict, List, Mapping, Optional, Sequence, Tuple

import httpx

from ._config import ResolvedConfig
from ._errors import ApiConnectionError, PaymentFlowError, error_from_response
from ._generated.operations import OperationDescriptor

__all__ = ["RateLimitMeta", "RequestOptions", "ResponseMeta", "Transport"]

#: The header the platform deduplicates mutations on.
_IDEMPOTENCY_HEADER = "Idempotency-Key"

#: How long a computed backoff may grow to, and the unit the exponential is built from.
_BASE_BACKOFF_SECONDS = 0.5
_MAX_BACKOFF_SECONDS = 8.0

#: The longest ``Retry-After`` this SDK will wait out rather than surrender to.
#:
#: ``Retry-After`` is authoritative, but for ``DAILY_QUOTA_EXCEEDED`` it is the time remaining
#: until 00:00 UTC — up to twenty-four hours. Sleeping that inside a caller's request handler is
#: not "honouring the header", it is a hang. Past this bound the SDK stops retrying and raises a
#: ``RateLimitError`` carrying ``retry_after_seconds``, so the caller can schedule the work
#: instead of blocking on it (D168).
_MAX_HONOURED_RETRY_AFTER_SECONDS = 60.0


@dataclass(frozen=True)
class RequestOptions:
    """Per-call options every resource method accepts."""

    #: The idempotency key to send, for operations that take one.
    #:
    #: Supply your own when the retry must survive your *process* restarting, not just this
    #: SDK's loop — a key generated here is lost with the call that held it.
    idempotency_key: Optional[str] = None

    #: Your own identifier for this operation, sent as ``X-Correlation-Id`` and echoed back.
    correlation_id: Optional[str] = None

    #: Overrides the client's timeout for this call only, in seconds.
    timeout: Optional[float] = None

    #: Overrides the client's retry budget for this call only.
    max_retries: Optional[int] = None


@dataclass(frozen=True)
class RateLimitMeta:
    """The daily quota, as reported on a measured response."""

    limit: Optional[int] = None
    remaining: Optional[int] = None
    #: Seconds until the daily quota window resets, at 00:00 UTC.
    #:
    #: Telemetry, not a retry hint (D167). It describes the daily window even on a successful
    #: response, so treating it as "wait this long" would idle a healthy client until midnight.
    reset_seconds: Optional[int] = None


@dataclass(frozen=True)
class ResponseMeta:
    """Everything a caller can learn about the exchange, beyond the body."""

    status_code: int
    #: Identifies this one HTTP call, and keys the matching ``GET /v1/request_logs`` row.
    request_id: Optional[str] = None
    #: Identifies the whole distributed trace.
    correlation_id: Optional[str] = None
    #: The dated API revision that answered. Absent when refused at the edge.
    api_version: Optional[str] = None
    #: ``True`` when the revision that answered has been superseded.
    deprecated: bool = False
    #: Daily quota telemetry, when the response was measured against an allowance.
    rate_limit: Optional[RateLimitMeta] = None
    #: How many HTTP attempts this call took. 1 when it succeeded first time.
    attempts: int = 1


@dataclass
class _Attempt:
    """The outcome of one HTTP attempt."""

    meta: Optional[ResponseMeta] = None
    body: Any = None
    error: Optional[PaymentFlowError] = None
    retryable: bool = False
    retry_after_seconds: Optional[float] = None

    @property
    def succeeded(self) -> bool:
        return self.error is None


@dataclass
class Result:
    """A parsed response and what the transport learned alongside it."""

    data: Any
    meta: ResponseMeta


@dataclass(frozen=True)
class RequestSpec:
    """What a resource method hands the transport. Assembled from a generated descriptor."""

    operation: OperationDescriptor
    #: Values for the ``{...}`` placeholders in the operation's path template.
    path: Mapping[str, str] = field(default_factory=dict)
    #: Query parameters, in wire spelling. ``None`` values are omitted, not sent empty.
    query: Mapping[str, Any] = field(default_factory=dict)
    #: The JSON request body, for operations that take one.
    body: Optional[Mapping[str, Any]] = None
    options: Optional[RequestOptions] = None


class Transport:
    """Turns a :class:`RequestSpec` into an HTTP exchange, retrying what is safe to retry."""

    def __init__(self, config: ResolvedConfig, http_client: Optional[httpx.Client] = None) -> None:
        self._config = config
        # Injectable for tests and for proxy configuration — the two reasons §7.1 lists, and
        # what lets this package stay testable without a live server. A client supplied here is
        # the caller's to close; one created here is closed by `PaymentFlow.close()`.
        self._owns_client = http_client is None
        self._http = http_client if http_client is not None else httpx.Client()

    def close(self) -> None:
        if self._owns_client:
            self._http.close()

    def request(self, spec: RequestSpec) -> Result:
        options = spec.options or RequestOptions()
        url = self._build_url(spec)
        params = self._build_params(spec)
        headers = self._build_headers(spec, options)
        max_retries = options.max_retries if options.max_retries is not None else self._config.max_retries
        timeout = options.timeout if options.timeout is not None else self._config.timeout

        content: Optional[bytes] = None
        if spec.operation["has_request_body"] and spec.body is not None:
            content = json.dumps(spec.body).encode("utf-8")

        replayable = (
            spec.operation["method"] in ("GET", "DELETE") or _IDEMPOTENCY_HEADER in headers
        )

        attempt_number = 0
        while True:
            attempt_number += 1
            attempt = self._attempt(
                method=spec.operation["method"],
                url=url,
                params=params,
                headers=headers,
                content=content,
                timeout=timeout,
                attempt_number=attempt_number,
            )

            if attempt.succeeded:
                assert attempt.meta is not None  # narrowed by `succeeded`
                return Result(data=attempt.body, meta=attempt.meta)

            remaining = max_retries - (attempt_number - 1)
            delay = _retry_delay(attempt, attempt_number) if remaining > 0 and replayable else None
            if delay is None:
                assert attempt.error is not None
                raise attempt.error
            time.sleep(delay)

    # ── One attempt ─────────────────────────────────────────────────────────────────────

    def _attempt(
        self,
        *,
        method: str,
        url: str,
        params: Sequence[Tuple[str, str]],
        headers: Mapping[str, str],
        content: Optional[bytes],
        timeout: float,
        attempt_number: int,
    ) -> _Attempt:
        try:
            response = self._http.request(
                method, url, params=list(params), headers=dict(headers), content=content, timeout=timeout
            )
        except httpx.TimeoutException:
            return _Attempt(
                # A timeout is retryable: it is the commonest transient failure there is, and
                # the idempotency key is exactly what makes replaying one safe.
                retryable=True,
                error=ApiConnectionError(f"The request timed out after {timeout}s.", attempts=attempt_number),
            )
        except httpx.HTTPError as exc:
            return _Attempt(
                retryable=True,
                error=ApiConnectionError(f"The request could not be completed: {exc}", attempts=attempt_number),
            )

        return _read_response(response, attempt_number)

    # ── Building the request ────────────────────────────────────────────────────────────

    def _build_url(self, spec: RequestSpec) -> str:
        path = spec.operation["path"]
        for name, value in spec.path.items():
            path = path.replace("{" + name + "}", _quote(value))
        if "{" in path:
            missing = path[path.index("{") + 1 : path.index("}")]
            raise PaymentFlowError(f"`{missing}` is required by {spec.operation['id']} and was not supplied.")
        return f"{self._config.base_url}{path}"

    def _build_params(self, spec: RequestSpec) -> List[Tuple[str, str]]:
        allowed = spec.operation["query_parameters"]
        params: List[Tuple[str, str]] = []

        for name, value in spec.query.items():
            if value is None:
                continue
            # The descriptor is the contract's own list. Checking against it turns a mistyped
            # filter — which the API would silently ignore, returning a page that looks right
            # and is not — into an error on the line that made it.
            if name not in allowed:
                accepted = ", ".join(allowed) or "(none)"
                raise PaymentFlowError(
                    f"`{name}` is not a query parameter of {spec.operation['id']}. It accepts: {accepted}."
                )
            # Encoded from the shape of the value, which is what the document's styles come to:
            # a sequence repeats the name (`sort=a&sort=b`), and a mapping is `deepObject`
            # (`metadata[orderId]=A-1234`). The generator refuses to emit an object query
            # parameter declared any other style, so this rule cannot quietly stop matching.
            if isinstance(value, Mapping):
                for key, nested in value.items():
                    if nested is not None:
                        params.append((f"{name}[{key}]", _to_query_value(nested)))
            elif isinstance(value, (list, tuple)):
                for element in value:
                    params.append((name, _to_query_value(element)))
            else:
                params.append((name, _to_query_value(value)))

        return params

    def _build_headers(self, spec: RequestSpec, options: RequestOptions) -> Dict[str, str]:
        headers: Dict[str, str] = {
            "Authorization": f"Bearer {self._config.api_key}",
            "Accept": "application/json",
            "PaymentFlow-Version": self._config.api_version,
            "User-Agent": self._config.user_agent,
        }
        if spec.operation["has_request_body"] and spec.body is not None:
            headers["Content-Type"] = "application/json"
        if options.correlation_id is not None:
            headers["X-Correlation-Id"] = options.correlation_id

        # Read from the generated descriptor, never from a list kept here: a hand-maintained
        # copy of "which operations need a key" keeps answering the old question after the
        # contract moves, and the failure mode is a duplicated charge.
        if _IDEMPOTENCY_HEADER in spec.operation["required_headers"]:
            headers[_IDEMPOTENCY_HEADER] = options.idempotency_key or str(uuid.uuid4())
        elif options.idempotency_key is not None:
            # The caller asked for one on an operation the contract does not require it for.
            # Sent rather than dropped: they know something about their own retry story that
            # the contract does not.
            headers[_IDEMPOTENCY_HEADER] = options.idempotency_key
        return headers


# ── Reading a response ──────────────────────────────────────────────────────────────────


def _read_response(response: httpx.Response, attempt_number: int) -> _Attempt:
    retry_after = _float_header(response, "Retry-After")
    meta = ResponseMeta(
        status_code=response.status_code,
        request_id=response.headers.get("X-Request-Id"),
        correlation_id=response.headers.get("X-Correlation-Id"),
        api_version=response.headers.get("PaymentFlow-Version"),
        deprecated="Deprecation" in response.headers,
        rate_limit=_rate_limit_meta(response),
        attempts=attempt_number,
    )

    raw = b"" if response.status_code == 204 else response.content
    body: Any = None
    unreadable = False
    if raw:
        try:
            body = json.loads(raw)
        except ValueError:
            unreadable = True

    if response.is_success:
        if unreadable:
            return _Attempt(
                # The platform said 2xx and sent something this SDK cannot read. Retrying is
                # the right guess: the realistic cause is an intermediary that truncated it.
                retryable=True,
                error=error_from_response(
                    {"message": "The API returned a success status with a body that is not JSON."},
                    status_code=response.status_code,
                    request_id=meta.request_id,
                    correlation_id=meta.correlation_id,
                    attempts=attempt_number,
                ),
            )
        # Unknown fields ride along untouched — §9's forward-compatibility promise is kept by
        # not validating here, which is also why there is no schema check on this path.
        return _Attempt(meta=meta, body=body)

    return _Attempt(
        retryable=_is_retryable_status(response.status_code),
        retry_after_seconds=retry_after,
        error=error_from_response(
            body,
            status_code=response.status_code,
            request_id=meta.request_id,
            correlation_id=meta.correlation_id,
            retry_after_seconds=retry_after,
            attempts=attempt_number,
        ),
    )


def _is_retryable_status(status: int) -> bool:
    """429 and 5xx, and nothing else.

    Every other 4xx describes a request that will be rejected identically however many times it
    is sent; retrying one only delays the error the caller needs to see. 501 is excluded for the
    same reason — an endpoint that is not implemented will not be by the third try.
    """
    return status == 429 or (status >= 500 and status != 501)


def _rate_limit_meta(response: httpx.Response) -> Optional[RateLimitMeta]:
    limit = _int_header(response, "RateLimit-Limit")
    remaining = _int_header(response, "RateLimit-Remaining")
    reset = _int_header(response, "RateLimit-Reset")
    if limit is None and remaining is None and reset is None:
        return None
    return RateLimitMeta(limit=limit, remaining=remaining, reset_seconds=reset)


def _int_header(response: httpx.Response, name: str) -> Optional[int]:
    raw = response.headers.get(name)
    if raw is None:
        return None
    try:
        return int(raw)
    except ValueError:
        return None


def _float_header(response: httpx.Response, name: str) -> Optional[float]:
    raw = response.headers.get(name)
    if raw is None:
        return None
    try:
        return float(raw)
    except ValueError:
        return None


# ── Backoff ─────────────────────────────────────────────────────────────────────────────


def _retry_delay(attempt: _Attempt, attempt_number: int) -> Optional[float]:
    """How long to wait before the next attempt, or ``None`` to stop retrying.

    ``Retry-After`` wins over anything computed here, because it is the interval the platform
    will actually accept the request again rather than a guess about when it might. The
    exception is an interval so long that waiting it out would be indistinguishable from
    hanging — see D168.
    """
    if not attempt.retryable:
        return None

    if attempt.retry_after_seconds is not None:
        requested = attempt.retry_after_seconds
        return None if requested > _MAX_HONOURED_RETRY_AFTER_SECONDS else requested

    # Full jitter: uniform over [0, ceiling) rather than ceiling/2 + jitter. With several
    # clients recovering from the same outage, the half-fixed form reconverges them into the
    # same synchronised wave that caused it; full jitter is what actually spreads the load.
    # `2.0 **`, not `2 **`: `int ** int` is typed `Any` because a negative exponent yields a
    # float, and an `Any` here would silently disable checking of everything it touches.
    ceiling = min(_MAX_BACKOFF_SECONDS, _BASE_BACKOFF_SECONDS * (2.0 ** (attempt_number - 1)))
    return random.random() * ceiling


def _to_query_value(value: Any) -> str:
    # `str(True)` is "True", which the platform's boolean binding does not accept.
    if isinstance(value, bool):
        return "true" if value else "false"
    return str(value)


def _quote(value: str) -> str:
    if value == "":
        raise PaymentFlowError("A path parameter cannot be empty.")
    return quote(str(value), safe="")
