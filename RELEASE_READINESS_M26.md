# M26 — release-readiness report

Branch `feat/m26-sdks`, off `main`. **Nothing has been pushed. Nothing has been published.**

---

## 1. Current branch

`feat/m26-sdks` (local only). `main` and `feat/m15-api-key-auth` exist on `origin`
(`github.com/IsaHaameem/cloud-native-payment-processing-platform`); `feat/m26-sdks` does not.

## 2. Commits created (this branch, on top of `main` @ `60e04b8`)

| Hash | Title |
|---|---|
| `d99ed13` | `feat(m26): the Java SDK — a dependency-free client the contract tests keep honest` |
| `b3b4fc6` | `feat(m26): the Go SDK — idiomatic, dependency-free, contract-tested` |
| `256e54b` | `feat(m26): SDK publishing — every package publish-ready, none published` |

Three logical commits: Java SDK, Go SDK, publishing+versioning+CI. No squashing of unrelated
work.

## 3. Files changed

### Committed on this branch

**`d99ed13` — Java SDK (90 files, all new under `sdks/java/`)**
- `sdks/java/` standalone Gradle build: `settings.gradle.kts`, `build.gradle.kts`,
  `gradle.properties`, wrapper, `.gitignore`, `README.md`, `CHANGELOG.md`
- `src/main/java/dev/paymentflow/` — `PaymentFlow`, `PaymentFlowOptions`, `RequestOptions`,
  `ResponseMeta`, `RateLimitMeta`, `Page`/`CursorPage`/`OffsetPage`, `Webhooks`, `WebhookEvent`,
  12 exception types, `internal/` (`ClientConfig`, `Transport`, `Json`, `Errors`, `Paginator`,
  `RequestSpec`, `PageFetch`), `resources/` (11 services + `Resource`),
  `model/` (19 response records + `Contract`, `Operations`, `Vocabularies`)
- `src/test/java/dev/paymentflow/` — 8 test classes (30 tests)
- `examples/` — 6 compiled examples
- `sdks/README.md` — Java added to the tree, build table, "fixture-verified" note

**`b3b4fc6` — Go SDK (30 files, all new under `sdks/go/`)**
- `go.mod`, `doc.go`, `contract.go`, `version.go`, `vocabularies.go`, `operations.go`,
  `models.go`, `errors.go`, `client.go`, `transport.go`, `pagination.go`, `resource.go`,
  `webhooks.go`, 8 resource files, `README.md`, `CHANGELOG.md`
- `*_test.go` — 5 test files (29 tests) + `example_test.go` (6 examples)
- `examples/quickstart/`, `examples/webhook-receiver/`
- `sdks/README.md` — Go added

**`256e54b` — publishing (11 files)**
- new: `.github/workflows/sdk-release-{node,python,java,go}.yml`, `sdks/PUBLISHING.md`,
  `sdks/node/CHANGELOG.md`, `sdks/python/CHANGELOG.md`
- edited: `.github/workflows/ci.yml` (sdks matrix `[node,python]` → `[node,python,java,go]`),
  `sdks/java/build.gradle.kts` (vanniktech Central Portal plugin),
  `sdks/node/package.json` (release metadata), `sdks/python/pyproject.toml` (urls/authors/classifiers)

### Not committed — working-tree changes I made this pass

