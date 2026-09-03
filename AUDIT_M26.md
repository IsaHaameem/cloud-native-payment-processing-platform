# PaymentFlow — full project audit (M26 SDK productization pass)

Date: 2026-08-30. Method: every claim below was cross-checked against the working tree and,
where practical, against a build or test run — not taken from a milestone report.

## The one framing that explains most of the table

**The context documents lag the working tree by a large margin.** `CLAUDE_CONTEXT.md` (last
substantive update 2026-08-10) still describes M23 as "in progress, M23.7 next" and M24–M26 as
"remaining". `project_3_context.md` (2026-08-30) still says "M24 Portal part 2 · M25 Docs site
· M26 Java/Go SDKs | ⬜ not started" and "there is no Java SDK (M26 is unbuilt)". In fact the
working tree contains, uncommitted on `main` before this branch:

- the **agentic-commerce-service** (Project 3) — 113 main + 27 test Java files, in
  `settings.gradle.kts`, with its own migrations, LLM layer, policy engine, approval flow, and
  web API;
- the **developer-portal** built out well past M23.6 — payment detail, refunds, balance,
  analytics, events, logs, sandbox, webhooks, the AI-integration prompt, the quickstart, the
  SDKs page, and a marketing site (`platform`, `pricing`, `security`, `docs`, `contact`,
  `developers`, `agentic-commerce`);
