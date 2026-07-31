# PaymentFlow SDKs

The client libraries for the public `/v1` API, and the generator that keeps them honest.

```
sdks/
├── shared/     the code generator (a Gradle module) and the golden fixtures both SDKs test against
├── node/       the TypeScript / Node package
└── python/     the Python package
```

## The shape of the thing

**Generated models, hand-written logic.** `sdks/shared` reads `docs/openapi.yaml` and emits
the contract's data shapes into both packages. Everything an SDK is actually *for* —
automatic idempotency keys that survive a retry, backoff that reads `Retry-After` instead of
guessing, auto-paginating iterators, a typed error hierarchy, webhook signature verification —
is written by hand, per language, because those are exactly what generators do badly.

**One generator, two languages.** Both emitters read a single intermediate representation
(`SdkSpec`), so the two SDKs cannot disagree about what the contract says. They can only
differ in how they spell it. `sdks/shared/fixtures` is that same representation serialized as
language-neutral JSON; both test suites assert against it, which is what turns "the two SDKs
are equivalent" from a claim into a test.

**Generated code is never part of the public API.** `sdks/node/src/generated` and
`sdks/python/src/paymentflow/_generated` are implementation details. What an integrator may
rely on is decided by each package's entry point and asserted by a test — otherwise a refactor
of the generator, or a schema renamed in a service's Java, would silently become a breaking
change to a published package.

## Regenerating

```bash
./gradlew :sdks:shared:generateSdkSources   # rewrite the generated trees and the fixtures
./gradlew :sdks:shared:verifySdkSources     # fail if what is committed is stale
```

The generated files are **committed**, so a reviewer can see what a contract change does to
the SDKs in the same diff that changes the contract. `verifySdkSources` runs as part of
`check`, so `./gradlew build` fails on a stale generated model rather than leaving it for a CI
job that only the SDK toolchains reach.

The generator refuses to guess: a construct it has no rule for fails the task rather than
emitting a permissive type. A generator that quietly says `unknown` about a field the contract
describes precisely is worse than one that stops.

## Building and testing each SDK

Neither package is a Gradle project, and neither toolchain is a prerequisite for building this
monorepo — a contributor with no Node and no Python can still run `./gradlew build`, which is
the constraint D136 established and this milestone keeps.

```bash
cd sdks/node   && npm ci && npm run verify     # type-check, dual build, tests, examples, README snippets
cd sdks/python && pip install -e ".[dev]" && mypy && pytest
```

CI runs both in jobs of their own (`.github/workflows/ci.yml`).

## Status

**M22.1 — the foundation.** The generator, the pipeline, the freshness gate, both package
skeletons, and the cross-language parity harness.

**M22.2 / M22.3 / M22.4 — the Node SDK, finished.** Configuration, native-`fetch` transport,
authentication, automatic idempotency keys reused across retries, the retry engine with
full-jitter backoff and `Retry-After` handling, timeouts, request-id and correlation-id
propagation, the typed error hierarchy mapped from `ApiError.type`, transparent pagination in
both of the platform's page shapes, all eleven resource namespaces covering every one of the 31
published operations, and `webhooks.constructEvent` verified against M18.4's shared signature
vectors. Packaged, documented and exemplified — the README's snippets and the six examples are
compiled against the built declarations on every run. See [`node/README.md`](node/README.md).

**Python is untouched by M22.2, M22.3 and M22.4**, deliberately: the approved sequence is that
Node is finished before Python begins, and it now is. `sdks/python` is still the M22.1 skeleton, and its public
surface is still its identity — `VERSION`, `API_VERSION`, `DEFAULT_BASE_URL`, `USER_AGENT`.

The Python client is M22.5. Neither package is published:
`sdks/node/package.json` is marked `private` and `sdks/python/pyproject.toml` carries
`Private :: Do Not Upload`. Publishing to a public registry is irreversible and effectively
claims a public name, so it needs explicit approval and does not happen by accident.
