# paymentflow — the PaymentFlow SDK for Python

The official client for the PaymentFlow payments API.

> **M22.1.** This package currently exposes its own identity and nothing else — the client,
> the resources, the retry loop and the webhook helpers are M22.2 onwards. It is not
> published; see [Status](#status).

## Requirements

- Python 3.9 or newer. Deliberately not the newest: an SDK runs in its users' environments,
  not in ours.
- One runtime dependency, [`httpx`](https://www.python-httpx.org/). A payments SDK that drags
  in a transitive dependency tree is a supply-chain liability for every integrator, so the
  dependency list is a design constraint rather than a preference.

## What is here today

```python
import paymentflow

paymentflow.VERSION           # this package's version, on its own semver track
paymentflow.API_VERSION       # the dated API revision this build was generated against
paymentflow.DEFAULT_BASE_URL  # the host the client will call unless told otherwise
paymentflow.USER_AGENT        # how this SDK identifies itself in the request log
```

`API_VERSION` is not `VERSION`. The API is versioned by date and the SDK by semver, and they
move for different reasons: a bug fix here is a patch release against an unchanged contract,
and a new contract revision changes nothing about this package by itself.

## Typing

The package ships `py.typed`, so a consumer's type checker uses these annotations rather than
treating every import as `Any`. `mypy --strict` runs over the whole package — including the
generated modules — in CI, because a `py.typed` marker on annotations nobody checked is a
false promise.

Models are `TypedDict`s rather than dataclasses, and that is a correctness decision rather
than a stylistic one. The platform's contract says additive changes ship unversioned and
clients must tolerate fields they have never heard of; a dataclass constructor rejecting an
unknown keyword would break every integrator the first time a field was added. A `TypedDict`
is a plain `dict` at runtime, so an unknown key costs nothing and is still there for a caller
who wants it, while a type checker still sees every documented field.

## Development

```bash
python -m venv .venv && . .venv/bin/activate   # or .venv\Scripts\activate on Windows
pip install -e ".[dev]"
mypy
pytest
```

`src/paymentflow/_generated` is written by `./gradlew :sdks:shared:generateSdkSources` from
`docs/openapi.yaml` and is not edited by hand — `./gradlew :sdks:shared:verifySdkSources`
fails the build when what is committed no longer matches the contract.

## Status

Not published to PyPI. `pyproject.toml` carries the `Private :: Do Not Upload` classifier,
which PyPI itself rejects an upload on. Publishing to a public registry is irreversible and
effectively claims a public name, so it needs explicit approval rather than a passing build.
