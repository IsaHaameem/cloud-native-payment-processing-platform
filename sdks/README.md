# PaymentFlow SDKs

The client libraries for the public `/v1` API, and the generator that keeps them honest.

```
sdks/
├── shared/     the code generator (a Gradle module) and the golden fixtures every SDK tests against
├── node/       the TypeScript / Node package
├── python/     the Python package
└── java/       the Java package — its own Gradle build, not a module of this monorepo
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

**Java is fixture-verified, not generator-emitted (M26).** `sdks/java` has no emitter in
`:sdks:shared` yet. Its generated-equivalent layer — `dev.paymentflow.model.Contract`,
`Operations`, `Vocabularies`, and the response records — is hand-written and asserted against
`sdks/shared/fixtures/*.json` by `ContractParityTest` in the SDK's own suite. That gives the
same guarantee the Node/Python freshness gate does ("this SDK matches the frozen contract")
without expanding the monorepo build's blast radius; a `JavaEmitter` is a possible later
refinement.

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
cd sdks/python && pip install -e ".[dev]" && mypy && pytest  # types, tests, examples, packaging
cd sdks/java   && ./gradlew build              # compile, test, javadoc, jars, compile the examples
```

CI runs each in a job of its own (`.github/workflows/ci.yml`).

## Status

**M22 is complete** (Node, Python). **M26 adds Java** (and, next, Go). No SDK is published —
every package is publish-ready and explicitly marked so. The publishing workflows and the exact secrets each needs are added in the next milestone.

**M22.1 — the foundation.** The generator, the pipeline, the freshness gate, both package
skeletons, and the cross-language parity harness.

**M22.2 – M22.4 — the Node SDK.** Configuration, native-`fetch` transport, authentication,
automatic idempotency keys reused across retries, the retry engine, timeouts, trace-id
propagation, the typed error hierarchy, transparent pagination in both page shapes, all eleven
resource namespaces over every one of the 31 published operations, `webhooks.constructEvent`
against M18.4's shared vectors, packaging verification, a README whose snippets compile, and six
examples. See [`node/README.md`](node/README.md).

**M22.5 – M22.7 — the Python SDK, and parity.** The same client in Python: same options, same
defaults, same retry rules, same backoff constants, same tolerance window, same error
classification, same page shapes — spelled the way Python spells things. Plus wheel and sdist
verification that installs into a clean interpreter, and
`SdkParityTest` in `sdks/shared`, which compares the two source trees on every
`./gradlew build` so that a divergence fails rather than being noticed later. See
[`python/README.md`](python/README.md).

Three things differ between the two, all forced by Python and all listed in
`SdkParityTest`: `PermissionDeniedError` rather than `PermissionError` (a builtin),
`delete()` rather than `del()` (a keyword), and seconds rather than milliseconds.

The **async Python client §7.2 calls for is not built**: `async`/`await` colours every
function it touches, so it means a second transport and a second copy of all eleven namespaces.
It deserves its own sub-milestone rather than being rushed in beside the first.

Neither package is published: `sdks/node/package.json` is marked `private` and
`sdks/python/pyproject.toml` carries `Private :: Do Not Upload`. Publishing to a public
registry is irreversible and effectively claims a public name, so it needs explicit approval and
does not happen by accident.