| File | Change | Why not committed |
|---|---|---|
| `developer-portal/src/lib/integration/stacks.ts` | + `go` stack, + `published`/`repoDir`, `sampleLang` union +java/+go, Java Maven coord | sits on top of the **uncommitted pre-existing portal build** |
| `developer-portal/src/lib/integration/prompt.ts` | SDK line branches on `published`: "publish-ready, not published, build from repoDir, prefer REST", real coords, API version + retry rules | same |
| `developer-portal/src/app/(app)/developers/quickstart/quickstart-client.tsx` | +Java +Go languages/samples/filenames; truthful install block; step 3 "Get the SDK" with publish-ready notice | same |
| `developer-portal/src/app/(app)/developers/sdks/page.tsx` | +Java +Go samples (2×2 grid); header + notice → `sdks/PUBLISHING.md` | same |
| `.dockerignore` | + `mock-project/` | fixes a **pre-existing** `DockerBuildContextConsistencyTest` failure caused by the uncommitted Knitt demo; belongs with the Project-3 commit |
| `AUDIT_M26.md`, `MANUAL_TEST_PLAN.md`, `RELEASE_READINESS_M26.md` | new report files | your call whether to keep them in the repo |

### Pre-existing uncommitted work (NOT mine — do not fold into the SDK commits)

The whole `developer-portal` build past M23.6, `agentic-commerce-service/`, `e2e/`,
`mock-project/knitt/`, and deltas to `.env.example`, `Dockerfile`, `docker-compose.yml`,
`settings.gradle.kts`, `ci.yml` (the `agentic-commerce-service` image leg), plus
`Design palette and scope.zip` (a build artefact — should be deleted or git-ignored, not
committed).

## 4. Validation results

| Check | Result |
|---|---|
| **Node SDK** — `npm run verify` | ✅ 108 tests, typecheck, dual ESM/CJS build, examples, README snippets |
| **Node SDK** — `npm pack` | ✅ dist-only, 156 files, 95 kB |
| **Python SDK** — `pytest` | ✅ 193 tests |
| **Python SDK** — `mypy` | ✅ no issues, 34 files |
| **Python SDK** — `python -m build` + `twine check` | ✅ wheel + sdist, both PASS |
| **Java SDK** — `./gradlew build` | ✅ 30 tests, javadoc + sources jars, examples compile |
| **Java SDK** — `./gradlew publishToMavenLocal` | ✅ jar + sources + javadoc + Central-shaped POM + module metadata |
| **Go SDK** — `go build ./... && go vet ./... && go test ./...` | ✅ 29 tests (Go 1.23.12) |
| **Go SDK** — `gofmt -l .` / `go mod tidy` | ✅ clean / no-op (zero deps) |
| **Shared codegen** — `:sdks:shared:test --rerun-tasks` | ✅ `SdkCodegenTest` + `SdkParityTest` green against the edited manifests |
| **Developer portal** — `npm run verify` | ✅ tsc + eslint + prettier + vitest + `next build`, after the Milestone D edits |
| **Release workflow YAML** — 5 files | ✅ all parse |
| **Backend** — `./gradlew clean build --no-build-cache` | ✅ `BUILD SUCCESSFUL in 12m 30s` — see below |
| **Secret scan** | ✅ see §Security findings |

### Backend build

`./gradlew clean build --no-daemon --no-build-cache --no-configuration-cache` was run in full
three times.

- Runs 1–2 hit `org.testcontainers.containers.ContainerFetchException` →
  `DockerClientProviderStrategy` and `Connection refused` on `postgres:5432` — Testcontainers
  under parallel load could not obtain a Docker client and a worker JVM then hung. **Zero
  assertion failures** in any run.
- After `docker system prune` and killing the leftover JVMs, **run 3 passed clean:
  `BUILD SUCCESSFUL in 12m 30s`, 118 tasks, every unit and integration (`*IntegrationTest`)
  suite green.** The earlier failures were the machine's documented Docker/Testcontainers
  flakiness (memory `m15-stability-hardening`), not an M26 regression — M26 changes no backend
  Java, Spring config, or migration.
- `DockerBuildContextConsistencyTest` — the one backend test that could have regressed from
  adding `sdks/java`/`sdks/go` — passes, after `.dockerignore` gained `mock-project/` (a
  pre-existing failure from the uncommitted Knitt demo, not from M26).

### E2E / OpenAI / Knitt — not run this pass