- an **e2e/** harness (9 scripts: OpenAI roundtrip + failure, portal, portal-integration,
  knitt bootstrap, merchant bootstrap, smoke, session);
- **mock-project/knitt** — a complete fictional-merchant demo (storefront + AI assistant +
  server integration + a payment-verification script).

This pass (M26) adds the **Java and Go SDKs** and the **SDK publishing infrastructure**, on
branch `feat/m26-sdks` (commits `d99ed13`, `b3b4fc6`, `256e54b`). Milestone D's portal
truthfulness edits are in the working tree, layered onto the uncommitted portal build.

So most "REMAINING" cells are **documentation catch-up** and **commit + verification**, not
implementation.

---

## AREA | CLAIM (source) | ACTUAL STATE | COMPLETE? | REMAINING WORK | SEVERITY

### Backend

| AREA | CLAIM | ACTUAL STATE | COMPLETE? | REMAINING | SEVERITY |
|---|---|---|---|---|---|
| gateway-service | `CLAUDE_CONTEXT §5`: reactive edge — auth, API-key verify, session-context minting, scopes, rate limit, versioning, internal-context signing, request logging. | Present and unchanged this pass. Filter order documented and load-bearing. Not re-tested here beyond the full `./gradlew build`. | COMPLETE | none (M26 scope) | — |
| identity-service | JWT issuance, JWKS, auth flows. | Present, unchanged. | COMPLETE | none | — |
| merchant-service | Merchant CRUD, API-key lifecycle, internal verify, API-version pinning. | Present, unchanged. | COMPLETE | none | — |
| payment-service | `PROJECT_CONTEXT_2 §2.4`: create/authorize/capture/void/refund, transition table, `refund cannot exceed captured`. `project_3_context AD-7`: all invariants ✅ [VERIFIED]. Working tree adds `authorization/external/` + a new test dir. | Present. `payment-service/src/main/java/.../authorization/external/` and `payment-service/src/test/java/.../authorization/` are **untracked** (part of the Project-3 provider-decision inbound channel, AD-4.2). | COMPLETE (impl) / PARTIAL (committed) | commit the untracked provider-decision files with the Project-3 work; docs unchanged | LOW |
| transaction-service / ledger | Double-entry ledger, balance, balance transactions. | Present, unchanged. `/v1/balance` + `/v1/balance_transactions` in the frozen contract; SDKs cover both. | COMPLETE | none | — |
| audit / events | `audit_log`, `api_request_log`, `decision_log`; `/v1/events`. | Present, unchanged. | COMPLETE | none | — |
| notifications / webhooks | M18: endpoints, signed deliveries, replay, dead-letter; shared signature vectors. | Present. Vector file `notification-service/src/test/resources/signature-vectors/webhook-signature-vectors.json` exists and is now consumed by **four** SDKs (Node, Python, Java, Go). | COMPLETE | none | — |
| analytics | `/v1/analytics/payments`, `/v1/usage` (M20). | Present, unchanged. Dimensional breakdown: `AnalyticsSummary` has `buckets` (hourly, currency-split) and `UsageSummary` has per-day/key/route `buckets` — the "dimensional breakdown" gap noted in older docs is closed at the contract level. | COMPLETE | confirm the portal analytics page renders every bucket dimension (Milestone F visual QA) | LOW |
| sandbox | M17: test cards, decisions, simulation overrides; `/v1/test/*`. | Present. All 6 test-helper operations in the frozen contract; all four SDKs cover them. `sandbox-service` in `settings.gradle.kts`. | COMPLETE | none | — |
| agentic commerce | `project_3_context`: M-A..M-E built; catalog, checkouts, conversations, actions, agent config, policies, provider decisions, approval flow. Docs still say "M26 not started" for the sibling Java/Go SDKs but the agentic service itself is marked ✅ throughout AD-4..AD-9. | `agentic-commerce-service` fully present: `action/`, `agent/`, `approval/`, `catalog/`, `checkout/`, `conversation/`, `idempotency/`, `llm/`, `policy/`, `provider/razorpay/`, `runtime/`, `tool/{catalog,commerce,money,payment}/`, `web/` (12 controllers), 2 migrations, 27 tests. **Untracked.** | COMPLETE (impl) / PARTIAL (committed) | commit the Project-3 work as its own series; update `project_3_context.md` status table (still says "not started"); wire an `agentic` CI job (there is only the docker-image build leg) | MEDIUM (uncommitted) / LOW (impl) |
| OpenAI integration | Memory: "OpenAI live E2E done". `project_3_context AD-6`: LLM isolated from credentials; scripted fallback. | `llm/` has `LlmClient` + `AnthropicLlmClient` + `OpenAiLlmClient` + `ScriptedLlmClient` + `LlmUnavailableException` + `MalformedLlmOutputException`. `e2e/openai-roundtrip.mjs` and `e2e/openai-failure.mjs` present. Live validation needs `OPENAI_API_KEY` in env (not in repo). | COMPLETE (impl) | Milestone F: run `e2e/openai-roundtrip.mjs` + `openai-failure.mjs` with the key in env; confirm `ScriptedLlmClient` fallback when the key is absent | VERIFICATION GAP |
| provider adapters (Razorpay) | `project_3_context AD-5`: Razorpay credentials server-side only; `provider/razorpay/`. | `provider/razorpay/` present; `V2__provider_decisions.sql`; `ProviderDecisionController` + `ProviderDecisionQueryController`. | COMPLETE (impl) | commit with Project-3; live Razorpay calls need real credentials (out of scope, correctly) | LOW |
| security / authn / authz | `project_3_context AD-7`: idempotency (Redis lock + fingerprint + durable replay), gateway API-key filter, scope check, mode confinement, merchant isolation, transition table — all ✅ [VERIFIED]. | Unchanged this pass. The four SDKs all send `Authorization: Bearer`, `PaymentFlow-Version`, and a once-per-call `Idempotency-Key` reused across retries. | COMPLETE | none (M26 scope) | — |
| mode isolation (test/live) | Mode is bound to the key; `X-PF-Mode` stripped at `/v1`. SDKs must not offer a mode switch. | Confirmed in all four SDKs — none has a `mode` option; the Java and Go `testHelpers`/`TestHelpers` namespaces are documented "test mode only, decided by your key". | COMPLETE | none | — |

### Frontend (developer-portal)

| AREA | CLAIM | ACTUAL STATE | COMPLETE? | REMAINING | SEVERITY |
|---|---|---|---|---|---|
| marketing | `frontend_Design §5`: landing becomes a product surface with a public navbar. | `(marketing)/` has `page.tsx` + `agentic-commerce/`, `contact/`, `developers/`, `docs/`, `platform/`, `pricing/`, `security/`. **Untracked.** `next build` succeeds. | COMPLETE (impl) | commit with the portal build | LOW |
| authentication | M23.2/2a/2b ✅ in docs. | `(auth)/` present; middleware, CSRF, session. `next build` + portal `verify` green. | COMPLETE | none | — |
| onboarding | M23.4 ✅. | `(setup)/onboarding/` present. | COMPLETE | none | — |
| dashboard | M23.8 ⬜ in docs. | `(app)/dashboard/` with `page.tsx`, `getting-started.tsx`, `overview-client.tsx` (untracked). Builds. | COMPLETE (impl) / doc says ⬜ | commit; update CLAUDE_CONTEXT M23 table | LOW |
| payments / payment detail | M23.6 ✅ (list); M23.7 ⬜ (detail, capture/refund/void). | `(app)/payments/` has `payments-browser.tsx` **and** untracked `[id]/`, `action-state.ts`, `actions.ts`. `(app)/refunds/` present. Builds. | COMPLETE (impl) / doc says ⬜ | commit; update M23 table | LOW |
| refunds | M23.7 ⬜. | `(app)/refunds/` present, builds. | COMPLETE (impl) | commit | LOW |
| balance | M24. | `(app)/balance/` present, builds. | COMPLETE (impl) | commit | LOW |
| webhooks | M24. | `(app)/developers/webhooks/` present, builds. | COMPLETE (impl) | commit | LOW |
| events / request logs | M24. | `(app)/developers/events/`, `(app)/developers/logs/` present, build. | COMPLETE (impl) | commit | LOW |
| sandbox | M24. | `(app)/developers/sandbox/` present, builds. | COMPLETE (impl) | commit | LOW |
| analytics | M24. | `(app)/analytics/` present, builds. | COMPLETE (impl) | commit; confirm dimensional breakdown renders | LOW |
| settings | M23.4 ✅. | `(app)/settings/` present, builds. | COMPLETE | none | — |
| agentic commerce (portal) | `frontend_Design §17`, `project_3` M-F. | `(app)/agentic/` + `src/lib/agentic/` + `src/app/api/agentic/` (proxy) + `test/agentic-proxy.test.ts`. Builds; vitest green. | COMPLETE (impl) | commit; `project_3` M-F still "pending" in docs | LOW |
| developer experience / quickstart | `frontend_Design §5`: "SDKs is static, from repository source". | `(app)/developers/quickstart/` + `sdks/` + `overview/`. **This pass** made the quickstart truthful (Node/Python/Java/Go/cURL, "publish-ready not published" labels, real package/module names) and added Java+Go to the SDKs page. Portal `verify` green after the edits. | COMPLETE (this pass) | commit with the portal build | LOW |
| AI integration prompt | `developers/ai` + `lib/integration/prompt.ts`. | Contract-grounded generator; **this pass** extended `stacks.ts` with a `go` stack + `published`/`repoDir` fields and made the SDK line honest (publish-ready, build from `repoDir`, prefer REST, real coords). | COMPLETE (this pass) | commit | LOW |
| responsive / mobile | M23.9 ⬜ (hardening). | Tailwind responsive classes throughout; `next build` succeeds. No automated viewport QA committed. | PARTIAL | Milestone F: manual mobile pass (checklist item Q); M23.9 Playwright/axe still ⬜ | MEDIUM (verification) |
| accessibility | M23.9 ⬜. | axe not wired into CI; components use semantic roles (`Tabs` is a `tablist`, arrow-key nav). | PARTIAL | M23.9 axe automation — INTENTIONALLY DEFERRED to hardening | LOW |

### SDKs

| AREA | CLAIM | ACTUAL STATE | COMPLETE? | REMAINING | SEVERITY |
|---|---|---|---|---|---|
| Node.js | M22 ✅ "finished, not published". | `sdks/node`, `paymentflow` `0.1.0`, 108 tests green, `npm pack` dist-only (156 files). Metadata brought to release quality this pass (repository/homepage/bugs/author/keywords/publishConfig). `"private": true` kept as the deliberate gate. | COMPLETE | actual publish (needs the flag flip + npm trusted-publisher config) | INTENTIONALLY DEFERRED |
| Python | M22 ✅. Async client D181 NOT built. | `sdks/python`, `paymentflow` `0.1.0`, 193 tests + mypy green, `python -m build` + `twine check` PASS. `[project.urls]`/authors/keywords/classifiers added this pass. `Private :: Do Not Upload` kept. | COMPLETE (sync) | async client — INTENTIONALLY DEFERRED (own sub-milestone); actual publish deferred | INTENTIONALLY DEFERRED |
| Java | `project_3_context`: "no Java SDK (M26 unbuilt)". | **Built this pass.** `sdks/java`, standalone Gradle, Java 17 target, zero runtime deps, all 31 ops / 11 namespaces, 30 tests green, `publishToMavenLocal` stages jar+sources+javadoc+POM. Fixture-parity via `ContractParityTest`. Committed `d99ed13`. | COMPLETE | Maven Central publish (needs Sonatype account + namespace verification + signing key — all documented) | INTENTIONALLY DEFERRED (publish) |
| Go | `project_3_context`: "M26 Java/Go SDKs — not started". | **Built this pass.** `sdks/go`, module `…/sdks/go`, `go 1.23`, zero deps, all 31 ops / 11 services, 29 tests + `go vet` + `gofmt` green, `go mod tidy` no-op. Fixture-parity via `parity_test.go`. Committed `b3b4fc6`. | COMPLETE | tag `sdks/go/v0.1.0` to release (no account needed) | INTENTIONALLY DEFERRED (tag) |
| cURL | Quickstart + AI prompt fall back to raw REST. | Present in the quickstart for every step; the AI prompt lists only real endpoints. | COMPLETE | none | — |
| publishing | `sdks/README`: "publishing needs explicit approval". | **This pass.** 4 tag-triggered release workflows (npm OIDC, PyPI Trusted Publishing, Maven Central via vanniktech, Go tag+proxy), each version-gated and behind a GitHub environment. `sdks/PUBLISHING.md` documents every account/secret/namespace. All 5 workflow YAMLs parse. Committed `256e54b`. | COMPLETE (ready) | you: create the accounts/environments/secrets in `sdks/PUBLISHING.md`, then tag | — |

### Demo (Knitt)

| AREA | CLAIM | ACTUAL STATE | COMPLETE? | REMAINING | SEVERITY |
|---|---|---|---|---|---|
| Knitt storefront | Memory: "Knitt merchant demo built". | `mock-project/knitt` — `web/` (Vite storefront + Assistant page), `server/` (`index.js`, `paymentflow.js`, `catalog.js`, `agentic.js`, `store.js`), `scripts/verify-payment.mjs`, README documenting 7 flows. **Untracked.** `.env` present (git-ignored — verify in F). | COMPLETE (impl) | commit; secret-scan its `.env` handling | LOW |
| PaymentFlow integration | Create→authorize→capture via `server/paymentflow.js`; derived idempotency key per `(orderId, step)`; declined-card real reason. | Present per README flows 2–6. | COMPLETE (impl) | live-run in F (checklist H) | VERIFICATION GAP |
| agentic integration | Assistant page → search → checkout → policy → approval → payment via `server/agentic.js` over signed internal context. | Present per README flow 7. | COMPLETE (impl) | live-run in F (checklist N/O) | VERIFICATION GAP |
| payment / refund / approval flow | e2e scripts + Knitt. | `e2e/portal-integration-e2e.mjs`, `bootstrap-knitt.mjs`; Knitt `verify-payment.mjs`. | COMPLETE (impl) | run in F | VERIFICATION GAP |

### Infrastructure

| AREA | CLAIM | ACTUAL STATE | COMPLETE? | REMAINING | SEVERITY |
|---|---|---|---|---|---|
| Docker | `CLAUDE_CONTEXT`: one parameterized Dockerfile, all services. `Dockerfile` + `docker-compose.yml` modified in the working tree. | `Dockerfile` and `docker-compose.yml` carry uncommitted changes (agentic-commerce-service service + build args, per the `ci.yml` agentic hunk). Not built here. | PARTIAL (committed) | commit the compose/Dockerfile changes with Project-3; a compose-up smoke is a manual step | LOW |
| database | Per-service Postgres schema, Flyway. Agentic adds `V1__init_agentic`, `V2__provider_decisions`. | Migrations present. | COMPLETE (impl) | commit | LOW |
| Redis / Kafka | Gateway rate-limit + key cache (Redis); request-log producer (Kafka). | Unchanged. | COMPLETE | none | — |
| CI | `ci.yml`: 5 jobs; `sdks` matrix was `[node, python]`. | **This pass** extended the `sdks` matrix to `[node, python, java, go]` with build/vet/test/gofmt steps + `publishToMavenLocal`. The pre-existing (uncommitted) `agentic-commerce-service` docker-image leg is restored to the tree, unstaged, untouched by my commit. | COMPLETE (this pass) | commit the agentic CI leg with Project-3; consider a dedicated `agentic` Gradle-test job | LOW |
| GitHub Actions (release) | `cd.yml` is deploy-only, `workflow_dispatch`. | 4 new `sdk-release-*.yml` added this pass. `cd.yml` unchanged. | COMPLETE (this pass) | — | — |
| environment config | `.env.example` modified in working tree; `.env` git-ignored. | `.env.example` has uncommitted changes (not mine). `.gitignore` covers `.env`, SDK build dirs, caches. Verified in F. | PARTIAL (committed) | commit `.env.example` changes; F secret scan | LOW |

### Testing

| AREA | CLAIM | ACTUAL STATE | COMPLETE? | REMAINING | SEVERITY |
|---|---|---|---|---|---|
| unit (backend) | `./gradlew build` gates everything. | Full `./gradlew clean build --no-build-cache` run 3× — the 3rd passed clean (`BUILD SUCCESSFUL in 12m 30s`, 118 tasks). | COMPLETE | — | — |
| integration (backend) | Testcontainers per service. | Green in the clean run 3 (`*IntegrationTest` suites all passed); runs 1–2 flaked on Testcontainers under load, 0 assertion failures. | COMPLETE | — | — |
| SDK — Node | 108 tests. | Green (`npm run verify`). | COMPLETE | — | — |
| SDK — Python | 193 tests + mypy + packaging. | Green. | COMPLETE | — | — |
| SDK — Java | 30 tests. | Green (`./gradlew build`). | COMPLETE | — | — |
| SDK — Go | 29 tests + vet + gofmt + mod-tidy. | Green (Go 1.23.12). | COMPLETE | — | — |
| shared codegen parity | `SdkCodegenTest`, `SdkParityTest`. | `:sdks:shared:test --rerun-tasks` green after the M26 manifest edits. Java/Go are fixture-verified in their **own** suites, not in `SdkParityTest` (Node-vs-Python only). | COMPLETE | optional: extend `SdkParityTest` to cover Java/Go, or add `JavaEmitter`/`GoEmitter` | LOW |
| frontend | `portal npm run verify` = tsc + eslint + prettier + vitest + next build. | Green after Milestone D edits. | COMPLETE | — | — |
| E2E | `e2e/` 9 scripts. | Present, not run this pass. | VERIFICATION GAP | F: portal-e2e, portal-integration-e2e, openai-roundtrip, openai-failure, bootstrap-knitt, smoke | VERIFICATION GAP |
| OpenAI | `e2e/openai-*.mjs`. | Present. | VERIFICATION GAP | F, with `OPENAI_API_KEY` in env | VERIFICATION GAP |
| Knitt | Knitt `verify-payment.mjs` + `e2e/bootstrap-knitt.mjs`. | Present. | VERIFICATION GAP | F | VERIFICATION GAP |
| security / secret scanning | No dedicated secret-scan step in CI. | `.gitignore` covers `.env`; this pass added no secrets; manual staged-diff scan clean at each commit. | PARTIAL | F: full tracked-file + `.next/static` secret scan; consider a `gitleaks` CI step | MEDIUM |

### Documentation

| AREA | CLAIM | ACTUAL STATE | COMPLETE? | REMAINING | SEVERITY |
|---|---|---|---|---|---|
| API reference | `docs/openapi.yaml` (325 KB, 31 ops), `ERRORS.md`, `READ_APIS.md`, `VERSIONING.md`, each test-asserted. | Present, unchanged, authoritative. All four SDKs generated against it. | COMPLETE | none | — |
| Quickstart | `frontend_Design §5`. | Truthful and covers 5 languages after this pass. | COMPLETE (this pass) | commit | LOW |
| SDK documentation | `sdks/README.md` + per-SDK README + CHANGELOG. | Node/Python/Java/Go each have README + CHANGELOG; `sdks/README.md` updated for Java+Go; `sdks/PUBLISHING.md` new. | COMPLETE (this pass) | — | — |
| agentic commerce docs | `project_3_context.md` (233 KB). | Thorough on design; **status table stale** ("M24/M25/M26 not started", "no Java SDK"). | PARTIAL | update `project_3_context.md` §status and the AD-4.3 "no Java SDK" line; update `CLAUDE_CONTEXT.md` M23 table and "remaining roadmap" | MEDIUM |
| test/live mode | `frontend_Design`, memory "mode truth documented". | The AI prompt and (per memory) other surfaces state that live mode still settles against a simulated acquirer — no real funds move in either mode. | COMPLETE | keep that wording everywhere a "live" claim could be read as real money (F check) | LOW |
| deployment | M29 (AWS deploy of V2) not started. | `terraform/` is V1's estate, not extended. `cd.yml` `workflow_dispatch` only. | INTENTIONALLY DEFERRED | M29 | — |
| publishing | `sdks/PUBLISHING.md`. | New, complete: accounts, namespace verification, every secret, publish-ready vs published table. | COMPLETE (this pass) | you execute the account setup | — |

### Git / GitHub

| AREA | CLAIM | ACTUAL STATE | COMPLETE? | REMAINING | SEVERITY |
|---|---|---|---|---|---|
| branch / commits | — | Branch `feat/m26-sdks` off `main`. 3 M26 commits: `d99ed13` (Java SDK), `b3b4fc6` (Go SDK), `256e54b` (publishing/CI). | COMPLETE (M26) | Milestone F: manual test plan; then push | — |
| uncommitted pre-existing work | — | Large: the whole portal build, `agentic-commerce-service`, `e2e/`, `mock-project/knitt`, `.env.example`/`Dockerfile`/`docker-compose.yml`/`ci.yml` (agentic hunk)/`settings.gradle.kts` deltas, plus `Design palette and scope.zip` (an artefact — should not be committed). | NOT MY SCOPE | you: commit the Project-3 + portal build as its own series; delete or `.gitignore` `Design palette and scope.zip` | MEDIUM |
| remote | `origin` = `github.com/IsaHaameem/cloud-native-payment-processing-platform` (exists). | Confirmed. `feat/m15-api-key-auth` and `main` on origin. | COMPLETE | push `feat/m26-sdks` (after F) | — |
| secrets in history | — | No secrets introduced by M26. Pre-existing history not audited here; `.env` is git-ignored and not tracked. | PARTIAL | F: `git log -p` grep for key patterns on the M26 commits (done incrementally); a full-history scan is a separate exercise | LOW |

---

## Explicit classification of every non-COMPLETE item

**COMPLETE (implemented + verified this pass):** Java SDK, Go SDK, SDK publishing infra, CI
matrix extension, Quickstart truthfulness, AI-prompt SDK coverage, all four SDK test suites,
portal `verify`, `:sdks:shared` parity.

**COMPLETE (implemented, pre-existing, not committed):** agentic-commerce-service, the portal
build past M23.6 (payment detail, refunds, balance, analytics, events, logs, sandbox, webhooks,
marketing, dashboard, agentic portal), the Knitt demo, the e2e harness,
payment-service provider-decision channel.

**PARTIAL:** responsive/mobile QA (no automated viewport tests — M23.9), accessibility
automation (M23.9), secret-scanning in CI (no gitleaks step), Docker/compose changes
(uncommitted).

**BACKEND GAP:** none found. Every `/v1` operation the SDKs target exists; every invariant in
`project_3_context AD-7` is marked verified and unchanged.

**FRONTEND GAP:** none functional. The gaps are verification (mobile/axe) and the fact that
`frontend_Design §15` "Customers — DOES NOT EXIST" is intentional, not a gap.

**DOCUMENTATION GAP:** `CLAUDE_CONTEXT.md` M23 table and remaining-roadmap; `project_3_context.md`
status table and the "no Java SDK (M26 unbuilt)" line in AD-4.3. These are the main stale claims.

**VERIFICATION GAP:** E2E suites (portal, OpenAI roundtrip + failure, Knitt), the OpenAI live
path, the `ScriptedLlmClient` fallback, and a full tracked-file secret scan — all scheduled for
Milestone F.

**INTENTIONALLY DEFERRED (do not reopen):** async Python client (D181, own sub-milestone);
actual registry publishing (needs your accounts + a deliberate flag flip); M27 security review;
M28 V2 performance; M29 AWS deploy; M30 launch/portfolio; `JavaEmitter`/`GoEmitter` in
`:sdks:shared` (fixture parity is the accepted substitute); a Java SDK for the agentic layer
(AD-4.3 deliberately hand-writes the HTTP client — a random idempotency key is wrong for an
agent).

**ACTUALLY MISSING (genuine remaining implementation):** nothing in M26 scope. Outside it, the
only true "not built" item is the async Python client, and that is a deliberate deferral, not
an omission.
