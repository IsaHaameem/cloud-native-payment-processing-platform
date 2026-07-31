"""Client configuration, resolved once and then frozen (M22.5).

§7.1 fixes the option names and defaults across all four languages, so this is a transcription
of an agreed table rather than a design. What it adds is *validation*: every option is checked
at construction, because a client built with a negative timeout or an empty base URL should
fail on the line that built it, not on the first call — by which point the traceback points at
a payment.

Two idiom differences from the Node SDK, both deliberate. ``timeout`` is **seconds**, because
that is what ``httpx`` and the rest of the Python ecosystem use; Node's is milliseconds for the
same reason. And the options are keyword arguments rather than an options object, because that
is how a Python constructor is spelled.
"""

from __future__ import annotations

import os
import platform
from dataclasses import dataclass
from typing import Final, Optional
from urllib.parse import urlparse

from ._generated.contract import API_VERSION as _API_VERSION
from ._generated.contract import DEFAULT_BASE_URL as _DEFAULT_BASE_URL

__all__ = ["PaymentFlowConfigurationError", "ResolvedConfig", "USER_AGENT", "VERSION"]

#: This package's own version. Kept here so the User-Agent and the public constant agree.
VERSION: Final[str] = "0.1.0"

#: How this SDK identifies itself, on every request.
#:
#: Not decoration: §7.1 notes that this is what makes SDK adoption measurable in the request
#: log M20 already records, which is the only way to answer "how many integrators are on a
#: version with a known bug" without asking them.
USER_AGENT: Final[str] = f"paymentflow-python/{VERSION} python/{platform.python_version()}"

#: The default per-attempt timeout, in seconds.
DEFAULT_TIMEOUT: Final[float] = 30.0

#: How many times a retryable failure is retried, so a call makes at most four attempts.
DEFAULT_MAX_RETRIES: Final[int] = 3


class PaymentFlowConfigurationError(ValueError):
    """Raised when the client is constructed with options it cannot work with.

    A ``ValueError``, because that is what Python calls an argument that is the right type and
    the wrong value, and an integrator's existing ``except ValueError`` should see it.
    """


@dataclass(frozen=True)
class ResolvedConfig:
    """The validated configuration a client holds."""

    api_key: str
    base_url: str
    api_version: str
    timeout: float
    max_retries: int
    user_agent: str


def resolve_config(
    *,
    api_key: Optional[str] = None,
    base_url: Optional[str] = None,
    api_version: Optional[str] = None,
    timeout: Optional[float] = None,
    max_retries: Optional[int] = None,
) -> ResolvedConfig:
    """Applies the defaults and rejects anything that cannot work."""
    resolved_key = api_key if api_key is not None else os.environ.get("PAYMENTFLOW_API_KEY")
    if not resolved_key:
        raise PaymentFlowConfigurationError(
            "No API key. Pass `api_key` to PaymentFlow(), or set PAYMENTFLOW_API_KEY."
        )
    # A key with surrounding whitespace is the commonest way an Authorization header comes out
    # malformed — it survives a copy-paste out of a dashboard or a .env file, and produces a
    # 401 that looks exactly like a revoked key. Rejected rather than stripped, because
    # silently repairing a credential hides the fact that the stored one is wrong.
    if resolved_key.strip() != resolved_key:
        raise PaymentFlowConfigurationError("The API key has leading or trailing whitespace.")

    resolved_url = (base_url if base_url is not None else _DEFAULT_BASE_URL).rstrip("/")
    parsed = urlparse(resolved_url)
    if not parsed.scheme or not parsed.netloc:
        raise PaymentFlowConfigurationError(f"`base_url` is not a valid URL: {resolved_url!r}")

    resolved_version = api_version if api_version is not None else _API_VERSION
    if not resolved_version:
        raise PaymentFlowConfigurationError("`api_version` must not be empty.")

    resolved_timeout = DEFAULT_TIMEOUT if timeout is None else float(timeout)
    if resolved_timeout <= 0:
        raise PaymentFlowConfigurationError("`timeout` must be a positive number of seconds.")

    resolved_retries = DEFAULT_MAX_RETRIES if max_retries is None else max_retries
    # `isinstance(True, int)` is True in Python, so a caller passing `max_retries=True` would
    # otherwise silently get one retry.
    if isinstance(resolved_retries, bool) or not isinstance(resolved_retries, int) or resolved_retries < 0:
        raise PaymentFlowConfigurationError("`max_retries` must be a non-negative integer.")

    return ResolvedConfig(
        api_key=resolved_key,
        base_url=resolved_url,
        api_version=resolved_version,
        timeout=resolved_timeout,
        max_retries=resolved_retries,
        user_agent=USER_AGENT,
    )