`e2e/` (portal, portal-integration, openai-roundtrip, openai-failure, bootstrap-knitt,
bootstrap-merchant, smoke, session) and `mock-project/knitt/scripts/verify-payment.mjs`
need a running stack and, for the OpenAI ones, `OPENAI_API_KEY` in the environment. The
`ScriptedLlmClient` fallback is **verified by code inspection** — `LlmClientConfig.llmClient`
returns `scriptedLlmClient` when `!llm.isConfigured()`, with a startup WARN log, and again for
an unknown provider. Live E2E is in the manual checklist (`MANUAL_TEST_PLAN.md`).

## 5. Remaining issues

| Issue | Severity | Owner |
|---|---|---|
| Backend integration tests red locally (Testcontainers env, not code) | MEDIUM | re-run / CI |
| `project_3_context.md` + `CLAUDE_CONTEXT.md` status tables stale ("M24/M25/M26 not started", "no Java SDK") | MEDIUM | doc update |
| Large pre-existing uncommitted work (portal build, agentic service, Knitt, e2e) not committed | MEDIUM | your commit series |
| `Design palette and scope.zip` sitting in the repo root | LOW | delete or git-ignore |
| No `gitleaks`/secret-scan step in CI | LOW | optional CI add |
| Async Python client | — | INTENTIONALLY DEFERRED (D181) |
| M23.9 responsive/axe automation | — | INTENTIONALLY DEFERRED (hardening) |

## 6. Exact GitHub setup required (before any SDK release)

**Environments** (repo → Settings → Environments), each with you as a required reviewer:
`npm`, `pypi`, `maven-central`.

**Repository secrets** (repo → Settings → Secrets and variables → Actions):

| Secret | For | Needed |
|---|---|---|
| `MAVEN_CENTRAL_USERNAME` | Java | always — Central Portal user-token name |
| `MAVEN_CENTRAL_PASSWORD` | Java | always — Central Portal user-token value |
| `SIGNING_KEY` | Java | always — ASCII-armoured PGP private key block |
| `SIGNING_PASSWORD` | Java | always — its passphrase |
| `NPM_TOKEN` | Node | only if you skip npm trusted publishing |
| `PYPI_API_TOKEN` | Python | only if you skip PyPI Trusted Publishing |

Preferred path uses **no tokens** — see §7.

## 7. Exact registry setup required

| Registry | Account | Setup |
|---|---|---|
| **npm** | `muhammadisahaameem` (you have it) | Create the `paymentflow` package (available — 404 today), then add a **trusted publisher**: repo `IsaHaameem/cloud-native-payment-processing-platform`, workflow `sdk-release-node.yml`, environment `npm`. |
| **PyPI** | `isahameem` (you have it) | Add a **pending publisher** for project `paymentflow` (available): owner `IsaHaameem`, repo `cloud-native-payment-processing-platform`, workflow `sdk-release-python.yml`, environment `pypi`. |
| **Maven Central** | none yet | Register at central.sonatype.com; **verify a namespace** — either `dev.paymentflow` via a DNS TXT record on `paymentflow.dev`, or switch to `io.github.isahaameem` (verified by the GitHub account; then change `group`/`coordinates` in `sdks/java/build.gradle.kts`). Generate a user token. Create + publish a PGP key. |
| **Go proxy** | none | Nothing. A tag `sdks/go/v0.1.0` is the release. |

Full detail in `sdks/PUBLISHING.md`.

## 8. What is publish-ready

**All four SDKs.** Each builds, tests green, produces a real distribution artefact, and has a
tag-triggered release workflow behind an approval gate:

| SDK | Package / module | Version | Artefact validated |
|---|---|---|---|
| Node | `paymentflow` (npm) | 0.1.0 | `npm pack` — dist only |
| Python | `paymentflow` (PyPI) | 0.1.0 | `python -m build` + `twine check` PASS |
| Java | `dev.paymentflow:paymentflow` (Maven Central) | 0.1.0 | `publishToMavenLocal` — full POM + jars |
| Go | `github.com/IsaHaameem/cloud-native-payment-processing-platform/sdks/go` | 0.1.0 | `go build/vet/test`, `go mod tidy` no-op |

