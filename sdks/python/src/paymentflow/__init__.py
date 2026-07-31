"""The PaymentFlow SDK for Python.

What this package is, as of M22.1
---------------------------------
The SDK's identity: which package this is, which dated API revision it was generated
against, which host it talks to by default, and how it identifies itself on the wire. The
client, the resources, the retry loop and the webhook helpers are M22.2 and later.

Why the generated code is not re-exported from here
---------------------------------------------------
``paymentflow._generated`` is written by ``:sdks:shared`` from ``docs/openapi.yaml`` and is
regenerated in full every time the contract moves. Re-exporting it would make this package's
public API a function of a code generator's naming decisions — so a refactor of the
generator, or a schema renamed in a service's Java, would become a breaking change to an SDK
that nobody meant to break. What an integrator may rely on is decided here, deliberately, and
listed in ``__all__``. ``tests/test_public_surface.py`` asserts that rather than trusting it.
"""

from __future__ import annotations

import platform
from typing import Final

from ._generated.contract import API_VERSION as _API_VERSION
from ._generated.contract import DEFAULT_BASE_URL as _DEFAULT_BASE_URL

__all__ = ["API_VERSION", "DEFAULT_BASE_URL", "USER_AGENT", "VERSION"]

#: This package's own version, which moves on its own schedule.
#:
#: Deliberately *not* the API revision. §7.3 pins SDK semver as independent of the dated
#: contract version: an SDK bug fix is a patch release against an unchanged API, and a new API
#: revision does not by itself change anything about this package.
VERSION: Final[str] = "0.1.0"

#: The dated API revision this build was generated against, sent as ``PaymentFlow-Version``.
#:
#: Re-exported under this SDK's own name rather than from the generated module, so that the
#: public surface stays this file's decision.
API_VERSION: Final[str] = _API_VERSION

#: The host the client calls unless a ``base_url`` option overrides it.
DEFAULT_BASE_URL: Final[str] = _DEFAULT_BASE_URL

#: How this SDK identifies itself.
#:
#: Sent on every request. §7.1 notes that it is what makes SDK adoption measurable in the
#: request log M20 already records, which is the only way to know whether an integrator is on
#: a version with a known bug.
USER_AGENT: Final[str] = f"paymentflow-python/{VERSION} python/{platform.python_version()}"