Tag scheme: `sdks/<lang>/v0.1.0`.

Each package carries a marker its registry refuses (`"private": true`,
`Private :: Do Not Upload`, no signing key) so a release cannot happen by an accidental tag —
removing the marker is a deliberate, documented step.

## 9. What is actually published

**Nothing.** No package exists on npm, PyPI, or Maven Central; no `sdks/go/*` tag exists. This
line will only say otherwise once verified against the registry itself.

## 10. What still requires your manual action

1. **Decide whether to claim `paymentflow` publicly** on npm and PyPI (both names are free
   today — verified). If yes, do the §7 registry setup.
2. **Create the three GitHub environments and the Java secrets** (§6).
3. **Commit the pre-existing work** — the portal build, `agentic-commerce-service`, `e2e/`,
   `mock-project/knitt/`, and the associated `Dockerfile`/`compose`/`settings`/`ci.yml`/
   `.env.example`/`.dockerignore` deltas — as its own commit series. My Milestone-D portal
   edits and the `.dockerignore` fix are in that working tree, ready to go with it.
4. **Delete or git-ignore `Design palette and scope.zip`.**
5. **Update the stale docs** — `project_3_context.md` status table + the AD-4.3 "no Java SDK"
   line; `CLAUDE_CONTEXT.md` M23 table + remaining-roadmap.
6. **Re-run the backend build** (or check CI) to clear the Testcontainers flake.
7. **Run the E2E suites** with a running stack and `OPENAI_API_KEY` set — `MANUAL_TEST_PLAN.md`
   §C–S.
8. **When ready:** `git push -u origin feat/m26-sdks`, open a PR into `main`. (I have **not**
   pushed.)

---

## Security findings

| Check | Result |
|---|---|
| `.env` tracked? | **No** — git-ignored (`*.env`), not in `git ls-files`. Local `.env` has real credentials and is correctly ignored. |
| Secret literals in tracked files | **None** — scanned for AWS keys, PEM private keys, npm/PyPI/GitHub/Slack tokens, Anthropic/OpenAI keys across the whole tracked tree. |
| Secret literals in the M26 commit range | **None** — only GitHub Actions secret *references* (`${{ secrets.* }}`) and Gradle property *names*. |
| `.env.example` | Placeholders only — empty values or `dev-only-insecure-…-change-me`. |
| SDK examples (all 4) | No credential literals — every example reads from the environment. |
| `developer-portal/.next/static` | Clean — no key/secret patterns. |
| Release workflows | OIDC/trusted-publishing preferred; every credential is a repository secret or an OIDC token; nothing long-lived in the repo. Java signing runs only when a key is supplied. |
| Live-mode money claim | The AI prompt and the SDKs page state that live mode still settles against a *simulated* acquirer — no real funds move in either mode. Keep that wording anywhere a "live" claim appears. |

## Completion snapshot

| Area | Completion | Note |
|---|---|---|
| Overall (this pass's scope: M26) | **~95%** | code + infra + docs done; actual publish is your deliberate step |
| Backend | ~100% impl | unchanged this pass; integration-test *verification* blocked by Testcontainers env |
| Frontend | ~95% impl | past M23.6 in the tree, uncommitted; M23.9 hardening deferred |
| Agentic commerce | ~100% impl | `agentic-commerce-service` complete, uncommitted; docs say "not started" |
| SDK — Node | 100% | publish-ready, unpublished |
| SDK — Python | 100% (sync) | publish-ready, unpublished; async deferred |
| SDK — Java | 100% | built this pass; publish-ready, unpublished |
| SDK — Go | 100% | built this pass; publish-ready, untagged |
