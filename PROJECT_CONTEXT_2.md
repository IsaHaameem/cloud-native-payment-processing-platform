# PROJECT_CONTEXT_2.md — PaymentFlow Developer Platform (Version 2)

> **Single source of truth for Version 2.** `PROJECT_CONTEXT.md` remains the frozen
> historical record of Version 1 (M0–M14) and must not be modified except where
> backwards compatibility genuinely requires it. Every V2 milestone, architectural
> decision, schema change, API addition, trade-off, and known issue is recorded *here*.
>
> **Status:** M15 (API Key Authentication & Machine-to-Machine Access) — **complete**
> (2026-07-21). Post-M15 repository stabilization phase (8 fixes, §17) — **complete**
> (2026-07-22). **M16 (Test/Live Mode Isolation) — complete** (2026-07-22): all
> sub-milestones M16.1–M16.7 implemented, verified, committed, and E2E-validated on the
> running docker-compose stack. **M17 (Sandbox Simulation Engine) — complete**
> (2026-07-23 – 2026-07-24): architecture reviewed and approved (incl. the
> `AuthorizationAdvisor` port, D127–D132), decomposed into M17.1–M17.8; all eight
> sub-milestones implemented, verified independently, and E2E-validated together on the
> running docker-compose stack. **M18 (Webhooks as a Product) — complete** (2026-07-25):
> repository reviewed, four planning decisions confirmed with the user (D133–D136),
> decomposed into M18.1–M18.9; all nine sub-milestones implemented, verified independently,
> and E2E-validated together on the running docker-compose stack against real
> signature-verifying receivers. Closes V1 known issue #2 (unsigned webhooks).
> **M19 (Public Read APIs & Query Surface) — complete** (2026-07-25 – 2026-07-26):
> repository reviewed against §5/M19 (seven divergences resolved as D139/D140 and in
> M19.7), decomposed into M19.1–M19.8; all eight sub-milestones implemented, verified
> independently, `EXPLAIN`-checked against seeded production-scale data, and E2E-validated
> together on the running docker-compose stack over the real gateway. Closes V1 known
> issues #3 and #4 (three services with no query API).
> **M20 is the next milestone.**
> **Milestone IDs continue from V1:** V2 begins at **M15**.
> **Decision IDs continue from V1:** V1 ended at **D97**; V2's log now runs **D98–D144**.

---

## Table of Contents

1. [Project Overview](#1-project-overview)
2. [Existing Services (Version 1 Inventory)](#2-existing-services-version-1-inventory)
3. [New Platform Vision](#3-new-platform-vision)
4. [Architecture Changes](#4-architecture-changes)
5. [Milestone Roadmap](#5-milestone-roadmap)
6. [Dashboard Planning](#6-dashboard-planning)
7. [SDK Planning](#7-sdk-planning)
8. [Sandbox Planning](#8-sandbox-planning)
9. [API Documentation Planning](#9-api-documentation-planning)
10. [Engineering Principles](#10-engineering-principles)
11. [Technical Decisions & Trade-offs (V2 log)](#11-technical-decisions--trade-offs-v2-log)
12. [Risks](#12-risks)
13. [Open Questions](#13-open-questions)
14. [Known Issues (V2)](#14-known-issues-v2)
15. [Future Extensibility (beyond V2)](#15-future-extensibility-beyond-v2)
16. [Maintenance Rules](#16-maintenance-rules)
17. [Milestone Change Log (V2)](#17-milestone-change-log-v2)
18. [Performance Benchmarks (V2)](#18-performance-benchmarks-v2)
- [Appendix A — Planning Decisions Confirmed With the User](#appendix-a--planning-decisions-confirmed-with-the-user-2026-07-20)

---

## 1. Project Overview

### 1.1 What Version 2 Is

Version 1 built a **distributed payment orchestration engine**: eight microservices that
accept, authorize, capture, and refund payments with idempotency, a transactional outbox,
a double-entry ledger, event-driven propagation, resilience, observability, and a live
AWS ECS Fargate deployment. It is a working payments *backend*.

Version 2 turns that backend into a **Developer Payment Gateway Platform** — the
Stripe/Razorpay-sandbox-shaped product surface that sits *around* a payments engine and
makes it consumable by third parties who have no access to the internals:

- a **self-serve signup** that produces a merchant account in minutes, with no KYC;
- **API keys** (`sk_test_` / `sk_live_`) as the primary authentication mechanism, not JWTs;
- a **test/live mode split** with hard data isolation between the two;
- a **sandbox simulation engine** — test cards, deterministic failure codes, delayed
  settlement, duplicate-request and idempotency exercises;
- **signed webhooks** with subscriptions, a delivery log, and manual replay;
- **public read APIs** for payments, refunds, balances, events, and usage;
- **official SDKs** (Node, Python, then Java, Go) with retries, pagination, typed errors,
  and webhook-signature verification;
- a **developer portal** — merchant dashboard, API console, logs, analytics, admin;
- **documentation** driven by a real OpenAPI 3.1 spec, with versioning and a deprecation
  policy.

### 1.2 Why We Are Building It

Three reasons, in priority order:

1. **The interesting distributed-systems problems in a payment platform are mostly on the
   *platform* side, not the payment side.** V1 already solved sagas, outbox, idempotency,
   and ledger consistency. V2 introduces a different and equally deep class of problems:
   multi-tenant data isolation, machine authentication at the edge, at-least-once webhook
   delivery to *untrusted third-party endpoints*, usage metering under load, API
   versioning without breaking existing integrations, and deterministic simulation of
   non-deterministic upstream behaviour. These are the problems that separate "a service
   that processes payments" from "a platform other people build on."

2. **A payments backend with no consumer surface cannot be demonstrated.** V1's live AWS
   deployment can only be exercised by hand-crafted `curl` chains carrying JWTs. Nobody
   can be handed a link. V2 makes the platform *usable by a stranger in ten minutes* —
   which is both the actual product requirement of a payment gateway and, not incidentally,
   the single highest-leverage thing a portfolio project can do.

3. **It is an evolution, not a rewrite.** Every V1 service survives, keeps its schema, and
   keeps its charter. V2 adds exactly one new backend service and one new frontend app;
   everything else is extension of existing services along their existing seams. This is
   deliberately the harder, more realistic engineering exercise — real platforms are
   evolved under load, not rebuilt.

### 1.3 Goals

| # | Goal | How it is measured |
|---|---|---|
| G1 | An external developer can sign up, get keys, and take a test payment with no human in the loop | Time-to-first-successful-payment from a cold start, measured by walking the quickstart end to end |
| G2 | Test and live data are **isolated by construction**, not by convention | A `sk_test_` key can never read, mutate, or even observe the existence of a live-mode object; enforced at the persistence layer, exercised by tests |
| G3 | Webhooks are cryptographically verifiable and operationally observable | HMAC-SHA256 signature with replay window; every attempt visible in a delivery log; manual replay works |
| G4 | The public API is documented, versioned, and machine-described | One merged OpenAPI 3.1 spec that actually validates against live responses; CI fails on an undeclared breaking change |
| G5 | SDKs make the correct thing the easy thing | Automatic idempotency keys, automatic retries with backoff, pagination auto-iteration, typed errors, signature verification helper |
| G6 | Failure is a first-class, *requestable* behaviour | Test cards and simulation controls can deterministically produce declines, timeouts, delayed captures, and duplicate deliveries |
| G7 | Nothing V1 established regresses | The existing V1 test suites, Gatling simulations, and the FSM/ledger/outbox invariants stay green throughout |

### 1.4 Non-Goals

Explicitly **out of scope** for V2. Each is listed so that a later milestone does not
quietly absorb it without a decision.

- **Real money movement.** No card-network, acquirer, bank, or PSP integration. "Live"
  mode is backed by a simulated acquirer. The platform never touches funds.
- **KYC / onboarding compliance.** No identity verification, document upload, sanctions
  screening, or underwriting. Signup is self-serve and immediate. This is what makes the
  platform a *sandbox* rather than a regulated product.
- **PCI DSS scope.** Raw PAN data is never accepted, stored, or transmitted. Test "cards"
  are opaque tokens and well-known test numbers that map to outcomes; no real card data
  ever enters the system, so the platform stays entirely out of PCI scope by construction.
- **Payouts, settlement to bank accounts, and treasury.** The ledger models obligations;
  it does not settle them.
- **Multi-currency FX conversion.** Currency remains a per-payment attribute; there is no
  conversion, rate feed, or FX exposure modelling.
- **Fraud / risk scoring engine.** A `risk` hook point is designed for, not built.
- **Marketplace / Connect-style sub-merchants.** One merchant per developer account.
- **Mobile SDKs and drop-in UI components** (hosted checkout page, card element).
  Deferred to a possible V3 — see §15.
- **On-premise / self-hosted distribution** of the platform.

### 1.5 Current Architecture (end of V1)

```
                          ┌─────────────────────────────────────┐
   Client (curl/JWT) ────► │  ALB  ─►  gateway-service :8080     │
                          │   JWT validation (RS256 via JWKS)   │
                          │   Redis token-bucket rate limiting  │
                          │   CORS, correlation-id injection    │
                          └───────────────┬─────────────────────┘
                                          │  (routes by path)
        ┌─────────────────────┬───────────┴───────────┬──────────────────────┐
        ▼                     ▼                       ▼                      │
 identity-service      merchant-service        payment-service               │
 :8081                 :8082                   :8083                         │
 users, BCrypt,        merchants, API keys,    payment FSM, idempotency,      │
 JWT issue/refresh,    Redis cache-aside,      transactional outbox,          │
 JWKS, RBAC            webhook_url             saga orchestration             │
        │                     ▲                       │                      │
        │                     └───── OpenFeign ───────┘                      │
        │                       (Resilience4j: retry → CB →                  │
        │                        timeout → thread-pool bulkhead)             │
        │                                              │                     │
        │                                              ▼                     │
        │                              ┌───── Kafka: payment.events ─────┐   │
        │                              │      payment.events.retry       │   │
        │                              │      payment.events.dlq         │   │
        │                              └──┬────────┬────────┬─────────┬──┘   │
        │                                 ▼        ▼        ▼         ▼      │
        │                        transaction-  audit-  notification-  analytics-
        │                        service       service service        service
        │                        :8084        :8091   :8092          :8093
        │                        double-entry immutable webhook POST  per-merchant
        │                        ledger       audit    + simulated    aggregates
        │                                     log      email, retry+DLQ
        │
        └── PostgreSQL 17 (schema-per-service) · Redis 8 · Kafka (KRaft)
            Observability: Micrometer → Prometheus / Grafana / Loki / Tempo (local only)
            Cloud: AWS ECS Fargate, RDS, ElastiCache, ALB, ECR, Secrets Manager (live)
```

### 1.6 How Version 2 Extends Version 1

The single most important framing for every milestone below:

> **V1 built the *engine*. V2 builds the *platform surface* around it. The engine's
> internals — FSM, outbox, ledger, saga, idempotency — are treated as settled and are
> touched only where multi-tenancy or mode isolation genuinely requires it.**

| Dimension | Version 1 | Version 2 |
|---|---|---|
| **Who calls the API** | A human with a JWT obtained by logging in | A developer's *server* with a long-lived secret API key |
| **Authentication** | JWT (RS256, 15-min access + rotating refresh) | API key at the edge → resolved to a signed internal merchant context; JWT retained for the dashboard session only |
| **Tenancy** | One merchant per user; ownership from the JWT subject | Same, but every object additionally carries a **mode** (`test`/`live`) that partitions the data plane |
| **API keys** | One active opaque key per merchant, issued but **never actually used to authenticate anything** (D31/D32) | Multiple named, scoped, mode-specific keys; the primary auth mechanism; last-used tracking, revocation, rotation |
| **Webhooks** | One URL per merchant, unsigned, fire-and-retry, no visibility | Many endpoints per merchant, event-type subscriptions, HMAC-signed, delivery log, replay, auto-disable |
| **Read APIs** | Payments only. Ledger, audit, and analytics have **no query API at all** (D42 + known issues) | Full read surface: payments, refunds, balance, ledger, events, webhook deliveries, request logs, usage |
| **Failure behaviour** | Whatever the system happens to do | Deterministically *requestable* via test cards and simulation controls |
| **Documentation** | None. `springdoc` is named in the V1 tech-stack table but **is not actually a dependency of any module** | OpenAPI 3.1 generated from code, merged, versioned, published, CI-diffed |
| **Client experience** | Hand-written `curl` | Four official SDKs + a docs site + an interactive console |
| **UI** | None (V1's own M15 planned a Next.js console but was never started) | One Next.js app: merchant dashboard, developer console, admin, docs |
| **New services** | — | **`sandbox-service`** (simulated acquirer + scenario engine) and **`developer-portal`** (Next.js). Nothing else is added. |

**On V1's unstarted M15.** V1's roadmap reserved M15 for "Next.js merchant console,
OpenAPI polish, README, diagrams, interview notes." That milestone was never started, and
V2 supersedes it entirely: the console becomes M23/M24, OpenAPI becomes M21, and the
README/diagrams/interview material becomes M30. The `M15` identifier is therefore reused
by V2 for API-key authentication. This is recorded explicitly so that a reader who finds
"M15 — Next.js console" in `PROJECT_CONTEXT.md` §6 understands it was replaced, not
skipped or forgotten.

---

## 2. Existing Services (Version 1 Inventory)

Everything in this section describes the system **as it exists today**, before any V2 work.
It is the baseline every V2 milestone is diffing against. Where a service has a gap that V2
must close, it is called out inline as **→ V2**.

### 2.1 gateway-service (`:8080`, reactive)

**Today.** Spring Cloud Gateway on WebFlux. Sole ALB-exposed service. Responsibilities:

- **Routing**, declared in YAML (D22), by path predicate only:
  - `/api/v1/auth/**`, `/api/v1/users/**`, `/oauth2/jwks` → identity-service
  - `/api/v1/merchants/**` → merchant-service
  - `/api/v1/payments/**` → payment-service
  - transaction/audit/notification/analytics are **not routed at all** — they have no APIs.
- **JWT validation** as a reactive resource server against identity-service's JWKS.
  Authentication only; RBAC is delegated downstream (D23).
- **Rate limiting** — Redis token bucket, `replenishRate=20/s`, `burstCapacity=40`, keyed
  `user:<sub>` when authenticated and `ip:<addr>` otherwise (D24). Applied as a
  `default-filter` to every route.
- **CORS**, security headers, and a reactive `CorrelationIdWebFilter` (D25).
- Micrometer/Prometheus metrics; OTLP trace export to Tempo; Reactor-Context→MDC bridging.

**→ V2.** This is the service V2 changes most. It becomes the **API-key authentication
edge** (M15), the **usage-metering event source** (M20), and the enforcement point for
**per-key rate limits and quotas** (M20). It also gains routes for every read API added in
M19 and the webhook-management API in M18.

**Known gaps carried in:** does not honour `X-Forwarded-*` (needs
`spring.cloud.gateway.server.webflux.trusted-proxies` now that it sits behind an ALB); its
AWS task definition runs `SPRING_PROFILES_ACTIVE=local`, pinning CORS to
`http://localhost:3000` — a real misconfiguration for any deployed browser client, which
M23 must fix before the portal can call it.

### 2.2 identity-service (`:8081`)

**Today.** Users, authentication, and token issuance.

- `POST /api/v1/auth/register` · `POST /api/v1/auth/login` · `POST /api/v1/auth/refresh` ·
  `POST /api/v1/auth/logout`
- `GET /api/v1/users/me` · `GET /api/v1/users` (ADMIN-only)
- `GET /oauth2/jwks` — public key distribution
- BCrypt password hashing; RS256 access tokens (D15); opaque, SHA-256-hashed, **rotating**
  refresh tokens stored in the DB, so logout and replay-detection are real (D16).
- Also validates its own tokens as a resource server, with method-level `@PreAuthorize`
  (D17) — per-service zero trust, not "the gateway checked it."
- Signing keypair from Secrets Manager in AWS (PKCS#8 — D83), ephemeral in dev (D18).

**Schema (`identity`):** `users` (email unique, `password_hash`, `enabled`, `version`),
`user_roles` (element collection), `refresh_tokens` (hash unique, `expires_at`, `revoked`).

**→ V2.** Gains developer-account concepts: email verification, password reset, and
dashboard **session** tokens distinct from API access (M15/M23). Roles expand beyond
`USER`/`ADMIN` to cover portal team membership if §13-Q3 resolves that way.

### 2.3 merchant-service (`:8082`)

**Today.** Merchant profiles and API keys — the service closest to V2's centre of gravity.

- `POST /api/v1/merchants` (onboard) · `GET /api/v1/merchants/me` ·
  `PATCH /api/v1/merchants/me` · `PATCH /api/v1/merchants/me/webhook` ·
  `POST /api/v1/merchants/me/api-key/rotate` · `GET /api/v1/merchants` (ADMIN-only)
- Ownership is **always** derived from the JWT subject, never a path parameter (D28) — so
  there is no IDOR surface to defend, by construction. V2 must preserve this property when
  the subject becomes an API-key-derived merchant context instead of a user.
- **API keys:** exactly one active key per merchant, rotate-in-place, enforced by a
  **partial unique index** `WHERE revoked_at IS NULL` (D29). Raw value is `pf_` + opaque
  token, returned once; only `sha256(raw)` and a 12-char visible prefix are stored.
- Redis cache-aside over an immutable response DTO (never the JPA entity), with a
  dedicated type-aware `ObjectMapper` for the cache serializer (D30/D38).
- `webhook_url` (nullable, HTTPS-only) added in M7 and embedded into payment events at
  publish time so notification-service needs no synchronous callback (D43).

**Schema (`merchant`):** `merchants` (`owner_user_id` unique, `business_name`,
`contact_email`, `webhook_url`, `version`), `api_keys` (`merchant_id`, `key_prefix`,
`key_hash` unique, `revoked_at`, partial unique index on active key).

**→ V2.** The API-key model is rebuilt in M15: multiple keys, `mode`, scopes, names,
`last_used_at`, and an **internal verification endpoint** — the endpoint D31 deliberately
declined to build speculatively, which now has a real caller. `webhook_url` is superseded
by M18's endpoint model and retained only for backwards compatibility.

### 2.4 payment-service (`:8083`)

**Today.** The core engine and the busiest service.

- `POST /api/v1/payments` · `POST /{id}/authorize` · `POST /{id}/capture` ·
  `POST /{id}/refund` · `POST /{id}/void` · `GET /{id}` · `GET /api/v1/payments`
- **FSM:** `CREATED → AUTHORIZED → CAPTURED → REFUNDED`, plus `FAILED`, `VOIDED`,
  `PARTIALLY_REFUNDED`. Illegal transitions are rejected. Capture is all-or-nothing;
  refunds may be partial and accumulate (D35).
- **Idempotency:** `Idempotency-Key` required on **every** mutating endpoint (D34).
  Redis lock + `idempotency_keys` table storing status, body, and a request fingerprint;
  a same-key/different-body replay is rejected. Sequenced via `TransactionTemplate` so the
  lock outlives the commit (D33).
- **Transactional outbox** (D3): the state mutation and the `outbox_events` row commit
  together; a polling relay publishes to Kafka and stamps `published_at`.
- **Merchant resolution** via OpenFeign to merchant-service `/me`, forwarding the caller's
  JWT, wrapped in M8's Retry → CircuitBreaker → TimeLimiter → ThreadPoolBulkhead chain
  composed programmatically (D49–D52).
- Event payloads live in payment-service's own package; only the `EventEnvelope<T>` wrapper
  is shared (D36).

**Schema (`payment`):** `payments` (money as `amount_minor BIGINT` + `currency VARCHAR(3)`,
`captured_amount_minor`, `refunded_amount_minor`, `version`), `idempotency_keys`
(unique on `(merchant_id, idempotency_key)`), `outbox_events` (partial index on the
unpublished tail).

**→ V2.** Gains `mode` on every row (M16), delegates authorization outcomes to
sandbox-service in test mode (M17), gains a first-class `refunds` sub-resource and richer
list filtering (M19), and gains `metadata` (a `jsonb` free-form field every Stripe-shaped
API has) in M19.

### 2.5 transaction-service (`:8084`)

**Today.** Double-entry ledger. **Kafka-in only — no REST API, no Spring Security, no
Feign** (D42). Consumes `payment.events` idempotently.

- Three account types per currency: one platform-wide `PLATFORM_CLEARING` (debit-normal),
  and per-merchant `MERCHANT_PENDING` / `MERCHANT_SETTLED` (credit-normal) — D40.
- Posts on Authorized, Captured, and Refunded/PartiallyRefunded; `Voided`/`Failed` reverse
  only if the previous status was `AUTHORIZED` (D39). `Created` never posts.
- Events carry an **incremental** `eventAmountMinor` delta, not a running total (D41).
- Optimistic locking with a jittered-backoff whole-transaction retry (`MAX_ATTEMPTS = 10`).
  M14 measured 575 real retries under contention with zero failed requests, and confirmed
  every account nets to exactly 0 after a fully refunded lifecycle.

**Schema (`transaction`):** `accounts` (two partial unique indexes — one clearing account
per currency, one per `(type, owner, currency)` otherwise), `ledger_transactions`,
`ledger_entries`, `processed_events`.

**→ V2.** Gains its **first API** in M19 (balance and ledger reads) — closing the
longest-standing V1 known issue — and mode-partitioned accounts in M16.

### 2.6 audit-service (`:8091`)

**Today.** Immutable append-only trail. Parses each event as a generic `JsonNode` and
stores the payload verbatim in `jsonb` (D44) — deliberately schema-agnostic, since its job
is to record whatever arrived, unchanged.

**Schema (`audit`):** `audit_log` (`event_id` unique, `event_type`, `aggregate_id`,
`occurred_at`, `correlation_id`, `payload jsonb`).

**→ V2.** Becomes the backing store for the public **Events API** (M19) — the
`GET /v1/events` surface every Stripe-like platform exposes — which requires mode
partitioning, merchant scoping, and cursor pagination over an append-only log.

### 2.7 notification-service (`:8092`)

**Today.** Webhook delivery plus a *simulated* email channel.

- Outbox-shaped delivery (D46): dedup check, `email_log` row, and a `PENDING`
  `webhook_deliveries` row all commit in one short transaction **with no network I/O
  inside it**; the first attempt happens immediately after commit; a failure publishes the
  event id to `payment.events.retry`, consumed by a dedicated retry listener with jittered
  exponential backoff, up to 5 total attempts, then `payment.events.dlq`.
- Email is logged, never sent — no SMTP/SES provider is wired (D45).

**Schema (`notification`):** `processed_events`, `email_log`, `webhook_deliveries`
(`event_id` unique, `status` PENDING/DELIVERED/DEAD_LETTERED, `attempt_count`, `version`).

**→ V2. Delivered in M18** (2026-07-25). The charter widened from "deliver the merchant's
one webhook URL" to a full **webhook subsystem**, and the service changed shape more than
any service has since M5:

- **No longer Kafka-only.** It now hosts a public, key-authenticated API
  (`/v1/webhook_endpoints`, `/v1/webhook_deliveries`) behind Spring Security with
  `InternalContextFilter` as its sole authentication mechanism — no OAuth2 resource
  server, since it never sees a JWT (D133).
- **Schema (`notification`) gains** `webhook_endpoints`, `webhook_subscriptions`,
  `webhook_events`, `webhook_delivery_attempts`; `webhook_deliveries` is retained and
  re-grained from one row per event to one per `(event, endpoint)`.
- **Kafka**: produces and consumes `webhook.deliveries[.retry|.dlq]` (D106), so webhook
  traffic no longer shares `payment.events.retry`.
- **Delivery** is fanned out to every subscribed, enabled endpoint; signed
  (`PaymentFlow-Signature`, D105); egress-filtered against SSRF; retried on a published
  8-attempt schedule; dead-lettered; and the endpoint auto-disabled after 20 consecutive
  failures.
- **Outbound dependency**: one, on sandbox-service, and deliberately ignorable — every
  failure resolves to "behave normally" (D131 enactment).
- **Deliberately still not done**: no real email transport (D45/Q5 unchanged); no
  `/api/v1` dashboard mirror until M23 (D133); no cursor pagination until M19 (D107).

Closes V1 known issue #2 — a receiving endpoint can now cryptographically verify a webhook
came from this platform, with the algorithm published and independently implemented in two
other languages.

### 2.8 analytics-service (`:8093`)

**Today.** Read-model aggregates. One `merchant_payment_stats` row per
`(merchant_id, currency)`, updated with the same optimistic-lock + whole-transaction-retry
pattern as the ledger (D47). **No query API** — verifying it requires `psql`.

**Schema (`analytics`):** `processed_events`, `merchant_payment_stats` (counts per
transition + `total_captured_amount_minor` / `total_refunded_amount_minor`, unique on
`(merchant_id, currency)`).

**→ V2.** Gains a query API (M19), a new **API-usage read model** fed by the gateway's
request events (M20), and time-bucketed series for the dashboard's charts (M24).

### 2.9 Shared modules

- **`common-dto`** — framework-free data contracts: `ApiError` / `ApiFieldError` (a stable
  machine-readable `code`, with rejected values deliberately omitted so nothing secret
  leaks — D12), `PageResponse`, and `EventEnvelope<T>`:
  `(eventId, eventType, aggregateId, occurredAt, correlationId, payload)`. The envelope
  deliberately does **not** share payload types across services (D36).
  **→ V2:** the envelope gains `mode` and a schema `version` (M16); a `CursorPage<T>`
  joins `PageResponse` (M19).
- **`common-lib`** — a Spring Boot auto-configuration *starter*, not a plain jar; web deps
  are `compileOnly` so the servlet stack is never leaked onto the reactive gateway (D11).
  Provides exception handling, the error envelope wiring, correlation-id filters, JSON
  structured logging, `OpaqueTokenGenerator` (SecureRandom + SHA-256, D27), and
  `ObservabilityAutoConfiguration` tagging every metric with `application=` (D87).
  **→ V2:** gains the internal-context header filter (M15), mode propagation (M16), and
  PII/secret log redaction (M27).
- **`platform-bom`** — dependency version alignment. Deliberately empty of extras.
- **`load-tests`** — Gatling; 7 simulations; a seeded merchant pool feeds all steady-state
  runs so registration overhead never contaminates hot-path numbers (D93).

### 2.10 Infrastructure

| Component | Local | AWS (live, billing continuously) |
|---|---|---|
| PostgreSQL 17 | compose, host `55432`, schema-per-service | RDS `db.t4g.micro`, 17.10, single-AZ |
| Redis | compose, host `56379` | ElastiCache 7.1, TLS-only + AUTH token (D67/D82) |
| Kafka | compose KRaft, host `59092` | **Self-managed single-broker KRaft on ECS Fargate + EFS** (D79) — MSK is blocked account-wide on this AWS account |
| Observability | Prometheus `9091`, Grafana `3002`, Loki, Tempo, Alertmanager | **None deployed** (D84) — CloudWatch Logs only |
| Compute | 12 containers via two merged compose files (D56) | ECS Fargate, 9 tasks, Service Connect for discovery (D70) |
| Edge | — | ALB, one target group (gateway only, D72) |
| IaC | — | Terraform, S3+DynamoDB remote state, one `environments/dev` root (D63/D64) |
| CI/CD | — | GitHub Actions `ci.yml` (builds + tags 8 images, `push:false`); `cd.yml` exists but **has never been run** |

**Kafka topics today:** `payment.events`, `payment.events.retry`, `payment.events.dlq`.
Naming uses dots only, never underscores, to avoid Prometheus metric-name collisions (D10).

**Ports:** gateway `8080`, identity `8081`, merchant `8082`, payment `8083`, transaction
`8084`, audit `8091`, notification `8092`, analytics `8093` (the jump to 8091 avoids
Kafka-UI's `8085` — D48).

### 2.11 V1 gaps V2 must close

Collected here because each becomes a milestone acceptance criterion rather than a
lingering note:

| # | Gap (from V1 §11 Known Issues) | Closed by |
|---|---|---|
| 1 | API keys are issued but authenticate nothing; payment creation is JWT-only (D31/D32) | **M15** |
| 2 | Webhooks are unsigned — merchants cannot verify authenticity | **M18 ✅ closed** (2026-07-25) |
| 3 | transaction-service has no query API; ledger state needs `psql` (D42) | **M19 ✅ closed** (2026-07-26) |
| 4 | audit-service and analytics-service have no query APIs | **M19 ✅ closed** (2026-07-26) |
| 5 | `springdoc` is in the tech-stack table but is not a dependency of any module | **M21** |
| 6 | No README badge target, no diagrams, no frontend | **M23/M24/M30** |
| 7 | Gateway does not honour `X-Forwarded-*` behind the ALB | **M15** (edge work) |
| 8 | Deployed gateway runs `SPRING_PROFILES_ACTIVE=local`, so CORS allows only `localhost:3000` | **M23** |
| 9 | Resilience4j meters are absent from `/actuator/prometheus` despite the dependency being present (V1 §11, re-confirmed during M14) | **M20** (observability work) |
| 10 | Email delivery is simulated only (D45) | **Remains open** — a real provider is still not chosen; see §13-Q5 |
| 11 | The async event pipeline was never *directly* confirmed end-to-end on AWS (no `psql`/ECS Exec access) | **M29** (enable ECS Exec during the V2 deploy) |

---

## 3. New Platform Vision

### 3.1 The developer journey, end to end

This is the narrative every milestone below serves. Each numbered step names the milestone
that makes it real.

**1 — Sign up.** A developer visits the portal, registers with email + password, verifies
their email, and lands in the dashboard. No KYC, no documents, no waiting. *(M23, on
identity-service's existing register/login — M15 adds verification.)*

**2 — Create a merchant account.** One form: business name, contact email, default
currency. On submit, the platform creates the merchant **and immediately issues four
keys** — `pk_test_`, `sk_test_`, `pk_live_`, `sk_live_`. The dashboard opens in **test
mode** by default, and a persistent mode toggle sits in the header. *(M15 + M16 + M23.)*

**3 — Take the first test payment.** The quickstart shows a five-line snippet. The
developer copies their `sk_test_` key, runs it, and sees the payment appear in the
dashboard within a second — with its full lifecycle, the events it emitted, and the raw
request/response that produced it. *(M15, M19, M20, M23, M25.)*

```bash
curl https://api.paymentflow.dev/v1/payments \
  -H "Authorization: Bearer sk_test_..." \
  -H "Idempotency-Key: 1f9c1c2e-..." \
  -d amount=2000 -d currency=USD -d "payment_method=pm_card_visa"
```

**4 — Integrate with an SDK.** `npm i paymentflow` / `pip install paymentflow`. The SDK
handles auth, generates idempotency keys automatically, retries safely with backoff, and
auto-paginates lists. *(M22, then M26 for Java/Go.)*

**5 — Receive webhooks.** The developer adds an endpoint URL in the dashboard, picks which
event types to subscribe to, and copies the endpoint's `whsec_` signing secret. Every
delivery is signed; the SDK ships a one-line verification helper. The dashboard shows every
attempt, the exact request and response, and a **Replay** button. *(M18, M24.)*

**6 — Test failure.** The developer needs to prove their integration handles a decline, a
timeout, and a duplicate webhook. They use `pm_card_chargeDeclined`, set a simulation
override for latency, and click "resend" on a past delivery. All three are deterministic
and repeatable. *(M17, M18.)*

**7 — Watch usage.** The developer dashboard shows requests per endpoint, error rates,
p95 latency, rate-limit headroom, and the raw request log with searchable filters —
scoped to the current mode. *(M20, M24.)*

**8 — Go "live."** Flipping the toggle and swapping `sk_test_` for `sk_live_` changes
nothing about the code. Live mode routes through a simulated acquirer with realistic
latency and a realistic decline rate, and writes to a completely separate data plane.
*(M16, M17.)*

### 3.2 Capability map

Each capability, what it means concretely, and where it lands.

| Capability | Concretely | Milestone |
|---|---|---|
| **Register** | Email/password signup, email verification, password reset, session tokens | M15, M23 |
| **Create merchant** | Self-serve, immediate, no KYC; auto-issues the four starter keys | M15, M23 |
| **Generate API keys** | Named, scoped, mode-specific; secret shown exactly once; `last_used_at`; revoke and rotate with an optional grace window | M15 |
| **Create test payments** | The existing FSM, now reachable with a secret key and partitioned by mode | M15, M16 |
| **Capture / refund / void** | Unchanged V1 semantics, exposed on the public API with `refunds` as a first-class sub-resource | M19 |
| **Receive webhooks** | Multi-endpoint, subscription-filtered, HMAC-signed, retried, logged, replayable | M18 |
| **View logs** | API request log (redacted bodies), webhook delivery log, event log | M19, M20, M24 |
| **Rotate keys** | Rotate-with-grace (old key valid for N hours) rather than V1's immediate revoke | M15 |
| **SDK examples** | Every API-reference page renders the same call in curl / Node / Python / Java / Go | M22, M25, M26 |
| **Sandbox mode** | The default; a full test data plane with its own ledger, events, and webhooks | M16 |
| **Simulate failures** | Test cards, forced error codes, injected latency, forced timeouts, duplicate events | M17 |
| **Dashboards** | Merchant, developer, admin, analytics — one app, RBAC-gated | M23, M24 |
| **Monitor API usage** | Per-key/per-endpoint counts, error rates, latency percentiles, quota headroom | M20, M24 |
| **Build demos without KYC** | The whole platform, by design | throughout |

### 3.3 Public API shape

V2 introduces a **new public API surface** alongside V1's internal one. The distinction is
deliberate and load-bearing:

- **`/v1/*` — the public, API-key-authenticated, versioned, documented, SDK-targeted API.**
  This is the contract external developers depend on. It changes only under M21's
  versioning policy.
- **`/api/v1/*` — V1's existing JWT-authenticated API.** Retained unchanged. It becomes
  the **dashboard's** API (a browser session is a JWT, not a secret key — you must never
  ship a secret key to a browser). Not documented publicly, not SDK-targeted, free to
  change alongside the portal.
- **`/internal/v1/*` — service-to-service only.** Never routed through the ALB; never
  matched by any gateway path predicate. Used for API-key verification (M15) and any
  future internal contract.

This three-tier split is the single most important structural decision in V2 (D98). It
means the public contract can be frozen and versioned without freezing the dashboard's
iteration speed, and it makes "is this endpoint a public promise?" answerable by looking at
the path.

**Resource naming** follows the Stripe convention V2 is modelled on: plural, lowercase,
snake_case fields, prefixed typed object ids (`pay_`, `re_`, `evt_`, `we_`, `whsec_`,
`sk_`, `pk_`), an `object` discriminator on every response, ISO-4217 currency, integer
minor units, and RFC-3339 UTC timestamps. Every object carries a free-form `metadata` map.

---

## 4. Architecture Changes

### 4.1 Target architecture

```
        Browser (developer-portal, Next.js)          Developer's server (SDK / curl)
                    | session JWT                              | Authorization: Bearer sk_*
                    | /api/v1/*                                | /v1/*
                    +----------------+-------------------------+
                                     v
              +------------------------------------------------------+
              |              gateway-service (:8080)                  |
              |  1. strip all inbound X-PF-Internal-* headers         |
              |  2. credential detect: JWT (3 segments) vs sk_/pk_    |
              |  3. API key -> Redis lookup -> merchant-service verify|
              |  4. per-key rate limit + quota (mode-aware)           |
              |  5. inject HMAC-signed internal context header        |
              |  6. emit api.request.events (async, non-blocking)     |
              +-------+----------------------------------------------+
                      |
   +----------+-------+----+------------+-------------+--------------+
   v          v            v            v             v              v
identity   merchant     payment    transaction     audit        analytics
:8081      :8082        :8083      :8084 *API      :8091 *API   :8093 *API
users      merchants,   FSM,       ledger +        events API   stats +
sessions   API keys *,  outbox,    balance API                  usage API *
           webhook      mode *,
           endpoints *  metadata *
                            |
                            | test mode: authorize/capture outcome
                            v
                   * sandbox-service (:8094)  -- NEW --
                     test cards, outcome rules, forced failures,
                     injected latency, delayed settlement scheduler
                            |
                            v
        Kafka: payment.events . payment.events.retry . payment.events.dlq
               * webhook.deliveries . * webhook.deliveries.retry/.dlq
               * api.request.events . * sandbox.scheduled.events
                            |
                            v
                notification-service :8092  * webhook subsystem
                  endpoints, subscriptions, HMAC signing,
                  delivery log *API, replay *API, auto-disable

              * = new or substantially extended in V2
```

### 4.2 New service: `sandbox-service` (`:8094`)

The only new backend service in V2. It exists because **simulating an acquirer is a
genuinely different domain from orchestrating a payment**, and folding it into
payment-service would put "what would a bank do?" logic inside the FSM that must remain
provider-agnostic.

**Responsibilities**
- Own the **test card catalogue** — a token (`pm_card_visa`, `pm_card_chargeDeclined`, ...)
  maps deterministically to an authorization outcome, a decline code, and a latency profile.
- Evaluate an **authorization decision**: approve, decline (with a specific code), error,
  or *delay* — given the payment method token, amount, currency, merchant, mode, and any
  active per-merchant simulation override.
- Model the **simulated acquirer** used by *live* mode: a small realistic decline rate,
  a realistic latency distribution, and occasional transient errors — so live mode is not
  simply "test mode with a different label."
- Run the **delayed-outcome scheduler**: for scenarios where authorization or capture
  settles asynchronously seconds later, emit the deferred outcome onto
  `sandbox.scheduled.events`.
- Serve **simulation controls**: per-merchant, per-mode overrides that force the next N
  requests to fail a particular way (the "chaos knob" a developer needs to test their
  error paths without hunting for the right test card).

**Deliberately NOT its responsibilities:** it never mutates a payment, never writes to the
ledger, never publishes a `payment.*` event. It answers a question; payment-service decides
what to do with the answer and remains the sole owner of the FSM. This keeps the FSM's
invariants in exactly one place, which is the property M14's load testing depended on.

**Schema (`sandbox`):** `test_cards` (seeded reference data), `simulation_overrides`
(merchant, mode, scenario, remaining count, expiry), `scheduled_outcomes` (payment,
fire-at, outcome, delivered flag), `decision_log` (append-only, backing the dashboard's
"why was this declined?" panel).

**Why a service and not a library.** Three reasons: overrides are stateful and must be
settable from the dashboard (so they need an API and a store); the delayed-outcome
scheduler needs to run independently of any request; and keeping it separate means the
"real acquirer integration" seam in a hypothetical V3 is already a network boundary rather
than a refactor.

### 4.3 Authentication: the API-key path

The central new mechanism. Sequence for a call carrying `Authorization: Bearer sk_test_...`:

```
1. Gateway strips every inbound X-PF-Internal-* header, unconditionally, before
   any other filter runs. A client can never forge internal context.
2. Credential detection: three dot-separated Base64URL segments -> JWT path (V1,
   unchanged). Otherwise -> API-key path.
3. Compute sha256(raw_key). Look up Redis `apikey:v1:<sha256>`.
   HIT  -> merchant context (merchantId, mode, keyId, scopes, status)   [~1 ms]
   MISS -> OpenFeign POST /internal/v1/api-keys/verify on merchant-service,
           wrapped in the same Resilience4j chain shape as M8's MerchantResolver.
           Cache positive results with a short TTL; cache negatives briefly too,
           so key enumeration cannot be turned into a DB-load amplifier.
4. Enforce scope for the route, then the per-key rate limit and quota.
5. Inject the internal context as headers, plus an HMAC-SHA256 signature over
   (merchantId | mode | keyId | scopes | issuedAtEpochSecond) using a shared
   secret from Secrets Manager, with a short validity window.
6. Downstream, a common-lib servlet filter verifies that signature and populates
   a request-scoped MerchantContext. An unsigned or stale header is rejected 401.
7. Asynchronously emit an api.request.event (never on the request's critical path).
```

**Why signed headers rather than the alternatives** (full reasoning in D100): having each
service verify the key itself would preserve strict zero trust but multiply the
verification hop by every service on the path and require an API-key filter in five
services; exchanging the key for a short-lived internal JWT at the edge is arguably the
"most correct" design and remains the documented upgrade path, but it introduces a
token-authority round trip and a new cycle between identity-service and merchant-service
that is not worth paying for at this platform's scale. Signed headers give a *verifiable*
assertion — not merely a trusted one — for one HMAC per hop, and preserve V1's D17
zero-trust posture in substance: no service takes the gateway's word for anything it cannot
itself check.

**Key format.** `{pk|sk}_{test|live}_{24-char base62}`, with a stored 12-character visible
prefix (`sk_test_a1b2c3`) for display and support. Only `sha256(raw)` is persisted, reusing
`OpaqueTokenGenerator` (D27) exactly as V1's refresh tokens and API keys already do.
Publishable (`pk_`) keys are read-only and safe for a browser; secret (`sk_`) keys are
full-access and must never reach one. The dashboard shows a secret exactly once, at
creation.

### 4.4 Test/live mode isolation

Mode is not a filter that queries remember to apply — it is a **structural property**.

- **Every merchant-scoped table** in payment, transaction, audit, notification, analytics,
  and sandbox gains a `mode VARCHAR(4) NOT NULL` (`test` | `live`), and every uniqueness
  constraint that includes `merchant_id` gains `mode` alongside it. Concretely: the
  idempotency key becomes unique on `(merchant_id, mode, idempotency_key)`; ledger accounts
  become unique on `(account_type, owner_id, currency, mode)`; the platform clearing
  account exists once **per currency per mode**.
- **`EventEnvelope` gains `mode`** (and a `schemaVersion`), so every consumer receives it
  without a lookup and cannot accidentally cross-post.
- **Enforcement is centralised**, not repeated: a Hibernate `@Filter` (or an equivalent
  repository-level base specification) applies the current request's mode to every
  merchant-scoped query automatically, so a developer writing a new repository method
  cannot forget it. A cross-mode read returns **404, never 403** — a `sk_test_` key must
  not be able to confirm that a live object exists.
- **Idempotency keys, webhook endpoints, signing secrets, rate-limit buckets, and usage
  counters are all mode-scoped.** The same idempotency key in test and live are different
  keys.
- **The migration is additive:** existing V1 rows backfill to `mode='live'` (they were
  produced by the only mode that existed), so no data is lost and no constraint breaks.

### 4.5 Webhooks

From "the merchant's one URL" to a subsystem.

- **`webhook_endpoints`** — many per merchant, per mode: URL (HTTPS only), description,
  enabled flag, API version pin, `whsec_` signing secret (hashed at rest, revealed once),
  consecutive-failure counter, auto-disable timestamp.
- **`webhook_subscriptions`** — which event types each endpoint receives. `*` is allowed.
- **`webhook_events`** — the canonical, merchant-facing event object (`evt_...`), distinct
  from the internal Kafka envelope. This is what appears in the Events API *and* in the
  webhook body, so "what the dashboard shows" and "what the endpoint received" are the same
  object by construction.
- **`webhook_delivery_attempts`** — one row per attempt: request headers/body, response
  status/headers/body (truncated), duration, error. This is the delivery log the dashboard
  renders and the SDK docs reference.

**Signature.** `PaymentFlow-Signature: t=<unix>,v1=<hex hmac_sha256(secret, "t.body")>`.
The timestamp is inside the signed payload, and receivers reject a timestamp outside a
tolerance window — which is what actually prevents replay. Secret rotation supports two
active secrets briefly so an endpoint can roll without dropping deliveries.

**Retries.** An explicit schedule (roughly 0s, 5s, 30s, 2m, 10m, 1h, 6h — 8 attempts over
~24h), continuing V1's D46 pattern of a hand-rolled retry/DLQ topic pair rather than
`@RetryableTopic`, on dedicated `webhook.deliveries[.retry|.dlq]` topics so webhook traffic
no longer shares `payment.events.retry` with unrelated concerns.

**Auto-disable.** After N consecutive failures across distinct events, the endpoint is
disabled and the merchant is notified. This protects the platform from spending its retry
budget on an endpoint that has been dead for a week.

### 4.6 New and changed data stores

**New schemas:** `sandbox`.

**New tables by schema**

| Schema | New tables | Purpose |
|---|---|---|
| `identity` | `email_verifications`, `password_resets` | Self-serve signup completion |
| `merchant` | `api_keys` **rebuilt** (mode, name, scopes, `last_used_at`, `expires_at`), `merchant_settings` | Multi-key model, defaults, branding |
| `payment` | `refunds`, `payment_methods` (test tokens) | First-class refund objects; method tokens |
| `notification` | `webhook_endpoints`, `webhook_subscriptions`, `webhook_events`, `webhook_delivery_attempts` | The webhook subsystem |
| `analytics` | `api_request_log`, `api_usage_daily`, `payment_stats_hourly` | Usage metering + dashboard series |
| `sandbox` | `test_cards`, `simulation_overrides`, `scheduled_outcomes`, `decision_log` | Simulation engine |

**Column added everywhere merchant-scoped:** `mode`, plus `metadata jsonb` on the
developer-visible objects (payments, refunds, endpoints).

**Retention.** `api_request_log` is the only table with genuinely high write volume. It is
partitioned by day and pruned on a schedule (30 days), with aggregates rolled into
`api_usage_daily` before pruning — decided now rather than discovered later under load.

### 4.7 New Kafka topics

| Topic | Producer | Consumers | Notes |
|---|---|---|---|
| `webhook.deliveries` | notification-service | notification-service | Separates webhook retry traffic from payment events |
| `webhook.deliveries.retry` / `.dlq` | notification-service | notification-service | D10 naming; explicit backoff schedule |
| `api.request.events` | gateway-service | analytics-service | High volume; fire-and-forget, never blocks a response |
| `sandbox.scheduled.events` | sandbox-service | payment-service | Deferred authorization/capture outcomes |
| `merchant.events` | merchant-service | analytics, audit, notification | Merchant/key lifecycle — needed so audit can record key creation and revocation |

Existing `payment.events` is unchanged in name and topology; its envelope gains `mode` and
`schemaVersion` (backwards-compatible additive fields).

### 4.8 New Redis usage

| Key pattern | Purpose | TTL |
|---|---|---|
| `apikey:v1:<sha256>` | Verified merchant context for a key | short, with brief negative caching |
| `ratelimit:key:<keyId>:<window>` | Per-key token bucket, replacing per-user for API traffic | window |
| `quota:<merchantId>:<mode>:<day>` | Daily request quota counter | 48h |
| `idem:<merchantId>:<mode>:<key>` | Existing idempotency lock, now mode-namespaced | short |
| `sim:<merchantId>:<mode>` | Active simulation override | override-defined |
| `webhook:endpoints:<merchantId>:<mode>` | Endpoint + subscription list for fan-out | minutes, evicted on change |
| `session:<sessionId>` | Portal session revocation list | session lifetime |

All caches follow V1's D30 rule: cache immutable response DTOs, never JPA entities, with a
type-aware serializer (D38).

### 4.9 Security changes

- **Two distinct credential types with different blast radii**: session JWTs (browser,
  short-lived, refreshable, revocable) and secret API keys (server, long-lived, scoped,
  revocable). A secret key is never accepted from a browser origin; a session JWT is never
  accepted on `/v1/*`.
- **Scopes** on keys (`payments:read`, `payments:write`, `refunds:write`, `webhooks:manage`,
  `logs:read`, ...) enforced at the gateway, so a compromised read-only key cannot move
  money.
- **Signed internal context** (§4.3) — no service trusts an unverifiable header.
- **Secret handling**: every secret (`sk_`, `whsec_`, session refresh) is stored only as
  SHA-256, shown exactly once, and displayed thereafter as a prefix. Log redaction in
  common-lib scrubs anything matching the key patterns before a line is written.
- **Rate limiting becomes multi-dimensional**: per key, per merchant, per mode, per IP for
  unauthenticated routes, with `RateLimit-Limit` / `-Remaining` / `-Reset` response headers
  so SDKs can back off intelligently rather than blindly.
- **Portal**: `SameSite=Strict` refresh cookie, CSRF token on state-changing dashboard
  calls, strict CSP, and the deployed-gateway CORS misconfiguration (V1 known issue) fixed.
- **Threat model written down** in M27, covering key leakage, webhook SSRF (a merchant can
  point an endpoint at `169.254.169.254` — outbound requests must be egress-filtered and
  private ranges blocked), enumeration, mode-boundary escape, and replay.

### 4.10 API versioning

- **Date-based versions** (`PaymentFlow-Version: 2026-08-01`), pinned per merchant at first
  call and overridable per request — the model that lets a platform evolve without
  coordinating upgrades with every integrator.
- The URL stays `/v1/` permanently; `v1` denotes the API *family*, the header denotes the
  *revision*. A `v2` path would only ever appear for a total redesign.
- **Additive changes are never breaking** and ship unversioned: new fields, new endpoints,
  new event types, new enum values. Clients and SDKs must tolerate unknown fields and
  unknown enum values — this is stated in the SDK contract and tested.
- **Breaking changes** require a new dated version plus a request/response transformation
  layer at the edge for the previous one, and a published deprecation timeline with
  `Deprecation` / `Sunset` headers.
- CI diffs the generated OpenAPI spec against the committed baseline and **fails the build**
  on an undeclared breaking change (M21). This is the mechanism that makes the policy real
  rather than aspirational.

### 4.11 Frontend

One Next.js (App Router) + TypeScript + Tailwind application, `developer-portal`, serving
four authenticated surfaces behind RBAC and one public surface:

- **Public** — landing page, docs, API reference, quickstarts (SSG).
- **Merchant dashboard** — payments, refunds, balance, customers-lite.
- **Developer console** — API keys, webhooks, request logs, events, simulation controls.
- **Analytics** — volume, success rate, latency, error breakdown, usage.
- **Admin** — all merchants, platform health, DLQ inspection, feature flags.

Rationale for one app: a single auth session, a single build and deploy target, one design
system, and shared components between docs and dashboard (the interactive API console needs
the dashboard's key picker). Route groups and RBAC keep the surfaces cleanly separated;
admin routes are additionally server-side gated so an admin bundle is never served to a
non-admin.

---

## 5. Milestone Roadmap

### 5.0 Overview and sequencing

Sixteen milestones, **M15 through M30**. The ordering is not arbitrary — it follows one
rule: *nothing is built before the thing it depends on is real*. Concretely, that produces
four phases.

| Phase | Milestones | Why this phase exists |
|---|---|---|
| **A — Platform foundations** | M15 API-key auth · M16 mode isolation · M17 sandbox engine · M18 webhooks | These four change the *shape of every request and every row*. Building anything on top of the platform before they exist means building it twice. |
| **B — Product surface** | M19 read APIs · M20 usage metering · M21 OpenAPI + versioning | The API surface external developers actually consume, and the contract that freezes it. |
| **C — Consumption** | M22 Node/Python SDKs · M23 portal part 1 · M24 portal part 2 · M25 docs · M26 Java/Go SDKs | Everything that *uses* the API. Deliberately after the contract is stable, so none of it gets rewritten. |
| **D — Production readiness** | M27 security · M28 performance · M29 AWS deploy · M30 launch | Hardening, measurement, deployment, and the artefacts that make the work legible. |

**Dependency graph**

```
M15 ──┬──> M16 ──┬──> M17
      │          ├──> M19 ──┬──> M23 ──> M24
      │          └──> M18 ──┘     │       │
      └──> M20 ──────────────────>┘       │
                    │                     │
                    └──> M21 ──> M22 ──┬──> M25
                                       └──> M26
                                              │
   (all of the above) ──> M27 ──> M28 ──> M29 ──> M30
```

**Critical path:** M15 → M16 → M19 → M23 → M24 → M27 → M28 → M29 → M30. M17, M18, M20,
M21, M22, M25, and M26 have slack and can absorb schedule pressure without stalling the
chain.

**Standing rules for every milestone** (inherited from V1, restated because they are the
working agreement, not decoration):

1. **One milestone at a time, gated on explicit approval.** No milestone begins until the
   previous one is confirmed complete by the user.
2. **Verify, never assume.** Every completion claim is backed by something actually run —
   a test, a real HTTP call, a `psql` query, a log line. "It should work" is not evidence.
   V1's §16 Lessons Learned exist because this rule repeatedly caught real bugs.
3. **A new service's manual end-to-end pass is also a regression check on everything it
   calls** (V1's M4/M5 cache-aside lesson).
4. **No duplicated code.** If a pattern appears a third time, it moves into `common-lib`.
5. **Nothing costly or irreversible without explicit approval** — `terraform apply`,
   pushing to a public package registry, publishing a public URL.
6. **This document is updated at the end of every milestone**, per §16.

---

### M15 — API Key Authentication & Machine-to-Machine Access

> *The milestone that turns a payments backend into a platform. Nothing else in V2 can
> begin until an external server can authenticate without a human logging in.*

**Objective.** Make API keys the primary authentication mechanism for the public API.
Rebuild the key model to be multi-key, scoped, and mode-aware; teach the gateway to
authenticate a key and assert a verifiable merchant context downstream; and establish the
`/v1/*` public API surface alongside V1's `/api/v1/*`.

**Why this milestone exists.** V1 issues API keys that authenticate *nothing* — D31
explicitly declined to build a verification endpoint because no caller existed, and D32
routed payment creation through JWT-via-gateway instead. Both deferrals were correct at the
time and are now resolved by a real caller: every external developer. Until this lands,
"external developers integrate with our REST API" is impossible, so every other V2
capability is blocked behind it.

**Features**
- Multi-key model: many keys per merchant, each with a name, mode, scopes, `last_used_at`,
  optional expiry, and independent revocation.
- Four key types: `pk_test_`, `sk_test_`, `pk_live_`, `sk_live_`. Publishable keys are
  read-only; secret keys are full-access.
- Key management API (JWT-authenticated, dashboard-facing): create, list, reveal-once,
  rotate-with-grace, revoke.
- Internal verification endpoint on merchant-service (`/internal/v1/api-keys/verify`).
- Gateway API-key authentication filter with Redis caching and negative caching.
- HMAC-signed internal merchant-context headers, verified by a `common-lib` filter.
- Scope enforcement per route at the edge.
- The `/v1/*` route family, initially proxying payments.
- Email verification and password reset on identity-service (self-serve signup completion).
- `merchant.events` Kafka topic for key/merchant lifecycle, consumed by audit-service.

**Implementation tasks**
1. **merchant-service** — Flyway `V3__api_keys_v2.sql`: rebuild `api_keys` with `mode`,
   `name`, `scopes`, `last_used_at`, `expires_at`, `revoked_at`, `grace_expires_at`; drop
   V1's single-active-key partial unique index (D29 is superseded — record why); backfill
   every existing key to `mode='live'`, scope `*`, name `"Legacy key"`.
2. **merchant-service** — `ApiKeyService` rewrite: issue by type+mode, hash with
   `OpaqueTokenGenerator` (unchanged), rotate-with-grace, revoke; `last_used_at` written
   asynchronously and throttled (never one UPDATE per request).
3. **merchant-service** — `POST /internal/v1/api-keys/verify`: constant-time hash lookup
   returning `{merchantId, keyId, mode, scopes, status}`; deliberately no other internal
   surface.
4. **merchant-service** — key management endpoints under `/api/v1/merchants/me/api-keys`.
5. **gateway-service** — `ApiKeyAuthenticationWebFilter`: strip inbound `X-PF-Internal-*`
   first, detect credential type, Redis lookup, Feign fallback wrapped in the M8 resilience
   chain shape, populate a reactive security context.
6. **gateway-service** — internal-context header injection with HMAC-SHA256 signature and a
   short validity window; secret sourced from Secrets Manager in AWS, `.env` locally.
7. **gateway-service** — scope-to-route mapping; 403 with a stable error code on
   insufficient scope; `/v1/**` route family added.
8. **gateway-service** — fix `X-Forwarded-*` handling (`trusted-proxies`) — the V1 known
   issue, fixed here because this is the milestone that touches the edge.
9. **common-lib** — `InternalContextFilter` (servlet): verify the HMAC, reject unsigned or
   stale, populate a request-scoped `MerchantContext`; auto-configured, `@ConditionalOn`
   servlet, matching D11's existing pattern.
10. **payment-service** — accept `MerchantContext` from the filter as an *alternative* to
    the existing JWT-derived merchant resolution; the Feign call to merchant-service is
    skipped entirely when the gateway already resolved the merchant (a real latency win on
    every API-key request).
11. **identity-service** — `email_verifications` and `password_resets` tables plus
    endpoints; verification email goes through notification-service's existing simulated
    channel.
12. **merchant-service** — publish `merchant.events` on merchant and key lifecycle;
    audit-service subscribes.

**Testing strategy**
- *Unit*: key generation format and entropy; prefix extraction; hash comparison is
  constant-time; scope matching including wildcards; HMAC signing and verification
  including a deliberately stale timestamp.
- *Integration (Testcontainers)*: full key lifecycle against real Postgres; verification
  endpoint against real data; Redis cache hit/miss/negative-cache paths.
- *Gateway (WebFlux + embedded Redis/wiremock)*: JWT path still works unchanged
  (**regression — this is the highest-risk part of the milestone**); API-key path succeeds;
  revoked key 401; wrong-scope 403; forged `X-PF-Internal-*` header is stripped and the
  request is rejected, not silently trusted.
- *Manual E2E*: register → create merchant → create `sk_test_` key → `curl /v1/payments`
  with only that key → payment created. Then revoke the key and confirm the very next
  request fails, proving cache invalidation works rather than just the happy path.
- *Regression*: the entire V1 Gatling suite must still pass unchanged on the JWT path.

**Completion criteria**
- [ ] A payment can be created end-to-end with **only** a secret key — no JWT anywhere.
- [ ] A `pk_` key cannot write; a revoked key fails within the cache TTL; a wrong-scope key
      gets a 403 with a documented error code.
- [ ] A client-supplied `X-PF-Internal-*` header can never reach a downstream service —
      demonstrated by an actual forged request, not by reading the filter code.
- [ ] Every V1 JWT flow works exactly as before; V1's full test suite and Gatling
      simulations are green.
- [ ] Key verification adds < 5 ms p99 on a cache hit, measured.

**Deliverables.** merchant-service key subsystem + internal API; gateway auth filter and
`/v1` routes; `common-lib` internal-context filter; identity-service verification/reset;
`merchant.events`; migrations; tests; this document updated.

**Dependencies.** None beyond V1. **This milestone blocks everything else in V2.**

**Risks**
| Risk | Mitigation |
|---|---|
| Breaking V1's JWT path while adding a second credential type at the edge | Credential *detection* is a pure function with its own unit tests; the JWT filter chain is untouched, not refactored; V1's Gatling suite is the regression gate |
| Cached key context outlives a revocation | Short TTL plus explicit Redis eviction on revoke/rotate; the manual E2E explicitly tests revoke-then-immediately-call |
| Signed internal headers become a shared-secret sprawl problem | Exactly one secret, sourced from Secrets Manager, injected identically to every service (D73's existing pattern); rotation procedure documented |
| `last_used_at` turns every request into a write | Throttled async update (at most once per key per minute), never inline |

**Engineering notes.** D29 ("single active key per merchant, enforced by a partial unique
index") is deliberately **superseded**, not violated — a developer platform requires
multiple concurrent keys by definition, and rotate-with-grace is impossible under a
single-active-key constraint. This is the first V1 decision V2 overturns, and it is
overturned for a stated reason with a recorded successor decision (D99), which is the
pattern every future supersession should follow.

---

### M16 — Test / Live Mode Isolation

> *The milestone that makes "sandbox" structurally true rather than a label.*

**Objective.** Introduce `mode` as a first-class, structurally enforced property of every
merchant-scoped row, event, cache key, and rate-limit bucket, such that test and live data
are two disjoint data planes that share code but never share state.

**Why this milestone exists.** A sandbox that is merely "the same data with a flag" is not
a sandbox — a developer testing a refund loop would corrupt real aggregates, and a leaked
test key would expose live data. Doing this *now*, immediately after keys and before any
read API, ledger extension, or dashboard exists, is deliberate: every subsequent milestone
gets mode for free, and no query written later has to be retrofitted. Retrofitting mode
after M19–M24 would mean auditing every repository method in the platform.

**Features**
- `mode` column on every merchant-scoped table across six schemas, with `NOT NULL` and a
  check constraint.
- Composite uniqueness: idempotency `(merchant_id, mode, key)`; ledger accounts
  `(type, owner, currency, mode)`; per-mode `PLATFORM_CLEARING`.
- `EventEnvelope` gains `mode` and `schemaVersion` (additive, backwards compatible).
- Automatic mode scoping at the persistence layer — not per-query.
- Cross-mode access returns 404, never 403.
- Mode-namespaced Redis keys, rate-limit buckets, and idempotency locks.
- Mode visible on every API response and in every log line's MDC.

**Implementation tasks**
1. Flyway migrations in payment, transaction, audit, notification, analytics: add `mode`,
   backfill `'live'`, add the constraint, rebuild the composite unique indexes. Each
   migration is written to be safely re-runnable against a non-empty database.
2. `common-dto`: add `mode` + `schemaVersion` to `EventEnvelope`; consumers tolerate their
   absence (defaulting to `live`) so an in-flight message from before the deploy is not
   poison.
3. `common-lib`: `ModeContext` (request-scoped, populated by `InternalContextFilter`), a
   Hibernate `@Filter` enabler bound to it, and a `ModeAware` marker for entities.
   Publishing a Kafka event without a mode throws — a loud failure, not a silent default.
4. payment-service: mode on payments, idempotency, and outbox; mode carried into events.
5. transaction-service: per-mode accounts; the `PLATFORM_CLEARING` singleton becomes one
   per currency **per mode**; verify the net-to-zero invariant independently in each mode.
6. notification-service / analytics-service / audit-service: mode on rows, mode in
   consumer dedup, mode in every aggregate key.
7. gateway-service: mode from the key drives the rate-limit bucket and quota key.
8. Logging: `mode` into MDC alongside `correlationId` so every log line is attributable.

**Testing strategy**
- *Unit*: the Hibernate filter is applied to every `ModeAware` repository — enforced by a
  reflective test that fails if a new `ModeAware` entity is added without it. This is the
  test that keeps the guarantee true in a year.
- *Integration*: create identical objects in both modes with the same idempotency key and
  confirm both succeed independently; confirm a test-mode read of a live id returns 404;
  confirm ledger accounts are disjoint and each mode nets to zero independently.
- *Migration*: run against a database seeded with V1-era data; confirm every row lands in
  `live` and no constraint is violated.
- *E2E*: run the full V1 lifecycle twice, once per mode, and confirm complete separation in
  the ledger, audit log, aggregates, and webhook deliveries — verified by `psql`, not by
  the API alone.

**Completion criteria**
- [ ] No merchant-scoped table lacks `mode`; verified by an automated schema assertion, not
      by inspection.
- [ ] A `sk_test_` key cannot observe the existence of a live object (404, not 403).
- [ ] Both modes' ledgers independently net to zero after a fully refunded lifecycle.
- [ ] The reflective "every `ModeAware` entity is filtered" test passes.
- [ ] Existing V1 data is intact and attributed to `live`.

**Deliverables.** Migrations across five schemas; `ModeContext` + filter infrastructure in
`common-lib`; envelope change; updated consumers; the schema-completeness assertion.

**Dependencies.** M15 (mode originates from the key).

**Risks**
| Risk | Mitigation |
|---|---|
| A query somewhere forgets mode and leaks data across the boundary | Enforcement is centralised at the persistence layer, plus a reflective test that fails on any unfiltered `ModeAware` entity |
| Migration breaks live V1 data | Additive-only, backfill to `live`, tested against a seeded copy first; no destructive statements |
| In-flight Kafka messages lack `mode` during rollout | Consumers default a missing mode to `live` — the only correct value for a message produced before the field existed |
| Per-mode clearing accounts break M6's net-to-zero invariant | The invariant is re-asserted per mode in tests; it is the correctness property the ledger exists to guarantee |

**Engineering notes.** The 404-not-403 choice matters more than it looks: returning 403
would confirm that an object exists in the other mode, which is an information leak across
the exact boundary this milestone builds. V1 already made the same call for cross-merchant
access (D28's "404-masking"), so this is consistency with an established decision rather
than a new one.

---

### M17 — Sandbox Simulation Engine (`sandbox-service`)

> *The milestone that makes failure requestable.*

**Objective.** Introduce `sandbox-service`, the platform's simulated acquirer and scenario
engine, and route payment authorization decisions through it so that outcomes — approval,
decline, error, delay — become deterministic and developer-controllable.

**Why this milestone exists.** A developer's integration is only as good as its error
handling, and error handling cannot be built against a system that always succeeds. Every
real payment sandbox ships test cards and forced-failure controls for exactly this reason.
It also closes a genuine gap in V1: the FSM has `FAILED` and decline paths that no test has
ever driven from the outside, because nothing could make an authorization fail on demand.

**Features**
- Test card catalogue mapping tokens to deterministic outcomes and latency profiles.
- Authorization decision API returning approve / decline+code / error / delay.
- Simulated acquirer for live mode: realistic decline rate, latency distribution, transient
  errors — so live mode differs observably from test mode.
- Per-merchant, per-mode simulation overrides ("fail the next 5 authorizations with
  `insufficient_funds`", "add 3s latency", "time out").
- Delayed-outcome scheduler emitting to `sandbox.scheduled.events`.
- Append-only decision log powering the dashboard's "why was this declined?" panel.
- Simulation control API for the dashboard.

**Implementation tasks**
1. New Gradle module `sandbox-service` on port `8094`; the shared parameterized Dockerfile
   (D53) needs only build args, and `docker-compose.yml` gains one service.
2. Flyway `sandbox` schema; seed `test_cards` as reference data via migration so the
   catalogue is versioned rather than hand-inserted.
3. `DecisionEngine`: override → test card → mode default, in that precedence order, as a
   pure function over its inputs so it is exhaustively unit-testable.
4. `POST /internal/v1/sandbox/authorize` — internal only, never publicly routed.
5. `/v1/test/simulations` — public, key-authenticated simulation controls.
6. payment-service: call sandbox-service during authorize (and capture, where a scenario
   defers it), wrapped in the M8 Resilience4j chain shape. **A sandbox failure must never
   corrupt the FSM** — a timeout or unavailability degrades to a deterministic
   `processing_error`, never an ambiguous state.
7. Delayed outcomes: scheduler publishes; payment-service consumes and applies the deferred
   transition through the *same* FSM guard as a synchronous one.
8. Decline codes mapped to the platform's stable error-code catalogue.

**Testing strategy**
- *Unit*: the decision engine across the full matrix of card × override × mode, including
  precedence conflicts; latency profiles; expiry of overrides.
- *Integration*: authorization decisions against real Postgres; scheduler fires and the
  deferred outcome lands on Kafka.
- *Cross-service*: payment-service correctly applies each outcome — approved →
  `AUTHORIZED`, declined → `FAILED` with the decline reason recorded, delayed → stays
  `CREATED` then transitions on the deferred event.
- *Resilience*: sandbox-service stopped mid-flight; payment-service degrades cleanly (the
  same manual verification M8 performed against merchant-service).
- *E2E*: every documented test card driven end-to-end through the public API and confirmed
  to produce its documented outcome — the documentation is verified, not asserted.

**Completion criteria**
- [ ] Every published test card produces its documented outcome, verified by a real call.
- [ ] A simulation override forces failures for exactly the requested count, then expires.
- [ ] A delayed scenario settles asynchronously and the payment reaches the correct state.
- [ ] sandbox-service being down degrades payments gracefully and never leaves a payment in
      an ambiguous state.
- [ ] Live mode's simulated acquirer produces a measurably different outcome distribution
      from test mode.

**Deliverables.** `sandbox-service` (module, schema, Dockerfile args, compose entry);
payment-service integration; `sandbox.scheduled.events`; the seeded test-card catalogue;
the decision log.

**Dependencies.** M16 (decisions are mode-dependent).

**Risks**
| Risk | Mitigation |
|---|---|
| A new synchronous hop on the payment hot path adds latency | Resilience4j-wrapped with a tight timeout; measured in M28; the decision itself is an in-memory lookup |
| sandbox-service becomes a single point of failure for payments | Explicit deterministic degradation; never an ambiguous FSM state; exercised by stopping the service for real |
| Simulation state leaks between merchants or modes | Overrides are keyed by `(merchantId, mode)` and inherit M16's isolation guarantees |
| Test-card semantics drift from the docs | The E2E test *is* the documentation check — every published card is exercised |

**Engineering notes.** Keeping the FSM's ownership in payment-service and giving
sandbox-service only an advisory role is the load-bearing design choice here. It means the
state machine's invariants — the thing M5 built and M14 load-tested — remain provable in
one place, and it means a future real-acquirer integration replaces one internal call
rather than restructuring the payment lifecycle.

---

### M18 — Webhooks as a Product

> *The milestone that makes the platform's async output trustworthy and debuggable.*

**Objective.** Evolve notification-service from "POST the merchant's one URL" into a real
webhook subsystem: multiple endpoints, event-type subscriptions, HMAC-signed payloads, an
explicit retry schedule, a complete delivery log, manual replay, and endpoint auto-disable.

**Why this milestone exists.** Webhooks are how a payment platform tells a developer that
something happened, and V1's implementation has three disqualifying gaps for external use:
deliveries are **unsigned** (a documented V1 known issue — a merchant cannot verify the
call came from us), there is **one URL per merchant** with no way to subscribe selectively,
and there is **no visibility** into what was attempted. A developer who cannot see why a
webhook did not arrive cannot integrate. This milestone is also where the platform stops
trusting itself and starts defending against hostile endpoints (SSRF).

**Features**
- Many endpoints per merchant per mode; HTTPS-only; description; enable/disable.
- Event-type subscriptions per endpoint, wildcards supported.
- Per-endpoint `whsec_` signing secret, revealed once, rotatable with a dual-secret window.
- `PaymentFlow-Signature: t=…,v1=…` with an in-payload timestamp and a receiver-side
  tolerance window.
- Canonical merchant-facing `webhook_events` (`evt_…`) — the same object served by the
  Events API and delivered in the body.
- Explicit retry schedule (8 attempts over ~24h) on dedicated Kafka topics.
- Full delivery log: request, response, duration, error, per attempt.
- Manual replay of any past event to any endpoint.
- Auto-disable after N consecutive failures, with notification.
- SSRF protection: private/link-local/metadata ranges blocked; DNS re-resolution guarded;
  redirects not followed; response size and timeout capped.

**Implementation tasks**
1. Flyway `V2__webhooks.sql` in `notification`: the four new tables, mode-scoped.
2. Endpoint management API (`/v1/webhook_endpoints`, key-authenticated;
   `/api/v1/…` mirror for the dashboard).
3. `WebhookEventFactory`: internal Kafka envelope → canonical merchant-facing event, with
   the API version pinned per endpoint.
4. Fan-out: one `webhook_event` produces N deliveries, one per subscribed endpoint —
   replacing V1's single-URL path. Endpoint lists cached in Redis, evicted on change.
5. `WebhookSigner`: HMAC-SHA256 over `"{timestamp}.{body}"`, dual-secret aware.
6. Delivery executor: bounded connection pool, per-attempt timeout, capped response
   capture, redirects disabled, egress allow-list check before every connect.
7. Retry listener on `webhook.deliveries.retry` implementing the explicit schedule;
   dead-letter to `.dlq` — the same hand-rolled shape as D46, not `@RetryableTopic`.
8. Replay API and delivery-log query API, both cursor-paginated.
9. Auto-disable counter and the notification that accompanies it.
10. V1's `merchants.webhook_url` is migrated into a real endpoint row and then treated as
    deprecated — kept readable for backwards compatibility, no longer the delivery source.

**Testing strategy**
- *Unit*: signature generation against known vectors, verified independently in Node and
  Python so the SDK helpers in M22 are provably compatible; schedule computation;
  subscription matching including wildcards; SSRF allow-list against a table of hostile
  URLs (`localhost`, `127.0.0.1`, `169.254.169.254`, a DNS name resolving to a private IP,
  an IPv6-mapped IPv4 address).
- *Integration*: fan-out to three endpoints with different subscriptions delivers to
  exactly the right two; a failing endpoint walks the full retry schedule and dead-letters;
  replay creates a new attempt without mutating the original.
- *E2E*: a real local HTTP sink receives signed deliveries and verifies the signature using
  the documented algorithm — the same check a merchant would write. A deliberately hostile
  sink (slow, oversized response, redirect to a private IP) is rejected safely.
- *Regression*: V1's existing webhook E2E and DLQ scenario still behave correctly.

**Completion criteria**
- [ ] A merchant can register three endpoints with different subscriptions and each
      receives exactly the events it subscribed to.
- [ ] Every delivery is signed and independently verifiable by third-party code.
- [ ] A dead endpoint exhausts the schedule, dead-letters, and auto-disables.
- [ ] Replay works and is visible as a distinct attempt.
- [ ] Every SSRF vector in the test table is blocked.
- [ ] The delivery log shows the full request and response for every attempt.

**Deliverables.** Webhook subsystem in notification-service; two new Kafka topics;
management/replay/log APIs; signature specification documented; SSRF guard.

**Dependencies.** M15 (key auth for the management API), M16 (mode-scoped endpoints).

**Risks**
| Risk | Mitigation |
|---|---|
| Webhook delivery to hostile endpoints becomes an SSRF vector into the VPC | Egress allow-list, private-range blocking, no redirects, DNS re-resolution guard, all with an explicit hostile-URL test table |
| Fan-out multiplies load — N endpoints × M events | Bounded executor and connection pool; measured in M28; auto-disable removes dead endpoints from the budget |
| A slow endpoint starves delivery for everyone | Per-attempt timeout plus a bulkhead per endpoint, so one merchant cannot consume the shared pool |
| Signature scheme is subtly wrong and only discovered by an integrator | Verified independently in two other languages during this milestone, before any SDK exists |

**Engineering notes.** Signing over `"{timestamp}.{body}"` rather than the body alone is
what makes the timestamp tamper-proof and therefore makes the replay window meaningful; a
signature over the body alone can be replayed forever. This is the detail most homegrown
webhook implementations get wrong, and it is worth stating in the docs as well as the code.

---

### M19 — Public Read APIs & Query Surface

> *The milestone that closes V1's three "no query API" known issues at once.*

**Objective.** Build the complete public read surface: payments with rich filtering,
refunds as first-class objects, balance and ledger reads, an events API, and analytics —
with consistent list/pagination/error semantics across every resource.

**Why this milestone exists.** Three V1 services (transaction, audit, analytics) have *no
API at all* — D42 deferred them because no consumer existed. The dashboard, the SDKs, and
every developer using the platform are that consumer. Equally important, this is where the
*shape* of the public API is decided: pagination, filtering, expansion, error codes, and
list envelopes are set once here and inherited by everything after, so getting them right
before M21 freezes them is the whole point of the ordering.

**Features**
- `GET /v1/payments` with filters (status, mode, created range, amount range, currency,
  metadata), cursor pagination, and `expand` for related objects.
- `refunds` promoted to a first-class resource with its own id, status, and lifecycle.
- `GET /v1/balance` and `GET /v1/balance_transactions` — transaction-service's first API.
- `GET /v1/events` and `GET /v1/events/{id}` — the merchant-facing event log.
- `GET /v1/analytics/*` — volume, success rate, and totals, time-bucketed.
- `metadata` (free-form `jsonb`, indexed for filtering) on payments, refunds, endpoints.
- Uniform conventions: `CursorPage<T>`, `has_more`, `object` discriminator, stable error
  codes, consistent 404-masking.

**Implementation tasks**
1. `common-dto`: `CursorPage<T>` alongside V1's `PageResponse`; opaque, signed cursors so a
   client cannot forge one into another merchant's or mode's range.
2. payment-service: `refunds` table (extracted from the accumulating columns V1 uses,
   which are retained as derived values); list/filter/expand; `metadata` with a GIN index.
3. transaction-service: **its first web layer.** Add `spring-boot-starter-web` properly,
   Spring Security as a resource server, the `common-lib` internal-context filter, and
   read-only endpoints. Explicitly still no write API — the ledger is only ever written by
   the Kafka consumer, and that invariant is preserved.
4. audit-service: events API over `audit_log`, projecting the stored `jsonb` into the
   canonical `evt_` shape M18 defined, merchant-scoped and mode-scoped.
5. analytics-service: query API plus `payment_stats_hourly` time buckets.
6. gateway-service: route `/v1/balance*`, `/v1/events*`, `/v1/analytics*` to the three
   previously unrouted services; scope enforcement per route.
7. A shared `ListQuery` abstraction in `common-lib` so filtering and pagination are
   implemented once, not five times.

**Testing strategy**
- *Unit*: cursor encode/decode including tamper detection; filter predicate building;
  `expand` depth limits.
- *Integration*: pagination correctness across page boundaries **with concurrent inserts**
  (the case offset pagination gets wrong and cursors exist to fix); filter combinations;
  metadata queries.
- *Isolation*: every new endpoint is tested for cross-merchant and cross-mode access,
  expecting 404 — a systematic sweep, since M19 is where the number of readable endpoints
  jumps sharply and IDOR risk with it.
- *E2E*: run a full lifecycle, then verify it is visible and consistent through all five
  read APIs — the first time the platform's own state is checkable without `psql`.

**Completion criteria**
- [ ] Every V1 "no query API" known issue is closed.
- [ ] Cursor pagination is stable under concurrent writes.
- [ ] Ledger totals returned by the balance API match a direct `psql` sum exactly.
- [ ] Every endpoint enforces merchant and mode isolation, verified endpoint by endpoint.
- [ ] List, error, and pagination semantics are identical across all resources.

**Deliverables.** Read APIs on five services; `refunds` resource; `metadata`; `CursorPage`
and `ListQuery` in shared modules; gateway routes.

**Dependencies.** M16 (mode scoping), M18 (canonical event shape for the events API).

**Risks**
| Risk | Mitigation |
|---|---|
| Giving transaction-service a web layer erodes D42's clean "Kafka-in only" boundary | Read-only by construction; no write path; the FSM/ledger write path is untouched and its tests unchanged |
| Unbounded queries over large tables | Mandatory limit with a hard cap; every filter column indexed; `EXPLAIN` checked for each list endpoint rather than assumed |
| The API shape gets frozen wrong and M21 locks in a mistake | This milestone deliberately precedes versioning; conventions are reviewed as a set before M21 commits them |
| IDOR on a newly exposed endpoint | Systematic per-endpoint isolation sweep, plus D28's structural rule (identity from context, never from a path parameter) applied to every new route |

**Engineering notes.** Cursor rather than offset pagination is chosen because a payments
list is append-heavy and constantly changing; offset pagination silently skips or repeats
rows under concurrent inserts, which for a financial list is a correctness bug and not a
cosmetic one. Signing the cursor is what prevents it from becoming a parameter an attacker
can manipulate into another tenant's range.

---

### M20 — API Request Logging, Usage Metering & Per-Key Rate Limits

> *The milestone that makes the platform observable to its users, not just its operators.*

**Objective.** Capture every API request as a first-class, developer-visible object; build
usage aggregates; and move rate limiting from per-user to per-key, per-mode, with
standard response headers and quotas.

**Why this milestone exists.** V1's observability (M13) serves *operators* — Prometheus,
Grafana, Tempo. A developer platform additionally owes its *users* an answer to "what did I
send, what did you return, and why was it rejected?" Stripe's request log is arguably its
single most-used debugging feature. Rate limiting also has to change: V1 keys buckets by
JWT subject or IP (D24), neither of which is meaningful when the caller is a server holding
a key.

**Features**
- `api.request.events` emitted by the gateway for every request — asynchronously, never on
  the response path.
- Request log: method, path, status, latency, key id, mode, IP, user agent, request id,
  error code, and **redacted** request/response bodies.
- Usage aggregates: per key, per endpoint, per day; error rates; latency percentiles.
- Per-key token buckets with configurable per-merchant limits; separate test/live budgets.
- Daily quotas with `RateLimit-Limit` / `-Remaining` / `-Reset` and `Retry-After` headers.
- Developer-facing usage and request-log APIs.
- Fixes V1's missing Resilience4j meters on `/actuator/prometheus`.

**Implementation tasks**
1. gateway-service: a global filter capturing timing and outcome, publishing to Kafka
   fire-and-forget with a bounded buffer that **drops rather than blocks** if the producer
   backs up — a request must never fail because logging is slow.
2. Redaction in `common-lib`: field-name and pattern-based scrubbing (`sk_`, `whsec_`,
   `password`, `authorization`, PAN-shaped digits), applied before anything is serialized.
   Bodies are truncated to a fixed cap.
3. Per-key rate limiter replacing D24's key resolver for API-key traffic; JWT/IP keying is
   retained for dashboard and unauthenticated routes.
4. Quota counters in Redis with daily expiry; standard headers on every response.
5. analytics-service: `api_request_log` (daily-partitioned), `api_usage_daily`, a rollup
   job, and a retention pruner — all three built together, since a log table without a
   pruner is a future outage.
6. `/v1/usage` and `/v1/request_logs` APIs, cursor-paginated, mode-scoped.
7. Investigate and fix the Resilience4j-meters gap V1 flagged and re-confirmed in M14.

**Testing strategy**
- *Unit*: redaction against a corpus of realistic payloads containing secrets, including
  nested and array cases; header computation at limit boundaries; quota arithmetic across a
  day rollover.
- *Integration*: request events land and aggregate correctly; rollup and pruning are
  correct across a partition boundary.
- *Load*: confirm the logging path adds negligible latency and that a deliberately stalled
  Kafka producer causes dropped log events, **not** failed or slowed requests. This is the
  property most worth proving by experiment rather than by reading the code.
- *E2E*: exceed a rate limit and confirm 429 plus correct headers; confirm the request log
  shows the 429 with a redacted body.

**Completion criteria**
- [ ] Every request through the gateway appears in the developer-visible log within seconds.
- [ ] No secret ever appears in a logged body — verified against a deliberately
      secret-laden corpus.
- [ ] Rate limits are per key and per mode; headers are correct at the boundary.
- [ ] A stalled log pipeline degrades to dropped events with zero request impact, proven
      under load.
- [ ] Retention pruning works and `/actuator/prometheus` exposes Resilience4j meters again.

**Deliverables.** Gateway request-event pipeline; redaction in `common-lib`; per-key limits
and quotas; analytics request-log/usage models with rollup and retention; two APIs.

**Dependencies.** M15 (keys are the rate-limit and attribution dimension).

**Risks**
| Risk | Mitigation |
|---|---|
| High-volume request logging becomes the platform's bottleneck | Async, bounded, drop-on-backpressure; daily partitions; aggressive retention; measured in M28 |
| A secret leaks into a stored request body | Redaction runs before serialization, not after; tested against a purpose-built secret corpus; bodies capped |
| Request-log storage grows without bound | Partitioning plus a pruner plus pre-pruning rollup, all shipped in this milestone rather than deferred |
| Changing the rate-limit key breaks V1's tested behaviour | JWT/IP keying is preserved for non-key traffic; V1's Gatling rate-limit scenario stays green as the regression gate |

**Engineering notes.** "Drop rather than block" is the load-bearing decision. Observability
infrastructure that can fail a customer request is worse than no observability, and V1
already learned an adjacent version of this lesson in D89, where an OTLP exporter with no
receiver spent months quietly retrying and logging stack traces on every service.

---

### M21 — OpenAPI 3.1, Versioning & the Error Contract

> *The milestone that turns an API into a contract.*

**Objective.** Generate a real OpenAPI 3.1 description of the public API from code, merge
the per-service fragments into one published spec, implement date-based versioning with a
deprecation policy, formalise the error-code catalogue, and make CI fail on an undeclared
breaking change.

**Why this milestone exists.** V1's tech-stack table lists "OpenAPI / Swagger UI
(springdoc)" but **springdoc is not a dependency of any module** — the documentation story
is entirely aspirational today. Everything downstream needs a machine-readable contract:
SDKs are generated or hand-written against it, the docs site renders it, the interactive
console drives it, and CI diffs it. It is placed after M19 deliberately, so the API shape
being frozen is the final one rather than a moving target.

**Features**
- springdoc across every service exposing a public API; annotated schemas, examples, and
  error responses.
- A merged public spec (`openapi.yaml`), built as a real artefact and committed as a
  baseline.
- `PaymentFlow-Version` header; per-merchant pinning at first call; per-request override.
- Version transformation layer at the edge for superseded revisions.
- `Deprecation` / `Sunset` headers and a documented deprecation timeline.
- A complete, stable error-code catalogue: type, code, message, `param`, `doc_url`,
  `request_id`.
- CI spec-diff gate that fails on an undeclared breaking change.

**Implementation tasks**
1. Add springdoc to every public-API service; annotate DTOs, enums, and error responses.
2. A Gradle task that fetches each service's fragment and merges them, deduplicating shared
   components (`ApiError`, `CursorPage`, pagination parameters).
3. Extend `ApiError` (D12) with `type`, `doc_url`, and `request_id` — additive, so V1
   clients are unaffected; document every code in one table that is the source of truth for
   both the docs site and the SDKs.
4. Version resolution filter at the gateway; per-merchant pinned version stored in
   `merchant_settings`; request/response transformers registered per revision.
5. CI: generate the spec, diff against the committed baseline, classify additive vs
   breaking, fail on undeclared breaking. Publish the spec as a build artefact.
6. Spec-vs-reality validation: assert live responses actually validate against the schema,
   so the spec cannot silently drift from the implementation.

**Testing strategy**
- *Unit*: version parsing and resolution precedence; transformer correctness in both
  directions.
- *Contract*: every documented endpoint's real response validated against its schema — this
  is the test that keeps the spec honest.
- *CI*: deliberately introduce a breaking change on a scratch branch and confirm the gate
  actually fails. A gate that has never been observed failing is not known to work.
- *E2E*: two pinned versions served simultaneously produce correctly different shapes.

**Completion criteria**
- [ ] A single merged OpenAPI 3.1 spec covers every public endpoint.
- [ ] Live responses validate against the spec — verified, not assumed.
- [ ] Version pinning works; a superseded revision still returns its original shape.
- [ ] The CI breaking-change gate has been observed failing on a real breaking change.
- [ ] Every error response carries a catalogued code and a `request_id`.

**Deliverables.** springdoc integration; merge task; committed `openapi.yaml` baseline;
versioning infrastructure; error catalogue; CI gate; contract tests.

**Dependencies.** M15–M20 (the API surface must be complete before it is frozen).

**Risks**
| Risk | Mitigation |
|---|---|
| Generated spec drifts from actual behaviour | Contract tests validate live responses against the schema on every build |
| Version transformation becomes unmaintainable as revisions accumulate | Only one superseded revision is supported at a time during V2; the policy is documented and the count is a deliberate constraint |
| The breaking-change classifier has false negatives | Curated rule set plus a real observed failure; the classifier's own test suite is part of the deliverable |

**Engineering notes.** Date-based versioning with a per-merchant pin is chosen over URL
versioning because it lets the platform ship improvements continuously without forking
every endpoint path, and because it makes "which version is this integrator on?" a data
question rather than a log-parsing exercise. The cost — a transformation layer — is real,
which is why the number of concurrently supported revisions is capped by policy rather than
left to grow.

---

### M22 — Node & Python SDKs

> *The milestone that makes the correct integration the easy one.*

**Objective.** Build two production-quality SDKs — TypeScript/Node and Python — that
encapsulate authentication, automatic idempotency, safe retries, pagination, typed errors,
and webhook-signature verification, with real packaging and release pipelines.

**Why this milestone exists.** Every hard-won correctness property in this platform —
idempotency keys on every mutation (D34), at-least-once webhook delivery requiring
signature verification and dedup (D2/M18), rate-limit backoff — is a property the
*integrator* has to honour, and most will not honour it by hand. An SDK is where a platform
encodes its own operational lessons so its users get them for free. Two languages rather
than four is deliberate depth-before-breadth (V1's own design principle): the design is
validated against a second language before being ported to a third and fourth in M26.

**Features (both SDKs, identical semantics)**
- Configuration: API key, base URL, timeout, max retries, API version, custom headers.
- Automatic `Idempotency-Key` generation on every mutating call, overridable.
- Retries with exponential backoff and jitter on 429/5xx/network errors — **never** on
  4xx client errors, and always reusing the same idempotency key so a retry is a genuine
  replay, not a second charge.
- `RateLimit-Reset`-aware backoff rather than blind sleeping.
- Auto-paginating iterators (`for await (const p of client.payments.list())`).
- Typed error hierarchy mirroring the M21 error catalogue, carrying `request_id`.
- `webhooks.constructEvent(body, signature, secret)` — verification plus replay-window
  enforcement, the single most important helper in the SDK.
- Full type coverage (TypeScript types; Python type hints + `py.typed`).
- Request/response hooks for logging, and a configurable HTTP client for testability.

**Implementation tasks**
1. New top-level `sdks/` directory: `sdks/node`, `sdks/python`. Kept in the monorepo so the
   spec, SDKs, and API version in sync by construction.
2. Generate base types from M21's OpenAPI spec; hand-write the ergonomic layer. Fully
   generated SDKs are rejected — the ergonomics above are exactly what generators do badly.
3. Shared design doc first, implemented twice, so the two SDKs are genuinely equivalent
   rather than accidentally divergent.
4. Node: TypeScript, ESM + CJS dual build, zero runtime dependencies beyond `fetch`.
5. Python: 3.9+, sync client first with an async variant, `httpx`, `py.typed`.
6. Example programs per SDK: quickstart, full lifecycle, webhook receiver, error handling,
   pagination, retries.
7. CI: lint, type-check, unit tests, and integration tests against a real local stack.
8. Release pipeline to npm and PyPI, **dry-run only** in this milestone — actual publishing
   is an irreversible public action requiring explicit approval (standing rule 5).
9. Versioning policy: SDK semver, decoupled from the dated API version, with a documented
   compatibility matrix.

**Testing strategy**
- *Unit*: retry/backoff behaviour under mocked failures, including the assertion that the
  idempotency key is preserved across retries; pagination across boundaries; error mapping;
  signature verification against the same known vectors M18 established.
- *Integration*: both SDKs run the full lifecycle against a real local stack.
- *Cross-language*: an identical scripted scenario produces identical platform state from
  both SDKs — the concrete test that they are actually equivalent.
- *Packaging*: install the built artefact into a clean project and run the quickstart, so
  packaging errors are caught before publication rather than by a user.

**Completion criteria**
- [ ] Both SDKs complete a full lifecycle against a real local stack.
- [ ] A forced 429 and a forced 5xx are retried correctly with a preserved idempotency key.
- [ ] Signature verification passes valid signatures and rejects tampered bodies, wrong
      secrets, and stale timestamps.
- [ ] Both SDKs are installable from a built artefact and their quickstarts run clean.
- [ ] Publishing pipelines succeed in dry-run.

**Deliverables.** `sdks/node`, `sdks/python`, a shared design document, examples, CI
workflows, dry-run release pipelines, a compatibility matrix.

**Dependencies.** M21 (a stable spec), M18 (the signature scheme).

**Risks**
| Risk | Mitigation |
|---|---|
| SDK retries turn one payment into two | Idempotency key generated once per logical call and reused across every retry; explicitly tested |
| The two SDKs diverge in behaviour | One shared design doc; a cross-language equivalence test |
| Publishing to a public registry is irreversible and name-squatting-adjacent | Dry-run only; real publication requires explicit approval |
| Generated types drift from the spec | Regenerated in CI and diffed; a stale checked-in type fails the build |

**Engineering notes.** The single highest-value line of code in either SDK is the one that
reuses the idempotency key across retries. Without it, the SDK's own retry logic converts a
transient network blip into a duplicate charge — the exact failure mode V1's entire
idempotency subsystem (D33/D34) exists to prevent. That the platform is safe does not help
if the client library defeats it.

---

### M23 — Developer Portal, Part 1: Auth, Merchants, Keys, Payments

> *The milestone that gives the platform a face.*

**Objective.** Build the Next.js application shell and the first three functional surfaces:
authentication and account management, merchant onboarding with key management, and the
payments dashboard.

**Why this milestone exists.** Everything before this is invisible. It is split into two
milestones because "a dashboard" is not one deliverable — the shell, design system, auth,
and data layer are foundational work that the log/webhook/analytics surfaces in M24 build
on, and shipping them as one milestone would produce a gate too large to review meaningfully.

**Features**
- Next.js App Router + TypeScript + Tailwind; a small, deliberate design system.
- Signup, email verification, login, refresh, logout, password reset.
- Session handling: access token in memory, refresh in an `httpOnly` `SameSite=Strict`
  cookie. **No token is ever placed in `localStorage`**, and no secret API key is ever held
  by the browser.
- Merchant onboarding wizard; profile and settings.
- API key management: create (secret revealed exactly once, with an explicit
  copy-and-acknowledge step), list with prefixes and `last_used_at`, rotate with grace,
  revoke with confirmation.
- Global **test/live mode toggle**, persisted, visually unmistakable (colour + banner), and
  applied to every query.
- Payments list with filters, saved views, and a detail page showing the FSM timeline,
  amounts, metadata, related events, and refunds.
- Payment actions: capture, refund (full and partial), void — with confirmation dialogs.
- Empty states, loading skeletons, error boundaries, keyboard navigation, and an
  accessibility pass (WCAG AA contrast, focus management, screen-reader labels).

**Implementation tasks**
1. New top-level `developer-portal/` (Next.js). Not a Gradle module; its own toolchain,
   its own Dockerfile, and its own CI job.
2. Typed API client generated from M21's spec, wrapping the `/api/v1/*` session-authenticated
   surface (not `/v1/*` — the browser must never hold a secret key).
3. Auth flows, protected route groups, server-side session validation.
4. Design system: colour tokens with a dark/light pair, typography scale, and the shared
   primitives (button, input, table, badge, modal, toast, empty state).
5. Onboarding wizard and key management screens, with the once-only secret reveal handled
   carefully — this is the highest-consequence UI in the product.
6. Payments list and detail; mutation actions with optimistic updates and rollback.
7. Mode toggle in a global provider; every query key includes mode so switching cannot
   serve stale cross-mode data from cache.
8. Fix the deployed gateway's `SPRING_PROFILES_ACTIVE=local` CORS misconfiguration (the V1
   known issue) — this is the milestone where a browser client first exists to be broken
   by it.
9. Local development: portal added to `docker-compose.yml` and proxied through the gateway.

**Testing strategy**
- *Unit*: components and hooks (Vitest + Testing Library).
- *Integration*: auth flows against a real local backend.
- *E2E (Playwright)*: signup → verify → onboard → create key → create a payment via the API
  → see it in the dashboard → refund it. The full journey, automated.
- *Accessibility*: automated axe checks plus manual keyboard-only navigation.
- *Security*: confirm no token in `localStorage`, no secret key in any bundle, CSP enforced,
  and that a mode switch cannot surface cached cross-mode data.

**Completion criteria**
- [ ] A new user can sign up and reach a working dashboard with zero manual intervention.
- [ ] A secret key is displayed exactly once and never retrievable afterwards.
- [ ] The mode toggle switches the entire data plane with no cross-mode leakage.
- [ ] The full Playwright journey passes.
- [ ] Accessibility checks pass; the app is usable keyboard-only.

**Deliverables.** `developer-portal/` app; design system; auth/onboarding/keys/payments
surfaces; Playwright suite; Dockerfile; CI job; the CORS fix.

**Dependencies.** M19 (read APIs), M20 (usage data for the overview), M15 (key management).

**Risks**
| Risk | Mitigation |
|---|---|
| A secret key leaks into a bundle, log, or analytics payload | The browser only ever uses session auth; secrets are shown once from a direct response and never persisted client-side; verified by bundle inspection |
| Mode confusion causes a destructive action against live data | Unmistakable visual treatment; mode in every query key; confirmation dialogs name the mode explicitly |
| Frontend scope expands without bound | Hard split at M23/M24 with explicitly listed surfaces per milestone |
| The portal becomes a second, undocumented API consumer that drifts | Its client is generated from the same spec; `/api/v1/*` remains explicitly non-public |

**Engineering notes.** The once-only secret reveal deserves disproportionate care: it is the
one screen where a UX failure (a mis-click, a dismissed modal, a copy that silently failed)
translates directly into a user locked out of their own integration. An explicit
acknowledge-before-dismiss step is worth the friction.

---

### M24 — Developer Portal, Part 2: Webhooks, Logs, Analytics, Admin

> *The milestone that makes the platform debuggable by its users.*

**Objective.** Complete the portal with the surfaces that turn it from a viewer into a
working tool: webhook management and delivery inspection, the API request log, analytics
dashboards, simulation controls, and the admin console.

**Why this milestone exists.** M23 makes the platform visible; M24 makes it *diagnosable*.
The webhook delivery inspector and the request log are the two screens a developer actually
lives in when an integration misbehaves, and they are the strongest demonstration of the
observability work in M18 and M20.

**Features**
- Webhook endpoints: create, edit, subscribe to event types, reveal the signing secret once,
  rotate, disable, delete; a "send test event" button.
- Delivery inspector: every attempt with full request/response, timing, error, retry
  schedule position, and a **Replay** button.
- API request log: searchable, filterable, with a detail view showing the redacted request
  and response and a link to the related object.
- Events browser over the events API, with the exact payload delivered to webhooks.
- Analytics: volume over time, success/decline breakdown, latency percentiles, top error
  codes, usage against quota. Charts read in both light and dark themes and are accessible
  (never colour alone as the sole encoding).
- Simulation controls: pick a scenario, apply an override, see the decision log.
- Admin: all merchants, key/webhook health, DLQ inspector with replay, platform metrics,
  feature flags.
- Global search across payments, events, and logs by id.

**Implementation tasks**
1. Webhook management screens with the same once-only secret pattern as API keys.
2. Delivery inspector with a request/response viewer, syntax highlighting, and replay.
3. Request-log explorer with server-driven filtering and cursor pagination wired to
   infinite scroll.
4. Analytics with a charting library and a shared, accessible palette applied consistently
   across every chart.
5. Simulation control panel backed by M17's API.
6. Admin route group, server-side gated so the admin bundle is never served to a non-admin.
7. DLQ inspector: read `payment.events.dlq` and `webhook.deliveries.dlq`, show the failure,
   allow replay. This is the first UI the platform has ever had for its dead-letter queues —
   V1 could only inspect them by console consumer.
8. Global search by object id across services.

**Testing strategy**
- *Unit/integration*: components; filter state; chart data transforms.
- *E2E (Playwright)*: register an endpoint → trigger a payment → see the delivery → break
  the endpoint → watch retries → replay successfully. The whole webhook debugging loop.
- *Admin*: role gating tested by attempting admin routes as a non-admin, at both the route
  and API level.
- *Performance*: the request-log explorer over a large seeded dataset — the one screen with
  a realistic chance of being slow.

**Completion criteria**
- [ ] The complete webhook debugging loop works end to end in the UI.
- [ ] The request log surfaces any request within seconds and never displays a secret.
- [ ] Analytics match values computed directly from the database.
- [ ] Admin surfaces are inaccessible to non-admins at both route and API level.
- [ ] The DLQ inspector shows real dead-lettered messages and replays them.

**Deliverables.** Webhook, logs, events, analytics, simulation, and admin surfaces; DLQ
inspector; global search; extended Playwright suite.

**Dependencies.** M18, M19, M20, M23.

**Risks**
| Risk | Mitigation |
|---|---|
| A secret or PII is rendered in the log viewer | Redaction happens server-side at write time (M20); the UI never receives unredacted data |
| The log explorer is slow over large datasets | Cursor pagination, server-side filtering, indexed columns, tested against a large seeded dataset |
| Admin capabilities leak to ordinary merchants | Server-side gating in addition to client routing; tested by attempting access |
| DLQ replay causes duplicate side effects | Replay reuses the original event id, so every consumer's existing idempotency (D2) absorbs it — the property is verified, not assumed |

**Engineering notes.** DLQ replay is safe *only* because every consumer in this platform is
idempotent on `eventId` — a design decision made back in M6 for a completely different
reason. This is worth noting explicitly as the kind of dividend a correct early invariant
pays years later; it is also worth verifying rather than trusting, since the invariant now
has a new caller it was not designed for.

---

### M25 — Documentation Site & Developer Experience

> *The milestone that determines whether anyone succeeds at integrating.*

**Objective.** Build the public documentation experience: quickstarts, guides, an API
reference rendered from the OpenAPI spec, SDK documentation, an interactive console, and a
changelog — all inside the portal app, all versioned with the API.

**Why this milestone exists.** Documentation is the actual product surface of a developer
platform; an undocumented API is unusable regardless of quality. Placing it after the SDKs
means every code sample can be real, copy-pasteable, and — critically — **tested**, rather
than illustrative prose that rots.

**Features**
- Quickstart: first payment in under five minutes, per language.
- Guides: authentication, idempotency, errors and retries, webhooks, testing, rate limits,
  pagination, versioning, going live.
- API reference generated from the spec: every endpoint with parameters, schemas, errors,
  and multi-language samples (curl / Node / Python, plus Java / Go after M26).
- Interactive console: run a real request against test mode using your own key, from the
  docs page.
- SDK guides per language, with install, config, and common patterns.
- Test-card and error-code reference tables, generated from the same source of truth the
  platform uses — so they cannot drift.
- Changelog and deprecation notices tied to API versions.
- Search across all documentation.

**Implementation tasks**
1. Docs routes in the portal as static generation, with MDX content.
2. API reference renderer over the merged spec, with a stable per-endpoint anchor scheme.
3. Multi-language sample generation, driven by the spec plus per-endpoint sample metadata,
   so samples cannot silently drift from parameters.
4. Interactive console reusing the dashboard's key picker; **hard-restricted to test mode**.
5. Test cards and error codes rendered from the same seed data and catalogue the services
   use.
6. Client-side search index built at build time.
7. **Sample verification in CI**: every code sample in the docs is extracted and executed
   against a live local stack. A sample that stops working fails the build.
8. Copy-to-clipboard, language persistence, dark mode, and deep links everywhere.

**Testing strategy**
- *Automated sample verification* (above) — the core testing idea of this milestone.
- *Link checking* for internal and external links.
- *E2E*: the quickstart is followed verbatim by an automated script that starts from
  nothing and ends with a successful payment.
- *Accessibility and performance* budgets on docs pages.

**Completion criteria**
- [ ] Every code sample in the documentation executes successfully in CI.
- [ ] The quickstart, followed verbatim from scratch, produces a successful payment.
- [ ] The API reference covers every public endpoint with no gaps.
- [ ] The interactive console works and cannot be pointed at live mode.
- [ ] Search returns useful results; no broken links.

**Deliverables.** Docs surface in the portal; API reference renderer; interactive console;
sample-verification CI job; generated reference tables; changelog.

**Dependencies.** M21 (spec), M22 (SDKs to document).

**Risks**
| Risk | Mitigation |
|---|---|
| Documentation drifts from the implementation | Reference generated from the spec; samples executed in CI; tables generated from the same seed data |
| The interactive console is used against live mode | Hard-restricted to test keys, enforced server-side, not merely hidden in the UI |
| Docs become a large unmaintained content debt | Generated wherever possible; hand-written prose limited to genuine concepts |

**Engineering notes.** Executing every documentation sample in CI is the single highest-value
practice in this milestone. It converts documentation from prose that decays into an
artefact with a test suite, and it is the only mechanism that reliably prevents the
"quickstart no longer works" failure every API platform eventually suffers.

---

### M26 — Java & Go SDKs

**Objective.** Port the validated SDK design to Java and Go, achieving behavioural parity
with Node and Python.

**Why this milestone exists.** Java and Go are the dominant server languages in the
payments and fintech space this platform models; a platform without them is
unrepresentative. Placing them after M22 and M25 means porting a design that has been
validated against two languages, real usage, and a documentation pass — rather than
inventing three designs in parallel and discovering the inconsistencies later.

**Features.** Identical semantics to M22: config, auto-idempotency, retries with preserved
keys, rate-limit-aware backoff, auto-pagination, typed errors, webhook verification, full
type coverage. Java targets 17+ (broader reach than the platform's own Java 25), builds
with Gradle, and publishes to Maven Central (dry-run). Go targets modules with
`context.Context` throughout and idiomatic error wrapping.

**Implementation tasks.** `sdks/java` and `sdks/go`; types generated from the spec;
ergonomic layer hand-written per language idiom; examples mirroring M22's set; CI jobs;
dry-run publishing; docs and API-reference samples extended to four languages; the
cross-language equivalence test extended from two languages to four.

**Testing strategy.** Same shape as M22: unit tests for retry/pagination/errors/signature
against the shared vectors; integration against a real local stack; the four-language
equivalence scenario; packaging verification by consuming the built artefact from a clean
project.

**Completion criteria**
- [ ] Both SDKs complete a full lifecycle against a real local stack.
- [ ] All four SDKs produce identical platform state from the identical scenario.
- [ ] Signature verification matches the shared vectors exactly.
- [ ] Publishing pipelines succeed in dry-run; docs show four-language samples everywhere.

**Deliverables.** `sdks/java`, `sdks/go`, examples, CI, dry-run publishing, expanded docs
samples, four-way equivalence test.

**Dependencies.** M22 (the validated design), M25 (the docs surface to extend).

**Risks**
| Risk | Mitigation |
|---|---|
| Four SDKs multiply maintenance cost | Shared design doc, shared test vectors, generated types, one equivalence suite covering all four |
| Language idioms pull the designs apart | Behaviour is specified in the shared design doc; idiom is allowed to vary, semantics are not |
| Java version choice conflicts with the platform's Java 25 | The SDK deliberately targets 17+ — an SDK must run in its users' environments, not its author's |

**Engineering notes.** The equivalence test growing from two languages to four is the
mechanism that keeps this maintainable. Without an executable definition of "these SDKs
behave the same," parity is an assertion that quietly stops being true.

---

### M27 — Security Hardening & Multi-Tenancy Review

> *The milestone that assumes V2 got something wrong and goes looking for it.*

**Objective.** Conduct a systematic security review of everything V2 added, write the
threat model, and fix what the review finds — with particular focus on the two genuinely
new attack surfaces: long-lived secret keys and the tenant/mode boundary.

**Why this milestone exists.** V2 introduced credentials that live for months on
third-party servers, a data-plane boundary enforced by application code, and outbound HTTP
to arbitrary developer-controlled URLs. None of these existed in V1, so none of V1's
security reasoning covers them. A dedicated milestone exists because security review done
incrementally inside feature milestones is always the work that gets compressed when the
feature runs late.

**Features / workstreams**
- A written threat model (STRIDE-style) covering every V2 component and trust boundary.
- Systematic **IDOR sweep**: every endpoint added in M15–M24 tested for cross-merchant and
  cross-mode access, driven by a generated matrix rather than by hand-picked cases.
- Key lifecycle review: entropy, storage, comparison timing, revocation propagation,
  rotation grace correctness, and enumeration resistance.
- Secret handling review: no secret in logs, traces, metrics, error messages, request logs,
  or client bundles — verified by grepping real captured output, not by inspection.
- Webhook SSRF review with an expanded hostile-target matrix and confirmed egress controls.
- Rate-limit bypass review: header spoofing, key rotation abuse, distributed sources,
  mode-switching to double an effective budget.
- Input validation sweep: size limits on every body, depth limits on `metadata`, injection
  testing across every filter parameter.
- Portal security: CSP, CSRF, clickjacking, session fixation, secure cookie flags.
- Dependency and container scanning wired into CI as a gate, not a report.
- Secrets rotation runbook for the internal HMAC secret, the JWT keypair, and DB credentials.

**Implementation tasks**
1. Write the threat model; enumerate trust boundaries; rank findings by likelihood × impact.
2. Build the automated tenant-isolation matrix test: for every resource, attempt access as
   (other merchant, other mode, revoked key, wrong-scope key, no key) and assert the exact
   expected status. This becomes a permanent regression suite, not a one-off audit.
3. Constant-time comparison audit anywhere a secret is compared.
4. Log/trace/metric capture under realistic traffic, then grep for key patterns.
5. Expand the SSRF matrix; confirm egress restrictions actually hold at the network level,
   not just in application code.
6. Rate-limit bypass attempts, each with a test.
7. Fuzz the public API surface for input handling.
8. Add `dependency-check`/`trivy`-style scanning to CI with a failure threshold.
9. Portal header and cookie hardening; verify with real response inspection.
10. Fix every finding above the agreed severity threshold; document accepted risks
    explicitly in §14 rather than silently.

**Testing strategy.** The tenant-isolation matrix and the SSRF matrix are the two central
deliverables and both are permanent automated suites. Everything else is verified by real
captured evidence: actual response headers, actual log output, actual scan results.

**Completion criteria**
- [ ] Threat model written, reviewed, and committed.
- [ ] The isolation matrix passes for every endpoint and runs in CI.
- [ ] No secret appears in any captured log, trace, metric, or bundle.
- [ ] Every SSRF vector is blocked at both application and network level.
- [ ] Dependency and container scans pass the configured threshold in CI.
- [ ] Every finding is fixed or explicitly accepted with a recorded rationale.

**Deliverables.** Threat model; isolation and SSRF matrix suites; CI scanning gates;
hardening fixes; rotation runbook; updated known issues.

**Dependencies.** M15–M26 (everything under review must exist).

**Risks**
| Risk | Mitigation |
|---|---|
| The review finds a structural flaw late, requiring rework | The isolation matrix is built incrementally from M16 onward rather than only here; this milestone confirms and extends rather than discovers from zero |
| Security work expands without a stopping condition | Findings ranked; a severity threshold agreed up front; accepted risks recorded rather than endlessly chased |
| Scanners produce noise that trains everyone to ignore them | Thresholds tuned so the gate is meaningful; suppressions require a written reason |

**Engineering notes.** The tenant-isolation matrix is the most valuable artefact here. A
one-time audit proves the system was safe on one day; a generated matrix that runs on every
build proves it stays safe as endpoints are added — which is the actual risk, since the
platform will keep growing after V2.

---

### M28 — V2 Performance Engineering

**Objective.** Extend V1's Gatling suite to cover every V2 path, establish V2 baselines,
and find and fix real bottlenecks introduced by API-key auth, mode filtering, request
logging, webhook fan-out, and the sandbox hop.

**Why this milestone exists.** V1's M14 measured a system where every request carried a JWT
and touched three services. V2's request path is materially different: a key lookup, a
signature verification, a mode filter on every query, an asynchronous log emission, and
possibly a sandbox call. Those numbers are unknown until measured, and several V2 design
choices (drop-on-backpressure logging, negative caching, per-endpoint bulkheads) were made
*on the assumption* they would behave a certain way under load. This milestone tests those
assumptions.

**Features / scenarios**
- API-key authentication throughput: cache hit vs miss, and the cost of a cold cache after
  a mass revocation.
- Mode-filtered query performance against a large multi-mode dataset.
- Request-logging pipeline under sustained high volume, including deliberate Kafka
  backpressure to prove the drop path.
- Webhook fan-out: many endpoints per merchant, slow endpoints, dead endpoints, and the
  retry backlog they generate.
- Sandbox decision latency and its contribution to end-to-end payment latency.
- Read-API performance: large lists, deep pagination, metadata filters, request-log search.
- Rate-limit and quota enforcement accuracy under concurrency.
- Portal API load patterns (dashboard page loads issue many parallel reads).

**Implementation tasks.** New Gatling simulations per area, extending the existing
`load-tests` module and reusing the seeded-pool approach (D93) and the concurrency-safe
feeder pattern (D95) — both hard-won V1 lessons that apply directly. Add a large-dataset
seeding harness. Extend Grafana with V2 panels: key-cache hit rate, log-drop rate, webhook
queue depth, sandbox latency. Profile and fix what is found; record every number in §18.

**Testing strategy.** Measurement is the test. Every scenario runs against the full local
stack with observability attached, exactly as M14 did (D92 — local only, never against
AWS). Each run is repeated to distinguish genuine findings from noise, which is precisely
how M14 correctly identified its bulkhead event as load-dependent rather than systemic.

**Completion criteria**
- [ ] Every V2 path has a baseline recorded in §18.
- [ ] API-key auth adds < 5 ms p99 on a cache hit, measured under load.
- [ ] The logging pipeline drops rather than blocks under deliberate backpressure — proven
      by experiment.
- [ ] Webhook fan-out sustains a defined target rate without starving other traffic.
- [ ] No regression against V1's M14 numbers on the original payment hot path.
- [ ] Any bottleneck found is either fixed or documented with a rationale.

**Deliverables.** New Gatling simulations; large-dataset seeder; V2 Grafana panels;
benchmark results in §18; fixes for whatever is found.

**Dependencies.** M27 (measure the hardened system, not a pre-hardening one).

**Risks**
| Risk | Mitigation |
|---|---|
| A V2 feature meaningfully degrades V1's measured hot path | V1's exact M14 simulations are re-run as the regression baseline |
| Local-only testing misses cloud behaviour | Explicitly acknowledged (D92 precedent); M29 does a limited smoke test against the deployed environment |
| Load-test harness bugs masquerade as platform bugs | V1 hit this twice (D95, D96) — every finding is reproduced independently outside Gatling before being called a platform bug |

**Engineering notes.** V1's M14 produced two findings that were bugs in the *test harness*,
not the platform, and one legitimate resilience event. That ratio is worth remembering as
the default prior: a surprising load-test result is more often a harness artefact than a
platform defect, and reproducing it outside the harness is the cheapest way to tell.

---

### M29 — AWS Deployment of Version 2

**Objective.** Extend the Terraform estate to cover every V2 component, deploy the full V2
platform to AWS, and verify it end to end against the real deployed environment.

**Why this milestone exists.** V2 has been built local-first by explicit decision, keeping
AWS cost flat during development. This is the milestone where that debt is paid: one
deployment, one apply, one verification pass. It also closes a V1 gap — the async event
pipeline was never *directly* confirmed on AWS because no `psql` or ECS Exec access existed.

**Features**
- Terraform for `sandbox-service` (ECR repo, ECS service, security group, secrets).
- Portal hosting: containerised Next.js on ECS behind the ALB, with CloudFront in front of
  the static assets.
- New Secrets Manager entries: the internal HMAC signing secret, webhook signing pepper.
- New ALB routing rules for the portal and the `/v1/*` API paths.
- ECS autoscaling policies — V1 ran every service as a single unscaled task, which M14
  explicitly noted made real cloud load testing meaningless.
- **Capacity-provider fix (carried over from V1, must land before the apply):** remove the
  explicit `launch_type = "FARGATE"` from `modules/ecs-service` so services actually inherit
  the cluster's `FARGATE_SPOT` default — V1's deployment silently ran entirely on-demand
  because of it (§14). This is a pre-apply task, not a post-deployment optimization: applying
  first and correcting after would mean paying the on-demand premium twice over.
- WAF in front of the ALB: rate-based rules, common rule set.
- ECS Exec enabled on the Kafka-touching services, closing the V1 verification gap.
- Optional (costed, decided at kickoff): the M13 observability stack deployed to AWS.
- `cd.yml` finally wired and actually run — it has never executed.

**Implementation tasks.** Extend `modules/ecs-service` instantiation for sandbox-service and
the portal; add secrets; add ALB rules and target groups; add autoscaling; add WAF; enable
ECS Exec; update task definitions for every new env var; run `terraform plan`, review it in
full, and apply **only with explicit approval**; push all images; run the full verification
suite against the deployed environment; write a teardown and cost runbook.

**Testing strategy.** `fmt`/`validate`/`plan` reviewed line by line before any apply — V1's
infrastructure-recovery experience showed a plan reviewed carefully catches what a plan
skimmed does not. Post-apply: every service healthy via `describe-services`; a full
lifecycle through the real ALB; **direct confirmation of the async pipeline via ECS Exec**;
the portal loading and functioning against the deployed API; webhooks delivered to a real
external endpoint; a limited smoke load test.

**Completion criteria**
- [ ] Every V2 service runs healthy in ECS.
- [ ] A full lifecycle succeeds through the real ALB using only an API key.
- [ ] The async event pipeline is *directly* confirmed on AWS, closing the V1 gap.
- [ ] The portal works against the deployed API, including CORS.
- [ ] Webhooks reach a real external endpoint with valid signatures.
- [ ] Autoscaling, WAF, and ECS Exec verified working.
- [ ] **Every running task confirms `capacityProviderName: FARGATE_SPOT`** via
      `aws ecs describe-tasks` — verified on the tasks themselves, not inferred from the
      cluster's default strategy, which is exactly the inference that hid V1's on-demand
      billing for four days.
- [ ] Teardown and cost runbook written, with a `terraform destroy` plan reviewed.
      The runbook must record that `modules/ecr` needs `force_delete = true` (or a manual
      image purge) — V1's teardown failed on all 8 repositories without it.

**Deliverables.** Terraform for all V2 components; deployed environment; a functioning
`cd.yml`; verification evidence; cost and teardown runbook.

**Dependencies.** M28 (deploy something measured, not something hoped for).

**Risks**
| Risk | Mitigation |
|---|---|
| AWS cost increases materially | Cost estimated *before* apply and approved explicitly; autoscaling floors set low; teardown runbook written first |
| `terraform apply` partially fails as it did in V1 | Full plan review; V1's exact failure mode (an invalid engine version silently blocking dependents) is a known pattern to check for |
| Config that works locally fails in AWS | V1 hit this twice (Redis TLS D82, PKCS#8 D83); every environment-specific value is enumerated and checked deliberately |
| The portal's CORS/CSP breaks against the deployed origin | Fixed in M23 and re-verified here against the real origin |

**Engineering notes.** V1's infrastructure recovery is the direct precedent for how this
milestone should be run: a `terraform apply` whose exit code is treated as evidence is not
evidence. Every resource is verified through an independent `aws` CLI call afterwards, and
the application is verified by driving real traffic — not by observing that the tasks
started.

---

### M30 — Launch Readiness & Portfolio Artefacts

**Objective.** Produce everything needed for the platform to be understood, demonstrated,
and discussed by someone who has never seen it — and to leave the repository in a state a
new engineer could join.

**Why this milestone exists.** The platform's technical work is worthless if it cannot be
explained in the settings that matter: a README a recruiter skims, a demo a interviewer
watches, a diagram a reviewer reads, an answer to "why did you do it that way?" This is also
where V2's own documentation obligations are discharged and the project's state is made
consistent.

**Features / deliverables**
- README v2: what it is, architecture at a glance, quickstart, live links, screenshots.
- Architecture diagrams: system context, service topology, request path (both credential
  types), the mode-isolation model, event flows, deployment topology.
- Seeded demo data and a reset script so the live environment always demos well.
- A scripted demo walkthrough, and a recorded video.
- Interview notes: the V2 decision log distilled into the ten questions this system invites,
  with the answer and the trade-off for each.
- A consolidated design-decision index across V1 and V2 (D1–D97 + D98 onward).
- Operational runbooks: deploy, rollback, key rotation, incident triage, teardown.
- Repository hygiene: consistent module structure, dead code removed, `CONTRIBUTING.md`,
  a documented local-setup path that a stranger can follow successfully.
- Final consistency pass over this document.

**Testing strategy.** The local-setup path is followed from a clean clone by an automated
script to prove it actually works — the same "verify, do not assume" discipline applied to
onboarding. The demo script is executed end to end against the deployed environment.

**Completion criteria**
- [ ] A stranger can clone, follow the README, and run the platform locally.
- [ ] The demo runs end to end against the deployed environment.
- [ ] Every diagram matches the implemented reality.
- [ ] This document is complete and consistent through M30.
- [ ] Runbooks exist for every operational action V2 introduced.

**Dependencies.** M29.

**Risks**
| Risk | Mitigation |
|---|---|
| Documentation describes an aspirational system rather than the real one | Every claim traced to a milestone entry in §18; diagrams checked against code |
| The demo environment drifts or accumulates junk | Seed-and-reset script, run before each demo |

**Engineering notes.** The consolidated decision index across both versions is likely the
single most useful artefact this milestone produces. Roughly 120 recorded decisions, each
with alternatives and rationale, is an unusually complete record of *why* a system looks the
way it does — and it is exactly the material that makes a technical conversation about this
platform substantive rather than descriptive.

---

## 6. Dashboard Planning

One Next.js application, five surfaces, RBAC-gated. This section is the detailed
specification M23 and M24 implement against.

### 6.1 Information architecture

```
/                          Public landing
/docs/**                   Documentation (SSG)  ............................ M25
/reference/**              API reference from OpenAPI  ..................... M25
/signup  /login  /verify  /reset                                            M23

/dashboard                 Overview (mode-scoped)  ......................... M23
  /payments                List + filters + saved views  ................... M23
    /[id]                  Detail: timeline, refunds, events, logs  ........ M23
  /refunds                 List + detail  .................................. M23
  /balance                 Balance + ledger entries  ....................... M24

/developers
  /api-keys                Create / rotate / revoke  ....................... M23
  /webhooks                Endpoints + subscriptions  ...................... M24
    /[id]/deliveries       Delivery log + replay  .......................... M24
  /logs                    API request log + detail  ....................... M24
  /events                  Event browser  .................................. M24
  /simulations             Sandbox scenario controls  ...................... M24

/analytics                 Volume, success, latency, errors, usage  ........ M24

/settings                  Profile, merchant, team, API version pin  ....... M23/M24

/admin                     Server-side gated  .............................. M24
  /merchants  /health  /dlq  /flags
```

### 6.2 Merchant dashboard (M23)

**Overview.** Today's volume, success rate, recent payments, webhook health, quota
headroom — each tile linking to its detail surface. Mode-scoped, with a clear empty state
for a brand-new account that guides toward the quickstart rather than showing zeros.

**Payments list.** Columns: id, amount, currency, status, method, created, metadata
preview. Server-side filtering on status, date range, amount range, currency, and metadata.
Cursor-paginated with infinite scroll. Saved views persisted per user. Bulk export to CSV.

**Payment detail.** The FSM timeline as the primary visual — every transition with its
timestamp, actor, and the event it emitted. Alongside: amounts (authorized / captured /
refunded / refundable), the sandbox decision (why it was approved or declined), related
refunds, related events, related webhook deliveries, related API requests, and editable
metadata. Actions: capture, refund (full/partial), void — each with a confirmation dialog
that names the mode explicitly.

**Refunds.** List and detail, with the reverse link to the parent payment.

**Balance (M24).** Current balance per currency and mode, split pending vs settled, with
the ledger entries that produced it — the first time transaction-service's data is visible
anywhere.

### 6.3 Developer console (M23/M24)

**API keys (M23).** Table of keys: name, type, mode, prefix, scopes, created, last used.
Create opens a scope picker and a mode selector; on submit the secret is displayed **once**
behind an explicit copy-and-acknowledge step. Rotate offers a grace window with a clear
explanation of what happens to the old key. Revoke requires typing the key name — a
deliberate friction on a destructive, irreversible action.

**Webhooks (M24).** Endpoints table with URL, subscribed events, status, and recent success
rate. Create/edit with an event-type picker (grouped, searchable, wildcard-capable) and a
URL validator that rejects non-HTTPS and private ranges *in the UI as well as the API*, so
the failure is explained rather than merely returned. Signing secret revealed once, with
rotation support. "Send test event" produces a real delivery a developer can inspect.

**Delivery log (M24).** Every attempt: timestamp, status, duration, attempt number, next
retry time. Detail shows the exact signed request (headers and body) and the endpoint's
response (status, headers, truncated body) or the error. **Replay** re-delivers the same
event id. This screen is the primary debugging tool the platform offers.

**Request log (M24).** Every API request: timestamp, method, path, status, duration, key,
mode, IP, request id. Filterable by all of those. Detail shows the redacted request and
response bodies and links to any object the request created or touched. A 4xx shows the
error code with a link to its documentation entry.

**Events browser (M24).** The merchant-facing event log, showing exactly the payload
delivered to webhooks, with which endpoints received it and their outcomes.

**Simulations (M24).** Choose a scenario (decline, insufficient funds, processing error,
timeout, latency injection, delayed settlement), set a count or duration, apply. Active
overrides shown with a countdown and a cancel action. The decision log shows what the engine
actually decided and why.

### 6.4 Analytics (M24)

Time-series and breakdowns over `payment_stats_hourly` and `api_usage_daily`, with a range
picker (24h / 7d / 30d / custom) and mode scoping:

- **Payment volume** — count and amount over time, stacked by status.
- **Success rate** — approved vs declined vs errored, with the top decline reasons.
- **API latency** — p50/p95/p99 by endpoint.
- **Error breakdown** — by error code, linking into the request log filtered to that code.
- **Usage** — requests against quota, by key, with headroom.
- **Webhook health** — delivery success rate, retry volume, endpoints near auto-disable.

Charts follow one shared, accessible palette; every encoding that uses colour also uses a
second channel (shape, position, or label) so the charts remain readable without colour
perception. Both light and dark themes are first-class, not an afterthought.

### 6.5 Admin console (M24)

Server-side gated at the route level, so an admin bundle is never served to a non-admin, and
independently enforced at the API level.

- **Merchants** — all merchants, their modes, volume, key and webhook health; drill into any
  merchant's objects for support purposes, with the access itself audited.
- **Platform health** — service health, Kafka consumer lag, DLQ depths, error rates.
- **DLQ inspector** — read `payment.events.dlq` and `webhook.deliveries.dlq`, inspect the
  failure and the original message, and replay. Safe because every consumer is idempotent
  on `eventId`.
- **Feature flags** — enable capabilities per merchant.
- **Audit** — who did what in the admin console.

### 6.6 Cross-cutting UI concerns

| Concern | Decision |
|---|---|
| **Mode indication** | Persistent header toggle plus a coloured banner in test mode. Mode is part of every query key so a switch can never serve cached cross-mode data. |
| **Data fetching** | Server components for initial load; a client query library for interactive lists with cursor pagination and cache invalidation on mutation. |
| **Real-time** | Polling with a short interval on active screens for V2. WebSocket/SSE streaming is deliberately deferred (§15) — polling is sufficient at this scale and avoids a new infrastructure dependency. |
| **Error handling** | Error boundaries per route group; API errors surfaced with their code, message, and `request_id`, so a support conversation starts with an identifier. |
| **Loading** | Skeletons matching final layout; never a spinner over a full page. |
| **Empty states** | Every list has a designed empty state that teaches the next action. |
| **Accessibility** | WCAG AA contrast, full keyboard navigation, focus management on dialogs, screen-reader labels, respect for reduced-motion. |
| **Theming** | Light and dark, following the OS preference with a manual override. |
| **Secrets in UI** | Displayed once, never persisted client-side, never logged, never sent to any analytics. |

---

## 7. SDK Planning

### 7.1 Shared design contract

Written once (M22), implemented four times (M22, M26). Any behaviour listed here is
identical across languages; only idiom varies.

**Configuration**

| Option | Default | Notes |
|---|---|---|
| `apiKey` | — | Required. Read from `PAYMENTFLOW_API_KEY` if unset. |
| `baseUrl` | `https://api.paymentflow.dev` | Overridable for local development. |
| `apiVersion` | SDK's pinned version | Sent as `PaymentFlow-Version`. |
| `timeout` | 30s | Per request. |
| `maxRetries` | 3 | Applies only to retryable outcomes. |
| `httpClient` | language default | Injectable, for testing and for proxy configuration. |

**Authentication.** `Authorization: Bearer <key>` on every request, plus a
`User-Agent` identifying the SDK, language, and version — which also makes SDK adoption
measurable in the request log.

**Idempotency.** Every mutating call generates a UUIDv4 `Idempotency-Key` unless the caller
supplies one. The generated key is created **once per logical call** and reused across every
retry of that call. This is the SDK's single most important correctness property.

**Retries.** Retry on 429, 5xx, and network/timeout errors. **Never** on other 4xx.
Exponential backoff with full jitter, capped; when the response carries `RateLimit-Reset` or
`Retry-After`, that value wins over the computed backoff. Retry budget is per logical call.

**Pagination.** List methods return an iterable that transparently fetches subsequent pages,
plus explicit `page()` access for callers who want manual control. No SDK user should ever
have to implement cursor handling.

**Errors.** A typed hierarchy mirroring the M21 catalogue: `AuthenticationError`,
`PermissionError`, `InvalidRequestError`, `IdempotencyError`, `RateLimitError`,
`ApiConnectionError`, `ApiError`. Every error carries `code`, `message`, `param`,
`requestId`, `statusCode`, and `docUrl`.

**Webhooks.** `webhooks.constructEvent(payload, signatureHeader, secret, tolerance)` —
verifies the HMAC in constant time, enforces the timestamp tolerance, and returns a typed
event. Throws distinctly on an invalid signature versus a stale timestamp, because those are
different operational problems.

**Forward compatibility.** Unknown response fields and unknown enum values must not throw.
This is a tested requirement, not a convention, and it is what makes M21's "additive changes
are never breaking" policy actually true for SDK users.

### 7.2 Per-language specifics

| | Node / TypeScript | Python | Java | Go |
|---|---|---|---|---|
| Milestone | M22 | M22 | M26 | M26 |
| Target | Node 18+, ESM + CJS | 3.9+ | JDK 17+ | 1.21+ |
| HTTP | native `fetch` | `httpx` | `java.net.http.HttpClient` | `net/http` |
| Async | promises | sync + async client | sync + `CompletableFuture` | `context.Context` throughout |
| Types | full TS types | type hints + `py.typed` | records + sealed errors | structs + wrapped errors |
| Package | npm `paymentflow` | PyPI `paymentflow` | Maven Central | Go module |
| Deps | zero runtime | `httpx` only | zero beyond JDK | stdlib only |

Java targets 17 rather than the platform's own 25 because an SDK must run in its users'
environments. Minimal dependencies everywhere is deliberate: a payments SDK that drags in a
transitive dependency tree is a supply-chain liability for every integrator.

### 7.3 Examples, testing, and release

**Examples per SDK** (identical set across languages): quickstart, full lifecycle, webhook
receiver, error handling, pagination, retry/idempotency demonstration, and a mode-switching
example. Every one of these is executed in CI (M25's sample verification).

**Testing.** Unit tests per behaviour; integration tests against a real local stack; the
cross-language equivalence scenario (two languages in M22, four in M26); packaging
verification by consuming the built artefact from a clean project.

**Release.** SDK semver, independent of the dated API version, with a published
compatibility matrix. Automated changelogs. Publishing is **dry-run only** until explicitly
approved — pushing a package to a public registry is irreversible and effectively claims a
public name.

---

## 8. Sandbox Planning

### 8.1 Test cards

Seeded as reference data in a Flyway migration so the catalogue is versioned, and rendered
into the documentation from the same source so the two cannot drift.

| Token | Behaviour |
|---|---|
| `pm_card_visa` | Approves |
| `pm_card_mastercard` | Approves |
| `pm_card_amex` | Approves |
| `pm_card_chargeDeclined` | Declines — `card_declined` |
| `pm_card_insufficientFunds` | Declines — `insufficient_funds` |
| `pm_card_expired` | Declines — `expired_card` |
| `pm_card_incorrectCvc` | Declines — `incorrect_cvc` |
| `pm_card_fraudulent` | Declines — `fraudulent` |
| `pm_card_processingError` | Errors — `processing_error` |
| `pm_card_authRequired` | Requires an extra authentication step |
| `pm_card_slow` | Approves after injected latency (~5s) |
| `pm_card_delayedSettlement` | Authorizes now, captures asynchronously later |
| `pm_card_captureFails` | Authorizes, then fails at capture |
| `pm_card_refundFails` | Captures, then fails at refund |
| `pm_card_disputed` | Captures, then raises a dispute event |

### 8.2 Simulation controls

Beyond test cards, a merchant can set an override in test mode, scoped to
`(merchantId, mode)`, that applies to the next N requests or for a duration:

| Scenario | Effect |
|---|---|
| `force_decline` | Every authorization declines with a chosen code |
| `force_error` | Every request returns a chosen platform error |
| `inject_latency` | Adds a fixed or random delay |
| `force_timeout` | The request exceeds the platform timeout |
| `force_rate_limit` | Returns 429 with realistic headers |
| `delay_settlement` | Captures settle after a chosen delay |
| `duplicate_webhooks` | Delivers each webhook twice, to test consumer idempotency |
| `webhook_failure` | Simulates the endpoint failing, to exercise the retry schedule |

Precedence is explicit and tested: **override → test card → mode default.**

### 8.3 What each scenario exists to let a developer prove

| Developer needs to prove | Sandbox provides |
|---|---|
| Declines are handled and surfaced to their user | Decline cards and `force_decline` |
| Retries do not double-charge | `force_timeout` plus idempotency replay |
| Their webhook consumer is idempotent | `duplicate_webhooks` |
| Their webhook endpoint survives platform retries | `webhook_failure` and the retry schedule |
| Async settlement is handled, not assumed synchronous | `pm_card_delayedSettlement` |
| Backoff is implemented correctly | `force_rate_limit` |
| Timeouts do not leave their system inconsistent | `inject_latency`, `force_timeout` |
| Partial refunds accumulate correctly | The existing partial-refund FSM path |

### 8.4 Live mode's simulated acquirer

Live mode must be observably different from test mode, or it teaches nothing. It applies a
small stochastic decline rate, a realistic latency distribution, and an occasional transient
error — and it is **not** developer-controllable, exactly as a real acquirer is not. The
distribution is configurable per environment and recorded in the decision log so behaviour
stays explainable after the fact.

### 8.5 Idempotency and duplicate-request testing

The sandbox makes V1's idempotency subsystem (D33/D34) externally demonstrable for the first
time. Documented, testable behaviours:

- Same key, same body → the stored response, replayed, with no second side effect.
- Same key, different body → rejected with a distinct, documented error code.
- Concurrent identical keys → the second fails fast with 409 (V1's documented simplification
  — stated in the docs as a deliberate behaviour, not left for a developer to discover).
- Keys are scoped per merchant **and per mode**, so a test key and a live key using the same
  string never collide.

---

## 9. API Documentation Planning

### 9.1 Structure

```
/docs
  /introduction              What the platform is; core concepts; object model
  /quickstart                First payment in <5 minutes, per language
  /authentication            Keys, types, modes, scopes, rotation, storage guidance
  /errors                    Error shape, full code catalogue, handling patterns
  /idempotency               Why, how, key selection, retry safety
  /pagination                Cursors, limits, iteration
  /versioning                Date-based versions, pinning, deprecation policy
  /rate-limits               Limits, headers, backoff guidance, quotas
  /testing                   Test cards, simulations, scenario recipes
  /webhooks                  Setup, event catalogue, signature verification, retries, replay
  /going-live                Test vs live, checklist, what "live" means here
  /sdks/{node,python,java,go}
  /changelog
/reference                   Generated per resource from OpenAPI
```

### 9.2 Principles

- **Generate what can be generated.** The reference, error catalogue, test-card table, and
  event catalogue all render from the same sources the services use. Hand-written prose is
  reserved for concepts.
- **Every sample is executed in CI.** A sample that stops working fails the build. This is
  the mechanism that prevents the universal "the quickstart no longer works" failure.
- **Multi-language everywhere.** Every reference entry shows curl, Node, Python, Java, and
  Go, with the reader's choice persisted across pages.
- **Show the response.** Every example includes a real response body, not just a request.
- **Explain the trade-off.** Where the platform made a deliberate choice a developer will
  notice — concurrent idempotent requests failing fast with 409, capture being
  all-or-nothing, 404-not-403 on cross-mode access — the documentation says so plainly
  rather than leaving it to be discovered.
- **Interactive.** The console runs real test-mode requests with the reader's own key.

### 9.3 Reference page anatomy

For each resource: the object definition with every field typed and described; endpoints
with parameters, request and response schemas, and every possible error; expandable fields;
list filters; related webhook events; and multi-language samples for each operation.

### 9.4 The webhook guide

The guide that matters most, because it is where integrators most often get security wrong:

1. Register an endpoint and copy the secret.
2. Verify the signature — with the algorithm spelled out, and the SDK one-liner shown
   alongside a from-scratch implementation for languages without an SDK.
3. Enforce the timestamp tolerance, and why signature-without-timestamp is replayable.
4. Respond `2xx` fast; do work asynchronously.
5. Be idempotent on `event.id` — with `duplicate_webhooks` provided to prove it.
6. Understand the retry schedule and auto-disable.
7. Rotate secrets using the dual-secret window.
8. Debug using the delivery log and replay.

### 9.5 OpenAPI and tooling

One merged OpenAPI 3.1 document covering `/v1/*` only — `/api/v1/*` and `/internal/v1/*` are
deliberately excluded, because publishing them would imply a promise the platform does not
intend to make. It is committed as a baseline, diffed in CI for breaking changes, published
as a build artefact, downloadable from the docs, and used to generate SDK types, the
reference site, and the interactive console. Contract tests validate live responses against
it so the spec cannot drift into fiction.

---

## 10. Engineering Principles

V2 inherits every V1 principle unchanged. They are restated here because this document is
meant to stand alone for someone who starts at V2, with the V2-specific application noted.

| Principle | V1 established | V2 application |
|---|---|---|
| **Depth before breadth** | One vertical slice fully working before widening | Two SDKs properly before four; portal split across two milestones; one new service, not five |
| **Database-per-service** | Schema-per-service, no cross-service joins | `sandbox` is its own schema; no V2 feature introduces a cross-schema query |
| **Async by default** | Kafka for propagation, sync REST only where consistency demands it | Request logging, webhook fan-out, and delayed outcomes are all async; the only new sync hops are key verification and the sandbox decision, both resilience-wrapped |
| **At-least-once + idempotent consumers** | No mythical exactly-once | Webhook replay and DLQ replay are safe *because* of this; SDKs make integrators idempotent too |
| **Transactional outbox** | Never dual-write DB and Kafka | Webhook events and merchant events follow the same pattern |
| **Money as integer minor units** | `BIGINT` + currency code | Unchanged; every new money field follows it |
| **Explicit state machine** | Illegal transitions rejected | sandbox-service advises; payment-service remains the sole FSM owner |
| **Clean Architecture / SOLID / DDD** | Per service, constructor injection, immutable records | New services and modules follow identically; `sandbox` is a genuine bounded context |
| **Repository pattern** | Spring Data JPA, no leaking entities | Mode filtering is applied at this layer, which is why it cannot be forgotten |
| **Security-first** | Secrets never hardcoded, JWT RS256, zero-trust per service | Signed internal context, scoped keys, redaction, SSRF defence, a written threat model |
| **Observability-first** | Micrometer, tracing, structured logs | Extended to user-facing observability: request logs, delivery logs, usage |
| **Cloud-native** | Containers, ECS, IaC | Every new component is Terraform-managed from its first deploy |
| **Verify, never assume** | The rule that caught D38, D51, D52, D82, D83, D89, D95 | Every completion criterion in §5 is phrased as something actually run |
| **No duplicated code** | Third occurrence moves to `common-lib` | Mode filtering, internal-context verification, redaction, and list/pagination all live in shared modules |
| **YAGNI with a recorded reason** | D14, D31, D42, D61 deferred work until a real consumer existed | V2 is largely the milestone where those deferrals' consumers finally arrived — which is the pattern working as intended |

**Two V2-specific additions:**

- **Structural enforcement over disciplined convention.** Where a guarantee can be made
  impossible to violate (mode filtering at the repository layer, ownership from context
  rather than a path parameter, generated reference documentation), it is — because
  conventions decay and structure does not.
- **The platform's own lessons are encoded in its SDKs.** Idempotency, retry safety, and
  signature verification are things integrators get wrong; the SDK is where the platform
  pays that cost once on their behalf.

---

## 11. Technical Decisions & Trade-offs (V2 log)

Continuing V1's numbering. V1 ended at D97. Decisions made **during planning**; further
decisions are appended as milestones are implemented.

| # | Decision | Alternatives | Rationale |
|---|---|---|---|
| D98 | Three-tier API surface: `/v1/*` public (key auth, versioned, documented), `/api/v1/*` internal-dashboard (JWT session, undocumented, freely changeable), `/internal/v1/*` service-to-service (never routed publicly) | One API surface serving both browsers and servers | A public contract must be frozen and versioned; a dashboard API must iterate freely. Merging them forces one of those properties to lose. The split also makes "is this a public promise?" answerable by path alone, and it keeps V1's existing `/api/v1/*` behaviour untouched — the lowest-risk way to add a public API to a running system |
| D99 | The single-active-API-key model (V1's D29, enforced by a partial unique index) is **superseded**: many concurrent keys per merchant, each independently scoped, moded, and revocable | Keep one active key; add mode by issuing a second "kind" of single key | A developer platform requires multiple concurrent keys by definition — separate keys per environment, per service, per teammate — and rotate-with-grace is impossible when only one key may be active. D29 was correct for V1's single-tenant, unused-key reality; the constraint that made it safe is exactly the constraint a platform cannot have |
| D100 | The gateway resolves the API key once and asserts merchant context downstream via **HMAC-signed internal headers**, verified by a `common-lib` filter in every service | (a) each service verifies the key itself; (b) exchange the key for a short-lived internal JWT at the edge | (a) multiplies a network verification hop by every service on the path and requires an API-key filter in five services; (b) is arguably the most correct design but introduces a token-authority round trip and a new identity↔merchant dependency cycle. Signed headers give a *verifiable* assertion — not merely a trusted one — for one HMAC per hop, preserving D17's zero-trust posture in substance. (b) remains the documented upgrade path if scale or a compliance requirement ever justifies it |
| D101 | Mode (`test`/`live`) is a column on every merchant-scoped table with composite uniqueness, enforced automatically at the repository layer, **not** a per-query filter developers must remember | Separate databases/schemas per mode; a per-query `WHERE mode = ?` convention | Separate schemas double the migration and connection-pool surface for every service and make cross-mode admin views painful. A convention decays the moment someone adds a repository method. Repository-layer enforcement plus a reflective test that fails on any unfiltered `ModeAware` entity makes the guarantee structural |
| D102 | Cross-mode and cross-merchant access returns **404, never 403** | 403 for "exists but forbidden" | 403 confirms existence, which leaks precisely across the boundary this design exists to protect. Consistent with V1's D28 404-masking for cross-merchant access — an established platform convention, not a new one |
| D103 | `sandbox-service` is a new service that **advises** on authorization outcomes; payment-service remains the sole owner of the FSM and the only writer of payment state | Embed simulation logic in payment-service; make sandbox-service own the transition | Keeps the FSM's invariants — built in M5, load-tested in M14 — provable in exactly one place, and makes a hypothetical real-acquirer integration a replacement of one internal call rather than a restructuring of the payment lifecycle |
| D104 | "Live" mode is backed by a **simulated acquirer** with a realistic decline rate and latency distribution, not a real PSP integration | Integrate Stripe/Razorpay test APIs as a real upstream; make the platform sandbox-only with no live mode at all | A real PSP adds third-party credentials, availability, and failure modes outside this platform's control, for a project whose entire point is the platform layer. Sandbox-only would collapse the mode-isolation design — the most interesting multi-tenancy problem in V2 — into nothing. A simulated acquirer keeps both the isolation story and the "live behaves differently from test" property honest |
| D105 | Webhook signature is HMAC-SHA256 over `"{timestamp}.{body}"` with a receiver-side tolerance window, not over the body alone | Sign the body only; use asymmetric signatures | A signature over the body alone is replayable forever; including the timestamp *inside* the signed payload is what makes the replay window enforceable. Asymmetric signing would let merchants verify without a shared secret but adds key distribution and rotation complexity disproportionate to the threat here |
| D106 | Webhook delivery gets its own topics (`webhook.deliveries[.retry|.dlq]`) rather than continuing to share `payment.events.retry` | Keep the V1 shared retry topic | V1's D46 put webhook retries on the payment retry topic when webhooks were the only consumer-side retry. With fan-out to many endpoints and an 8-attempt 24-hour schedule, webhook retry volume would dominate a topic that other concerns depend on. Separate topics keep the two failure domains independent |
| D107 | Cursor pagination (opaque, **signed**) for every public list endpoint; V1's offset-based `PageResponse` is retained only for existing internal endpoints | Offset pagination everywhere | A payments list is append-heavy and constantly changing; offset pagination silently skips or repeats rows under concurrent inserts — for a financial list that is a correctness bug, not a cosmetic one. Signing prevents the cursor from becoming a parameter an attacker can manipulate into another tenant's range |
| D108 | Date-based API versioning (`PaymentFlow-Version`) with per-merchant pinning, over URL-path versioning | `/v2/`, `/v3/` paths; no versioning at all | Path versioning forks every endpoint on every breaking change and pressures teams to batch breaking changes into rare, large releases. Date-based pinning lets the platform ship continuously and makes "which revision is this integrator on?" a data question. The cost is a transformation layer, which is why the number of concurrently supported revisions is capped by policy |
| D109 | API request logging is emitted **asynchronously with a bounded buffer that drops on backpressure**, never blocking or failing a request | Synchronous write; unbounded buffer; block on a full producer | Observability infrastructure that can fail a customer request is worse than no observability. V1 learned an adjacent version of this in D89, where an exporter with no receiver silently retried forever. Dropping log events under extreme load is the correct failure mode; M28 proves it by experiment rather than by reading the code |
| D110 | SDKs are **hand-written over generated types**, not fully generated | Fully generate all four SDKs from the OpenAPI spec | The properties that make an SDK worth using — automatic idempotency keys preserved across retries, rate-limit-aware backoff, auto-paginating iterators, an ergonomic typed error hierarchy, the webhook verification helper — are exactly what generators produce badly. Types are generated (and CI-diffed so they cannot go stale); the ergonomic layer is written once per language against a shared behavioural contract |
| D111 | Node and Python in M22; Java and Go in M26 | All four simultaneously | Depth before breadth (V1's own first design principle). The design is validated against a second language, real usage, and a documentation pass before being ported twice more — rather than inventing four designs in parallel and discovering the divergences later |
| D112 | One Next.js application for dashboard, developer console, admin, and docs | Separate dashboard and docs apps; three separate apps | One auth session, one build and deploy target, one design system, and genuine component reuse — the interactive docs console needs the dashboard's key picker. Route groups plus server-side gating give the isolation that separate apps would provide, without three deployment pipelines |
| D113 | V2 is built **local-first**, with a single AWS deployment milestone (M29) at the end | Deploy each milestone to AWS as it lands; never deploy V2 to AWS | V1's infrastructure is already billing continuously; adding services incrementally would multiply that cost across sixteen milestones for no engineering benefit, since every milestone is verifiable locally against the full stack. Mirrors V1's own successful shape (M0–M8 local, M11–M12 cloud) |
| D114 | The browser **never** holds a secret API key: the portal authenticates with a session JWT against `/api/v1/*`, and displays a newly created secret exactly once, directly from the creation response | Let the dashboard call `/v1/*` with the merchant's secret key | A secret key in a browser is a secret key in every extension, every XSS payload, and every analytics bundle. This is the single hardest rule in V2's frontend design, and it is why D98's API split exists at all |
| D115 | Every documentation code sample is **executed in CI** against a live local stack | Review samples manually; mark them illustrative | Converts documentation from prose that decays into an artefact with a test suite. It is the only mechanism that reliably prevents the "quickstart no longer works" failure every API platform eventually suffers |
| D116 | `api_request_log` is day-partitioned with a scheduled pruner and a pre-pruning rollup, all shipped **in the same milestone** as the log itself (M20) | Add the log now, add retention when it becomes a problem | A high-volume log table without a retention story is a scheduled outage. Deciding retention while designing the writer costs nothing; retrofitting it under storage pressure costs an incident |
| D117 | Publishing to public package registries (npm, PyPI, Maven Central) is **dry-run only** until explicitly approved | Publish as part of the SDK milestones | Publishing is irreversible, claims a public name, and is exactly the class of outward-facing action this project's standing rules require approval for — the same rule that governs `terraform apply` |
| D118 | The internal verify endpoint's response (and the signed internal-context header set it produces) is extended beyond §4.3's original minimal shape to also carry `contactEmail`/`webhookUrl` | Keep the endpoint to exactly `{merchantId, keyId, mode, scopes, status}` as originally specified | Confirmed with the user before implementing — a real gap in the original plan: `payment-service`'s event publisher (D43) needs these fields to embed in every payment event, and task 10's whole premise (skip the merchant-service Feign call on the API-key path) leaves no other path to learn them. Still merchant-service's *only* internal surface — a richer payload on the one endpoint, not a second endpoint |
| D119 | The gateway's API-key path runs on a **second, `@Order(1)` `SecurityWebFilterChain`** scoped to `/v1/**` with no `oauth2ResourceServer` configured at all, ahead of the unmodified V1 chain (now `@Order(2)`, matching everything else) | Add `.pathMatchers("/v1/**").permitAll()` to the single existing chain | Found while implementing, not assumed: Spring Security's OAuth2 resource-server filter attempts to parse *any* `Authorization: Bearer ...` value as a JWT unconditionally — it does not defer to a pre-populated `SecurityContext`. A single shared chain could only express "skip authentication here" (permitAll, fail-*open* for a credential-less request), not "authenticate this path a different way." Two chains keep both paths fail-closed: `/v1/**` relies entirely on `ApiKeyAuthenticationWebFilter`'s own `ReactiveSecurityContextHolder.withAuthentication(...)`, and the JWT chain is untouched, not refactored |
| D120 | `ApiKey.rotateWithGrace` grants the old key a `graceExpiresAt` timestamp instead of revoking it immediately; `isActive(now)` treats grace expiry as a pure time comparison | Revoke the old key immediately, or flip it inactive via a scheduled job when grace elapses | A rotated-out key must keep authenticating for a bounded window so an in-flight deploy using the old secret doesn't fail mid-rotation (task 2). A stored timestamp checked at read time needs no scheduler and cannot drift out of sync with a cron cadence |
| D121 | `last_used_at` is updated via a short-TTL Redis `SETNX`-style marker (`apikey:lastused:<keyId>`, throttled to once per `lastUsedThrottle` window) plus a fire-and-forget `CompletableFuture` write, not a synchronous update on every verify | Update the column inline on every successful `verify()` call | Task 2's explicit constraint ("never one UPDATE per request") — a cache-hit-heavy key would otherwise turn every downstream request into a database write. A missed or delayed timestamp update is never worth blocking, or even slowing down, the request that triggered it |
| D122 | merchant-service's `ApiKeyService.revoke()` deletes the gateway's `apikey:v1:<sha256>` Redis entry directly (same Redis instance, shared key-namespace convention documented in both services) rather than merchant-service calling the gateway, or the gateway polling | Leave revocation to the cache's own TTL; or add a synchronous revoke-notification call from merchant-service to the gateway | M15's own risk table calls for "short TTL plus explicit Redis eviction on revoke" — a revoked key must stop authenticating immediately, not just after its TTL lapses (verified by the manual E2E's revoke-then-immediately-call check). Direct Redis deletion needs no new network call or service dependency between merchant-service and the gateway, since both already share the same Redis instance |
| D123 | On the API-key path the gateway **removes the client's `Authorization` header** before proxying downstream; the signed internal-context headers become the request's sole downstream credential | Forward the API key downstream unchanged alongside the internal context | Found during post-M15 E2E validation: a downstream service's OAuth2 resource server parses *any* forwarded `Authorization: Bearer …` value as a JWT and rejects the request 401 ("Malformed token") before the internal context is consulted — the same "parses any Bearer unconditionally" behaviour D119 handled at the gateway, which also applies to whatever the gateway *forwards*. The API key must not leak past the edge anyway (defence in depth); stripping it is both the fix and the correct security posture |
| D126 | audit-service records `mode` as a **nullable** column, verbatim from the envelope (null when absent), with **no backfill** and **no null→live coercion** — deliberately unlike the NOT-NULL + backfill-live pattern M16.2–16.4 use | Make `audit_log.mode` NOT NULL and backfill existing rows to `'live'`, matching every other M16 table for consistency | Audit is a faithful, schema-agnostic recorder (D44) consuming *two* streams through one method: `payment.events` (which carry a mode) and `merchant.events` (key/merchant lifecycle, mode-less). A mode-less event — e.g. a **test**-key `ApiKeyIssued` — coerced to `'live'` would be a factual lie in an immutable audit trail. Audit partitions nothing (it appends one row per event; it never resolves a per-mode row/account), so it has no reason to apply the null→live interpretation that is a *consumer's* choice for its *own* partitioning. Existing rows genuinely predate mode or came from a mode-less stream, so null ("declared no mode") is the truthful value. The check still rejects any non-test/live string; a CHECK passes on NULL, so null stays valid. The M19 Events API filters payment events by concrete test/live; mode-less events correctly don't match |
| D125 | `EventEnvelope` gains `mode` as a **nullable, `NON_NULL`-omitted** field with a backward-compatible mode-less constructor + factory retained alongside the new mode-carrying ones; a `null` mode is read as `"live"` by every consumer; `schemaVersion` is **deferred to M21** | Add `mode` as a required (non-null) field and update every producer/consumer/test in one commit; also add `schemaVersion` now per §4.7 | M16.1 must be shippable as common-dto-only and leave the wire form byte-identical until a producer opts in — a required field would break every existing 4-arg `of(...)`/6-arg constructor caller (all in already-built consumer tests) and change the serialized JSON immediately, forcing a giant cross-service commit. Nullable+`NON_NULL` makes the producer (M16.2) and each consumer (M16.3–6) independently committable, with `null→live` matching the row-backfill semantics. `schemaVersion` is a genuine placeholder until M21's versioning gives it a consumer, so it is deferred rather than shipped unused |
| D124 | Downstream, `InternalContextFilter` is registered **inside** each servlet service's Spring Security chain (`http.addFilterBefore(internalContextFilter, AuthorizationFilter.class)`), not as a standalone servlet filter ahead of the chain; `common-lib` provides it as a bean with automatic servlet registration disabled | Keep the original design: a globally auto-registered `FilterRegistrationBean` ordered ahead of `FilterChainProxy`, so no service's `SecurityConfig` need change | The original design (as `MerchantContextAuthenticationToken`'s own javadoc aspired to) was structurally impossible: a filter ahead of the chain sets an `Authentication` that `SecurityContextHolderFilter` replaces at the start of the chain, so the request reaches `AuthorizationFilter` unauthenticated. An authentication filter must run *within* the chain. Wired in payment-service (M15's only internal-context consumer); other servlet services wire it when they gain `/v1` routes. Guarded by a new `payment-service` regression test against the real chain — the original miss existed because the gateway integration test stubbed payment-service |
| D127 | (M17) A simulation override's `remaining_count` is consumed via an atomic conditional `UPDATE … WHERE remaining_count > 0`, not `@Version` optimistic locking | Optimistic locking on `simulation_overrides`, matching the platform's other aggregates | Optimistic locking is correct for an aggregate whose invariants span fields; `remaining_count` is a counter on a hot row under concurrent authorizations, where optimistic locking converts contention into retry storms for no invariant that spans fields. `Payment` keeps optimistic locking untouched — this adds to the platform's vocabulary, not a replacement |
| D128 | (M17) sandbox-service's advisory call is idempotent via a caller-supplied `decision_key`, unique on `decision_log`; the log doubles as the idempotency store | A separate idempotency table alongside the log | The advice call has a side effect (override consumption) but is wrapped in payment-service's Resilience4j Retry (M8 shape) — a naive retry would double-consume an override. One unique key, one table, gives idempotency and the audit trail together rather than two mechanisms that could drift apart |
| D129 | (M17) The sandbox decision is obtained **before** payment-service opens its database transaction; authority over the payment's state is re-established by re-loading it under optimistic locking once the decision returns | Call sandbox from inside the same `TransactionTemplate` block as the state mutation | Holding a pooled DB connection across a network call (up to ~5s under `pm_card_slow`/`inject_latency`) risks connection-pool exhaustion under exactly the load M14 measured. The pre-transaction read is advisory only (token/amount + a fail-fast FSM check); the FSM's optimistic-lock guarantee is unweakened because authority is re-established, not assumed, when the transaction opens |
| D130 | (M17) `payments.payment_method_token` is a nullable column with no backfill; a token-less payment resolves to the mode default (test → auto-approve, live → the simulated acquirer) | Require a token on every payment; add a synthetic default token | Keeps M17 additive to M16/M5: every existing integration test and every existing caller that never sends a token continues to behave exactly as before. This is the property that lets M17 introduce a payment-method concept without touching a single M16.2 test |
| D131 | (M17) Webhook-path simulation scenarios (`duplicate_webhooks`, `webhook_failure`, delayed/out-of-order delivery) are **defined** in M17's override vocabulary and schema, but **enacted** by M18 (which reads the active override during delivery) | Reject these scenarios until M18 ships; or pull webhook-delivery machinery forward into M17 | Builds the schema and control API once rather than extending them again in M18. Accepted trade-off: between M17 and M18, setting one of these overrides returns 200 with an explicit `enactedFrom: "M18"` marker rather than doing anything — an honest no-op, not a silent one |
| D132 | (M17) payment-service depends on an `AuthorizationAdvisor` **port** (an acquirer-neutral decision contract); `SandboxAuthorizationAdvisor` is the one adapter behind it. No provider-selection strategy, multi-provider config, or separate adapter service is introduced | (a) A concrete `SandboxAdvisor` with no interface, matching `MerchantResolver`'s precedent; (b) a full routing/strategy abstraction for provider selection | (a) risks sandbox-specific vocabulary (`source`, `latencyMs`, test-card identity) leaking into `PaymentResponse`/`PaymentEventPayload` before M21 freezes the public contract — cheap to fix now, a versioned-contract migration to fix after. (b) encodes a guess about a selection axis with zero real-PSP experience; real PSP integration is explicitly beyond V2 (§15). The port's job is to make any such leak an explicit, reviewable diff, not to enable a swap that costs nothing to make either way |
| D133 | (M18) The `/api/v1/webhook_endpoints` dashboard mirror named in §5/M18 task 2 is **deferred to M23**; M18 builds only the key-authenticated `/v1/webhook_endpoints` surface, and notification-service gains **no OAuth2 resource server at all** — `InternalContextFilter` is its sole authentication mechanism, exactly as sandbox-service's (M17.2) | Build both surfaces in M18 as the task list literally specifies | Confirmed with the user before implementing. The mirror's only consumer is the developer portal, which does not exist until M23; shipping it now means a second `SecurityFilterChain`, a JWKS dependency, and a JWT decoder in a service no browser will call for five milestones — carried, maintained, and regression-tested across M18.3–M18.9 for nothing. This is the same YAGNI-with-a-recorded-reason discipline that produced D14/D31/D42/D61, and M23 is the milestone whose own scope already includes wiring every dashboard surface. The `/v1` surface is the one that is a public promise; deferring the *undocumented, freely-changeable* tier (D98) costs nothing that D98 does not explicitly permit |
| D134 | (M18) The **first** delivery attempt is dispatched through the `webhook.deliveries` topic (§4.7) rather than made inline post-commit as V1 does (D46); one consumed `payment.events` message writes the canonical event plus N delivery rows and publishes N dispatch messages | Keep V1's inline first attempt and add only `.retry`/`.dlq`, as §5/M18 task 7 alone would imply | Confirmed with the user before implementing. V1's inline attempt was correct when a merchant had exactly one URL: one HTTP call, one thread, bounded. Fan-out changes the arithmetic — N endpoints × the per-attempt timeout, on a `payment.events` listener thread shared with every other merchant — which is precisely the "a slow endpoint starves delivery for everyone" risk M18's own risk table names, arriving on the *first* attempt rather than a retry. Routing dispatch through a topic keeps the `payment.events` consumer's work bounded regardless of endpoint count, gives per-endpoint bulkheads somewhere natural to live, and reconciles §4.7's three-topic table with task 7's two. Cost: one Kafka hop of latency before the first attempt, which is invisible against an 8-attempt/24-hour schedule |
| D135 | (M18) V1's `merchants.webhook_url` is adopted into a real `webhook_endpoints` row **lazily, from the payment event payload**, the first time an event arrives for a merchant that has a URL but no registered endpoint — flagged `migrated_from_legacy` | (a) A one-off operational backfill script reading both schemas at M18.9; (b) merchant-service publishes an adoption event on `merchant.events` and notification-service consumes it | Confirmed with the user before implementing. §5/M18 task 10 describes this as a migration, but `merchants` lives in merchant-service's schema: notification-service cannot read it (D4, schema-per-service) and deliberately never calls merchant-service (D43 — the URL already rides on every payment event). (a) puts a cross-schema step outside the migration system and outside CI, exactly the class of manual operation that is correct once and forgotten thereafter; (b) is the cleanest long-term shape but pulls merchant-service changes, a new topic consumer, and a new listener into a milestone that already changes notification-service's charter more than any milestone has changed a service since M5. Lazy adoption needs no new dependency, no new schema access, and no downtime, and it is self-healing: a merchant who sets a URL after M18 ships is adopted on their next event rather than missed by a script that already ran. Per §13-Q9 the column itself is kept and marked deprecated, not dropped |
| D137 | (M18.6) Webhook signing secrets are stored **encrypted** (AES-256-GCM, key from configuration), not SHA-256 hashed — the one exception to §4.9's "every secret is stored only as SHA-256" | (a) Keep the hash, as §4.9 and M18.2 originally implemented; (b) derive each endpoint's secret deterministically from a platform master key plus endpoint id and a rotation counter (HKDF), storing no secret material at all | **A defect found in this milestone's own M18.2 work, not a preference.** §4.9's rule is correct for `sk_` keys and refresh tokens because the platform only ever *verifies* those: hash what the caller presented, compare digests. A webhook signing secret is *used* — it is the HMAC key for every outbound delivery — and a one-way digest cannot produce a signature the merchant, who holds the original, could reproduce. (a) is therefore not "less secure", it is **non-functional**: every delivery would have carried a signature no receiver on earth could verify, and the failure would have surfaced as integrators reporting that verification "just doesn't work" rather than as a red test. (b) is genuinely elegant — no secret material at rest at all — but makes every secret re-derivable forever by anyone holding the master key and the row, which quietly weakens the "revealed exactly once" property into "re-derivable on demand", and adds a rotation-counter concept to a schema that already models rotation with an explicit previous-secret column. Encryption is what comparable platforms do, keeps "shown once" strictly true, and fails loudly (GCM is authenticated, so a tampered ciphertext raises rather than silently yielding a wrong key). The key is handled exactly like the internal-context HMAC secret (D18/D73) and inherits its known issue: an insecure local default, with Secrets Manager wiring owned by M29 |
| D138 | (M19.1) Pagination cursors are **signed with the existing internal-context HMAC secret**, not with a new dedicated key, and are authenticated but not encrypted | (a) A separate cursor-signing key; (b) unsigned cursors, relying on repository-layer scoping alone; (c) encrypt the payload | (a) doubles the operational surface — another value in `.env`, another Secrets Manager entry for M29, another rotation story — for no separation that matters: both are server-side integrity secrets with identical lifecycles and blast radius. (b) is tempting because the repository already takes merchant and mode from the verified context (D101), so a forged cursor genuinely could not widen a query — but it would resolve to a confusing empty page instead of an error, and "it would not have worked anyway" is a poor reason to accept a forged token. (c) would imply a confidentiality property that does not exist: the payload is a timestamp and a row id the client just received in the response body |
| D139 | (M19.2) `GET /v1/payments` moves from offset `PageResponse` to cursor `CursorPage`, a **breaking change to an endpoint that already shipped**; `/api/v1/payments` keeps offset pagination, and the two tiers are split into separate controllers | (a) Keep offset on `/v1` and introduce cursors only on the new M19 endpoints; (b) move both tiers to cursors | This is the change M19 exists to make. §5/M19 states the ordering rationale outright — pagination semantics are "set once here and inherited by everything after, so getting them right before M21 freezes them is the whole point" — and D107 already decided cursors for public lists. (a) would ship the platform's flagship list endpoint with the semantics D107 rejects, permanently, since M21 freezes it a milestone later; it would also make "which pagination does this endpoint use?" a per-endpoint question forever. (b) changes a contract M23's dashboard has not been written against, for no benefit — D98 makes `/api/v1` freely changeable precisely so it can migrate later if a reason appears. The split into two controllers is forced by the same fact: before M19 the gateway rewrote `/v1/payments` onto `/api/v1/payments`, so one handler served both and could not return two envelopes |
| D140 | (M19.5) The canonical merchant-facing event vocabulary and its `evt_` id derivation move from notification-service to **`common-dto`** as `CanonicalEventType`; notification-service's `WebhookEventType` is deleted rather than kept alongside it | (a) audit-service keeps its own local copy, following the schema-per-service precedent every service uses for *event payload* shapes (D4/D36); (b) audit-service calls notification-service to resolve the shape | (a) is the established pattern and is wrong here, because it generalises the wrong property. D36's local copies exist so no service compiles against another's *internal model* — a producer's payload shape is free to change. This is the opposite: a **frozen public contract** that M21 will version and M22's four SDKs will implement, and which two services must render byte-identically for the same event. Two hand-maintained copies of a frozen contract drifting apart is exactly the documentation-and-contract drift R10 calls fatal, and there would be no test that could catch it from inside either service. `common-dto` already holds precisely this class of thing (`ApiError`, `PageResponse`, `EventEnvelope`). (b) would make a read API depend on another service being up to name its own event types, for data audit-service already has |
| D136 | (M18) The webhook signature is proved cross-language by **committed test vectors plus small `verify.js`/`verify.py` scripts run manually**, with the recorded output going in this log — not by a Gradle task that shells out to Node and Python | (a) Commit vectors and the written spec only, deferring real cross-language execution to M22; (b) wire the scripts into the build as a verification task | Confirmed with the user before implementing. M18's own risk table calls the signature scheme "subtly wrong and only discovered by an integrator" the failure mode worth spending a milestone's effort on, and (a) leaves that criterion asserted rather than demonstrated — the exact gap §14 already records for M17's test-card catalogue. (b) makes `node` and `python` build prerequisites for a Java monorepo, so a contributor with neither cannot build at all, to guard a constant that changes roughly never. Committed vectors give M22's four SDKs a shared fixture to test against, which is where the compatibility guarantee actually needs to live; running them by hand once, here, is what converts the guarantee from claimed to observed |
| D141 | (M19.8) Range and cursor predicates that participate in the **ordering** use **sentinel bounds** (`ListQuery.EARLIEST`/`LATEST`/`LAST_ID`) rather than the `(:x is null or …)` null-guard idiom; predicates that do not participate in the ordering keep their null guards | (a) Keep null guards everywhere, as M19.2 shipped; (b) build the predicate set dynamically per request (Criteria API / a Specification builder), emitting only the clauses a request actually uses; (c) maintain two hand-written query variants and pick one at call time | **A defect found by reading a plan, not by a failing test** — every assertion in M19.2 passed both before and after, because the idiom is *functionally* correct and only *structurally* wrong. Wrapping the row-wise comparison as `(:cursorCreatedAt is null or (created_at, id) < (…))` demotes it from an `Index Cond` to a `Filter`: Postgres scans from the newest row of the merchant's partition and discards everything above the cursor, so a deep page costs **O(depth)** — precisely what keyset pagination exists to avoid. Measured on 600k seeded payments one page 150 days in: 2,512 buffers and 2,568 rows discarded, versus **29 buffers and 0 discarded** unguarded. (b) is the textbook answer and produces optimal SQL for every combination, but it replaces two readable native queries with a builder, and the two clauses that need it are *jsonb containment* and *row-wise comparison* — the two things a Criteria builder expresses worst. (c) doubles the query surface for every future filter. The sentinel idiom was **already in the repository**: M19.4 adopted it in transaction-service for an unrelated reason (Postgres cannot infer a bind parameter's type from `? is null` alone), so M19.8 found that the choice made for type-inference reasons is also the one that produces the right plan. Promoting the three constants to `ListQuery` collapsed three private copies into one, which is what M19.1 said shared primitives were for. Status, currency, amount and metadata deliberately keep their guards: they do not participate in the ordering, so they cost only a `Filter` on rows the index already located, and there is no sentinel for "any status" that would not be a lie |
| D142 | (M19.8) payment-service sets `server.tomcat.relaxed-query-chars: "[,]"` so the **documented** `metadata[key]=value` filter syntax works literally as published, not only percent-encoded | (a) Publish only the percent-encoded spelling (`metadata%5BorderId%5D=A-1234`); (b) change the wire syntax to something RFC 3986 permits unreserved, e.g. `metadata.orderId=` or a repeated `metadata=k:v`; (c) relax Tomcat globally across every service | Found on the live stack, not by any test: Tomcat rejects `[` and `]` in a query string by default, so a client sending the published form got **HTTP 400 with Tomcat's HTML error page** — not merely a failure, but one that breaks the JSON error contract M21 will freeze. MockMvc could never have caught it, because it builds the request object directly and never goes through Tomcat's URI parser. (a) is honest but hostile: `metadata[k]=v` is the spelling every comparable platform uses and the one an integrator will type first, and "it works but only if your HTTP library happens to encode brackets" is a support ticket generator rather than a contract. (b) avoids the character class entirely and was genuinely tempting, but changes a syntax already published in `docs/READ_APIS.md` and already asserted by `ReadApiDocumentationConsistencyTest` for no gain beyond avoiding one configuration line. (c) widens the parser's accepted input on eight services to fix one — scoped to the two characters and the one service that serves the filter, both spellings now behave identically instead of one of them depending on which HTTP library a developer happens to use |
| D143 | (M19.8) `AnalyticsSummaryResponse` drops `@JsonInclude(NON_NULL)` so `successRate: null` is **explicitly on the wire** when no payment was attempted, rather than the field being omitted | (a) Keep the annotation and let clients treat an absent field as "unknown"; (b) return `0` for a rate over zero attempts; (c) add a separate boolean or a string enum (`"unknown"`) alongside the numeric rate | M19.6 had already decided that a rate over zero attempts is *unknown* rather than zero — charting it as zero shows a catastrophic outage every quiet hour — but the platform-wide `NON_NULL` convention then deleted the very signal that decision exists to send. The test asserted `successRate()).isNull()` **on the object**, which passes either way; the live response was the first thing to show what a client actually receives. (a) is where the bug lives: §4.10 tells clients to expect and ignore fields a version does not have, so silence makes "we measured and there is no answer" indistinguishable from "this API version has no such field". (b) is the error M19.6 explicitly rejected. (c) encodes in two fields what JSON's `null` already means, and every SDK in M22 would have to model both. No other field on this response is ever null, so removing the annotation changes nothing else on the wire, and the test now asserts the **serialized** form rather than the object |
| D144 | (M19.8) `webhook_endpoints.metadata` is **deliberately not GIN-indexed**, unlike `payments.metadata` and `refunds.metadata` | (a) Add the GIN index for consistency with the other two `metadata` columns; (b) omit `metadata` from webhook endpoints entirely, leaving §4.6's third object unimplemented | §5/M19 says metadata is "indexed for filtering", and the index therefore exists exactly where the filtering does. Payments and refunds carry a GIN index because their lists expose a containment filter that is unusable without one; the endpoint list has **no filter** — it returns every endpoint a merchant has in one mode, hard-capped at 16 — so an index here would be paid for on every write to serve a query that does not exist. (a) is consistency for its own sake, and the cost is real: GIN maintenance on a table whose rows are written by endpoint registration and rewritten by auto-disable bookkeeping. (b) would leave §4.6 and §5/M19's feature list naming three objects while the code delivered two. Recorded rather than glossed over, so that a future milestone adding an endpoint filter knows the index was considered and deferred, not forgotten |

---

## 12. Risks

Programme-level risks. Milestone-specific risks live with their milestones in §5.

| # | Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|---|
| R1 | **M15 or M16 breaks V1's working platform.** Both change the request path and the schema of every merchant-scoped table. | Medium | High | V1's full test suite and Gatling simulations are the regression gate for both. Migrations are additive with backfill, tested against seeded V1-era data. The JWT filter chain is extended, never refactored. |
| R2 | **Scope explosion.** V2's brief is genuinely larger than V1's — sixteen milestones, a new service, a frontend, four SDKs, a docs site. | High | High | Strict milestone gating; explicitly listed non-goals (§1.4); the M23/M24 and M22/M26 splits exist precisely to keep any single gate reviewable; the phase structure means Phase A alone is already a coherent, demonstrable deliverable if the programme stops early. |
| R3 | **Mode isolation has a hole.** A single unfiltered query leaks live data to a test key, which is the worst-case failure of V2's central promise. | Medium | High | Structural enforcement at the repository layer (D101), a reflective test that fails on any unfiltered `ModeAware` entity, and M27's generated isolation matrix running in CI permanently. |
| R4 | **A leaked secret key.** Long-lived credentials on third-party servers are a new exposure class V1 never had. | Medium | High | Scopes limit blast radius; revocation propagates within a short cache TTL and is tested by revoke-then-immediately-call; redaction prevents keys reaching logs; keys are shown once and stored only hashed. |
| R5 | **Webhook delivery to hostile endpoints becomes an SSRF path into the VPC.** | Medium | High | Egress allow-listing, private/link-local/metadata range blocking, DNS re-resolution guard, redirects disabled, and an explicit hostile-URL test matrix in both M18 and M27. |
| R6 | **Frontend work consumes disproportionate time.** UI is open-ended in a way backend milestones are not. | High | Medium | Two milestones with explicitly enumerated surfaces; a small deliberate design system rather than a component library project; backend value does not depend on UI completeness. |
| R7 | **AWS cost grows.** V2 adds sandbox-service, the portal, CloudFront, WAF, and autoscaling on top of an estate already billing continuously. | High | Medium | Local-first development (D113); cost estimated and approved before M29's apply; low autoscaling floors; a teardown runbook written as a deliverable, not an afterthought. |
| R8 | **The API contract is frozen wrong**, and M21 locks in a shape that later needs a breaking change. | Medium | Medium | M19 deliberately precedes M21 so conventions are reviewed as a complete set; date-based versioning (D108) makes a later correction survivable rather than catastrophic. |
| R9 | **Four SDKs become unmaintainable.** | Medium | Medium | Shared behavioural contract, shared test vectors, generated types, and one equivalence suite covering all four; Java/Go deferred until the design is validated. |
| R10 | **Documentation drifts from reality**, which is fatal for a developer platform. | Medium | High | Reference generated from the spec; every sample executed in CI (D115); reference tables generated from the services' own seed data; contract tests validate live responses against the spec. |
| R11 | **Performance regresses on V1's hot path** from the accumulated per-request work V2 adds. | Medium | Medium | M28 re-runs V1's exact M14 simulations as the regression baseline; the key-verification cache and skipped Feign hop are expected to offset much of the added cost, but that expectation is measured, not assumed. |
| R12 | **A V1 known issue is quietly inherited rather than closed.** | Medium | Low | §2.11 tabulates every one with the milestone that closes it; each becomes a completion criterion rather than a note. |

---

## 13. Open Questions

Questions this plan deliberately does not answer, each with a recommendation and the
milestone by which it must be decided.

| # | Question | Recommendation | Decide by |
|---|---|---|---|
| Q1 | Should the internal signed-header mechanism (D100) eventually become an internal-JWT exchange? | Not in V2. Revisit if a compliance requirement or a service that must independently verify a merchant's full authorization state appears. The upgrade path is documented and the header interface would not change for consumers. | M27 review |
| Q2 | Do merchants need **teams** — multiple users on one merchant account with roles? | Defer. One user per merchant keeps V2's auth model simple, and the brief describes individual developers. The schema should not *preclude* it: `merchants.owner_user_id` becomes a membership table if needed. | M23 kickoff |
| Q3 | Should there be a **hosted checkout page** or drop-in payment UI? | No — it is a listed non-goal and a large surface (PCI adjacency, cross-origin embedding, browser SDK). V3 candidate. | Fixed for V2 |
| Q4 | Real-time dashboard updates — WebSocket/SSE or polling? | Polling for V2. Streaming adds an infrastructure dependency for a small UX gain at this scale. | M24 kickoff |
| Q5 | ~~Real email delivery (SES) — still simulated, as V1's D45 left it?~~ | **Resolved at M15 kickoff, against this plan's own recommendation**: stays simulated (D45 unchanged). notification-service gained an `EmailSender` seam (`SimulatedEmailSender` the only implementation) specifically so a real provider can be added later with zero business-logic changes — the abstraction is built now, the SES integration itself is deliberately deferred. | **Resolved (M15)** |
| Q6 | Custom domain and public URL for the deployed platform? | Needed for a credible demo (`api.paymentflow.dev` reads very differently from an ALB hostname), and Route 53 + ACM are already in V1's stack. Costs a domain registration. | M29 kickoff |
| Q7 | Should the platform expose a **disputes/chargebacks** lifecycle? | `pm_card_disputed` implies one. Recommend a minimal read-only dispute object in M19 rather than a full lifecycle — enough to make the test card meaningful without inventing a second FSM. | M19 kickoff |
| Q8 | Data residency / multi-region? | Out of scope. Single region, as V1. | Fixed for V2 |
| Q9 | Should V1's `merchants.webhook_url` be dropped once M18 migrates it? | Keep the column, stop reading it, mark deprecated. Dropping a column is irreversible and buys nothing. Revisit in M30. | M18 |
| Q10 | Does the portal need its own backend-for-frontend service? | No. Next.js server components and route handlers cover the aggregation needs, and a BFF would be a ninth service to deploy for no capability gain. | M23 kickoff |

---

## 14. Known Issues (V2)

Populated as V2 progresses. V1's known issues remain recorded in `PROJECT_CONTEXT.md` §11;
those that V2 closes are tabulated in §2.11 above with their closing milestone.

- **Per-key rate limiting is not implemented yet** — API-key-authenticated `/v1/**` traffic falls through to the gateway's existing IP-based rate-limit bucket (`RateLimiterConfig`'s key resolver only recognizes `JwtAuthenticationToken`, not `MerchantContextAuthenticationToken`). Explicitly M20's job (§4's "usage metering + per-key rate limits"); left alone deliberately per Decision 4 (depth before breadth), not an oversight.
- **`mode` is not yet enforced anywhere except the key itself.** A `sk_test_...` key resolves a `MerchantContext` with `mode="test"`, but no payment/transaction/audit/notification/analytics table has a `mode` column yet — M16 adds that. Today, a payment created via a test-mode key lands in the same `live`-only data plane as one created via a live-mode key or the unmodified JWT path. Not a security gap (only M15's own new surface exists), but the mode-isolation guarantee itself does not exist until M16 ships.
- ~~**No scope beyond `payments:read`/`payments:write` is enforced anywhere**~~ — **superseded, and recorded rather than deleted because the expectation it set is the one that was met.** When written at M15 the scope vocabulary in §4.9 had no real routes to attach to, and the entry said each future milestone adding a `/v1` route was expected to extend `ApiKeyAuthenticationWebFilter`'s `requiredScopeFor` mapping. That is exactly what happened: M18 added `webhooks:manage`, and M19.7 added `balance:read`, `events:read` and `analytics:read`. Five scopes are now enforced at the gateway and covered by the live E2E. `logs:read` remains unattached and is M20's; `refunds:write` deliberately has no route, because §4.9 describes it as *issuing* a refund, which is `payments:write` on the payment itself (M19.7).
- **Scope enforcement lives at the gateway only**, not defensively re-checked in payment-service — a deliberate, narrower-than-D23 choice for this milestone (D23 has downstream services independently enforce RBAC for the JWT path; API-key scope enforcement does not yet have that second layer). Revisit if a future milestone finds a reason payment-service itself needs to distrust the gateway's scope decision.
- **The internal-context HMAC secret is `.env`-only** (`PAYMENTFLOW_INTERNAL_CONTEXT_SECRET`), with a hardcoded, clearly-insecure local-dev default (`dev-only-insecure-shared-secret-change-me`) baked into every service's `application.yaml` and `docker-compose.yml`. Secrets Manager wiring is explicitly out of scope per D113 (local-first V2, one AWS milestone at the end, M29) — this is a real, load-bearing gap for that milestone to close, not an accident.
- **Rotate-with-grace has no explicit "list keys near grace expiry" surface** — a developer can see `graceExpiresAt` on a key via `GET /api/v1/merchants/me/api-keys`, but there's no proactive notification (email/webhook) when a grace window is about to lapse. No milestone currently owns this; flagged here as a real gap in the developer experience, not assigned anywhere yet.
- **`api_key_issued_total`/`api_key_revoked_total`/`api_key_rotated_total`/`email_logged_total` (generalized) are the only new M15 metrics** — no Grafana panel or alert rule was added for any of them (M13's dashboards predate M15). A future observability pass should decide whether these belong on an existing dashboard or a new "developer platform" one.
- **`modules/ecs-service`'s explicit `launch_type` silently defeats the cluster's `FARGATE_SPOT` default — the entire V1 deployment billed at on-demand rates.** Found during the post-M16 teardown (2026-07-23), not during the deployment itself. `modules/ecs-cluster` sets `default_capacity_provider_strategy { capacity_provider = "FARGATE_SPOT" }` and the live cluster genuinely carried that strategy — but `modules/ecs-service` creates every service with an explicit `launch_type = "FARGATE"`, and **an explicit launch type bypasses the cluster's default capacity provider entirely**. Confirmed against the live estate before teardown: every service reported `launchType: FARGATE` with `capacityProviderName: null`, and so did the running tasks. The cluster-level setting was inert for the full deployment window (2026-07-19 → 2026-07-23); all nine tasks ran on-demand, roughly $90/month of a ~$165/month estate, where Spot would have cut that line item by ~60–70%. **The trap is that the cluster configuration reads as correct** — `describe-clusters` returns `FARGATE_SPOT`, and only `describe-tasks` reveals what capacity actually served the workload. Owned by M29 as a pre-apply task (§5), deliberately not fixed now: V2 is local-first (D113), so there is no live ECS service to fix against until M29 re-applies. The fix is to drop `launch_type` from `modules/ecs-service` and either inherit the cluster default or set an explicit per-service `capacity_provider_strategy`.
- **`modules/ecr` has no `force_delete`, so `terraform destroy` cannot complete while images exist.** V1's teardown (2026-07-23) destroyed 106 of 114 resources and failed on all 8 ECR repositories with `RepositoryNotEmptyException` — each held 3 images (3.30 GB total). Everything billable was destroyed successfully because ECR sits in its own corner of the dependency graph, so the practical impact was an incomplete teardown rather than continued cost (~$0.33/month retained). The repositories were kept deliberately after the fact, since M29 will need them again. M29's teardown runbook must either set `force_delete = true` or purge images as a documented first step.
- **`whsec_` signing secrets are encrypted, not hashed — §4.9's blanket "every secret is stored only as SHA-256" is now inaccurate as written.** D137 records why (a webhook secret is *used* as an HMAC key, so it cannot be one-way hashed), and `WebhookSecretCipher`/`WebhookEndpoint` both state it, but §4.9's prose still asserts the general rule without the exception. Left as-is deliberately rather than editing §4.9: that section is the V2 *plan*, and the plan genuinely said this; the decision log is where a plan is corrected by implementation, which is exactly what D137 does. Flagged here so nobody reads §4.9 in isolation and reintroduces hashing.
- **The webhook secret-encryption key is `.env`-only** (`PAYMENTFLOW_WEBHOOK_SECRET_ENCRYPTION_KEY`), with a clearly-insecure local default baked into `application.yaml` and `docker-compose.yml` — the same shape, and the same M29-owned gap, as the internal-context HMAC secret. Additionally: **there is no key-rotation path.** Rotating it would make every stored signing secret undecryptable, silently breaking every merchant's webhook verification at once. A real re-encryption procedure (decrypt-with-old, encrypt-with-new, versioned key id on the row) is needed before this key is ever rotated in a deployed environment. No milestone owns it; M29 is the natural home.
- **`payment.events.retry`/`.dlq` and V1's `WebhookDeliveryService`/`WebhookRetryListener` are now dormant.** M18.6 stopped creating V1-shaped delivery rows, so nothing new ever lands on those topics; the listener and service remain only to drain rows that predate the cutover. They are kept (with their tests) rather than deleted because deleting them would strand any in-flight legacy row, and because their tests still document V1's behaviour. They should be removed once no pre-M18.6 `webhook_deliveries` row with a null `webhook_event_id` remains — a cleanup with no owner yet.
- **`webhook_delivery_attempts` has no retention policy.** It grows with (events × endpoints × attempts) and stores the full request and response body per attempt, which is by far the highest write volume M18 introduces. D116 established that a high-volume log table shipped without a pruner is a scheduled outage, and applied that reasoning to `api_request_log` in M20 — the same argument applies here and was not acted on. M20 is the natural place to fix it alongside its own pruner; recorded now rather than discovered under storage pressure.
- **The Redis endpoint-list cache specified in §4.8/§5-M18 task 4 was not built.** Fan-out reads endpoints and subscriptions from Postgres on every event (two indexed queries, one of them batched). It is correct and, at current volumes, fast; the cache is a performance optimisation whose absence is invisible until measured. Deliberately left for **M28** to measure before adding, rather than adding a cache-invalidation surface on a guess — but it *is* a documented deliverable that this milestone did not deliver, so it is recorded as a gap rather than silently dropped.
- **Not every published test card is driven through a real authorize call by an automated test** (M17, §5's completion criteria). `DecisionEngine` is data-driven with no per-token special-casing, so the risk this leaves open is narrow — a bad seed row (wrong `outcome`/`declineCode`/`captureBehaviour` in the `test_cards` migration) rather than a bad engine — but it is a real gap between the roadmap's literal completion criterion and what M17.8's test suite actually asserts. `TestCardCatalogueIntegrationTest` checks all 17 rows exist with correct metadata for 9 of them; only 4 tokens are additionally exercised through a real `decide()`/authorize call anywhere in the suite. No milestone currently owns closing this; a cheap fix would be a single parameterized integration test iterating every seeded token through `POST /internal/v1/sandbox/authorize` and asserting the response matches the catalogue's own advertised outcome for that token.
- **Refunds and the hourly analytics series both start at M19 — there is no history behind either, and the two lists are therefore incomplete in a way no error signals.** A merchant listing `/v1/refunds` sees only refunds issued from M19 forward, because pre-M19 refunds were never stored as objects: only the running `refunded_amount_minor` accumulator on the payment ever existed, and a total cannot be decomposed into the refunds that produced it without fabricating ids and timestamps that never happened (M19.3). Identically, `/v1/analytics/payments`'s hourly series begins at M19 because which hour each past event fell in exists only in audit-service's trail, and reconstructing analytics from another service's schema would couple two things D4 keeps apart (M19.6). Both accumulators/totals remain complete and authoritative, so the *totals* are right while the *series* and the *list* are short — which is the honest representation, but it means a chart or a refund list spanning the M19 boundary silently understates the earlier side. Nothing currently marks where the data begins; a `series_starts_at` field on the analytics response, or a documented platform epoch, would make the gap visible to a client rather than merely true. No milestone owns this.
- **`failed_count` exists only on the hourly buckets, not on the running totals**, so a success rate can be computed from the series but not from the lifetime counters. The gap became visible only when something first *read* these numbers (M19.6): a success rate needs a denominator that includes failures, and the running-total table pre-dates anything that needed one. Left un-widened deliberately — a new column there could not be honestly backfilled either, for the same reason the series cannot be — but it means the two surfaces answer different questions and only one of them can answer "what is my success rate overall?".
- **`webhook_endpoints.metadata` is not indexed** (D144). Correct today, because the endpoint list exposes no containment filter and is hard-capped at 16 rows per merchant per mode, so a GIN index would be paid for on every write to serve a query that does not exist. Recorded because the *first* milestone to add a filter on that column must add the index with it — the absence is a considered deferral, not an oversight to be rediscovered under a sequential scan.
- **`idx_ledger_entries_account_id` is now redundant for lookups but is deliberately retained** (M19.8, Defect 3). `idx_ledger_entries_account_created (account_id, created_at desc, id desc)` supersedes it for every query the balance API issues, and M19.2 dropped `idx_payments_merchant_mode` in exactly this situation. It is kept here because it also backs the `account_id` foreign key, and dropping the only index on an FK column makes every delete on `accounts` scan `ledger_entries`. The cost is one redundant index's write maintenance; the alternative is a table scan on a path nothing exercises today but a future account lifecycle would. Flagged so the asymmetry with M19.2 is not read as an inconsistency.
- **`payment-service` relaxes Tomcat's query-string parser for `[` and `]`** (D142). Scoped to two characters and one service, but it *is* a widening of what the HTTP layer accepts, made to serve a documented filter syntax. Recorded as an accepted risk rather than a neutral configuration line: any future audit of input handling should know the parser is non-default here and why, and M21's error-contract work should confirm the relaxed characters still route malformed input to the JSON error handler rather than Tomcat's HTML page — which is the failure D142 exists to have fixed.

---

## 15. Future Extensibility (beyond V2)

Designed-for but deliberately unbuilt. Each notes the seam that makes it approachable.

| Capability | The seam that makes it feasible |
|---|---|
| **Real PSP integration** | sandbox-service is already the acquirer boundary (D103); a real integration replaces one internal call, and live mode already means "not developer-controlled" |
| **Hosted checkout / drop-in UI** | Publishable (`pk_`) keys already exist and are already read-only and browser-safe |
| **Marketplace / sub-merchants** | Merchant is already the tenancy unit; a parent-merchant relationship is additive, and mode isolation generalises to it |
| **Disputes and chargebacks** | The FSM is explicit and guarded; a dispute lifecycle is a sibling aggregate, and `pm_card_disputed` already anticipates it |
| **Payouts and settlement** | The double-entry ledger already models obligations; settlement is a new transaction type, not a new accounting model |
| **Fraud / risk scoring** | The authorization decision already routes through an advisory service; risk is a second advisor on the same seam |
| **Mobile SDKs** | The behavioural contract (§7.1) is language-agnostic by construction |
| **Real-time dashboard streaming** | Kafka already carries every event; SSE at the gateway is additive |
| **Multi-region / data residency** | Schema-per-service and mode partitioning are both already region-agnostic |
| **gRPC for internal calls** | V1 already flagged this (its §12); the internal surface is now explicitly separated as `/internal/v1/*` (D98), so the protocol can change without touching public contracts |
| **Blue/green or canary deploys on ECS** | Already flagged in V1 §12; M29's autoscaling and target-group work is the prerequisite |
| **Observability stack deployed to AWS** | V1's D84 left this ready-to-do; the `kafka-broker` Fargate+EFS module pattern generalises directly |

---

## 16. Maintenance Rules

**This document is the source of truth for Version 2.** From this point onward:

1. **Every completed milestone gets an entry in §17**, containing: date, objectives,
   summary, files created, files modified, endpoints added, DB changes, Kafka topics, Redis
   features, infra/Terraform/Docker changes, testing performed, verification steps (what was
   *actually run*, not what should work), design decisions, problems, solutions, remaining
   work, and next milestone.
2. **Every architectural decision is appended to §11** with its alternatives and rationale.
   A decision that supersedes a V1 decision says so explicitly and explains why the earlier
   reasoning no longer applies — as D99 does for D29.
3. **Every new service is documented in §2-equivalent detail** — responsibility, endpoints,
   schema, events, and what it deliberately does *not* do.
4. **Every schema change is recorded** with its migration file and the reason for it.
5. **Every API addition is recorded**, including which tier (`/v1`, `/api/v1`, `/internal/v1`)
   and whether it is a public promise.
6. **Every trade-off is recorded**, including ones that look obvious at the time.
7. **Every known issue goes in §14** — including accepted risks, with the acceptance
   rationale. A risk that is accepted silently is indistinguishable from one that was missed.
8. **Performance results go in §18.**
9. **Never lose project context.** If code and this document disagree, fix whichever is
   wrong; never leave it stale.
10. **`PROJECT_CONTEXT.md` is not modified** except where backwards compatibility genuinely
    requires it, and any such change is noted here with its reason.

---

## 17. Milestone Change Log (V2)

*(One entry appended per completed milestone, per §16 rule 1.)*

### M15 — API Key Authentication & Machine-to-Machine Access ✅ (2026-07-21)

**Objectives.** Make API keys the primary authentication mechanism for the public API:
rebuild the key model (multi-key, scoped, mode-aware), teach the gateway to
authenticate a key and assert a verifiable merchant context downstream, establish the
`/v1/*` public surface alongside `/api/v1/*`, and give self-serve signup a real
completion step (email verification, password reset). Implemented exactly the plan
approved before coding began, with one flagged deviation (D118) confirmed with the
user first.

**Files created (highlights — ~60 new files across 7 modules)**
- `common-lib`: `security/InternalContextHeaders.java`, `InternalContextSigner.java`,
  `MerchantContext.java`, `MerchantContextHolder.java`, `MerchantContextAuthenticationToken.java`,
  `InternalContextFilter.java`, `InternalContextProperties.java`,
  `autoconfigure/InternalContextAutoConfiguration.java`.
- `merchant-service`: `domain/ApiKeyType.java`, `KeyMode.java`, `OutboxEvent.java`;
  `service/ApiKeySecretGenerator.java`; `web/ApiKeyController.java`,
  `ApiKeyInternalController.java`; `event/MerchantEventPublisher.java`,
  `MerchantEventPayload.java`; `outbox/OutboxRelay.java`; new DTOs/mapper; V3/V4 migrations.
- `gateway-service`: the whole `security/apikey` package (`InternalHeaderStrippingWebFilter`,
  `ApiKeyFormat`, `ApiKeyCacheService`, `ApiKeyVerificationClient`,
  `ResilientApiKeyVerifier`, `ApiKeyAuthenticationWebFilter`, `ApiKeyVerifyResult`,
  `InvalidApiKeyException`); `config/MerchantServiceProperties.java`,
  `ApiKeyCacheProperties.java`, `InternalContextBeanConfig.java`.
- `identity-service`: `domain/EmailVerification.java`, `PasswordReset.java`,
  `OutboxEvent.java`; `service/EmailVerificationService.java`, `PasswordResetService.java`;
  `event/IdentityEventPublisher.java`, `IdentityEventPayload.java`; `outbox/OutboxRelay.java`;
  4 new DTOs; V2 migration.
- `notification-service`: `email/EmailSender.java`, `EmailMessage.java`,
  `SimulatedEmailSender.java`; `listener/IdentityEventListener.java`;
  `event/IdentityNotificationEventPayload.java`; V2 migration.
- `audit-service`: `listener/MerchantEventListener.java`.
- Tests: `InternalContextSignerTest`, `InternalContextFilterTest` (common-lib);
  `ApiKeyServiceTest` rewrite, `MerchantServiceTest` rewrite (merchant-service);
  `ApiKeyFormatTest`, `ApiKeyAuthenticationIntegrationTest` (gateway-service, 6 cases).

**Files modified (highlights)**
- `merchant-service`: `ApiKey.java` (full rewrite), `ApiKeyService.java` (full rewrite),
  `ApiKeyRepository.java`, `MerchantController.java`/`MerchantService.java` (4-key
  onboarding), `MerchantOnboardResponse.java`, `SecurityConfig.java`
  (`/internal/v1/**` permitted).
- `gateway-service`: `SecurityConfig.java` (split into two `SecurityWebFilterChain`
  beans — D119; also fixed `trusted-proxies`), `application.yaml` (`/v1/payments`
  route + `RewritePath`, resilience config, internal-context secret).
- `identity-service`: `User.java` (`emailVerifiedAt`), `AuthService.java` (register
  triggers verification), `AuthController.java` (4 new endpoints), `UserResponse.java`/
  `UserMapper.java` (`emailVerified` field).
- `notification-service`: `NotificationService.java` (refactored onto the `EmailSender`
  seam), `EmailLogEntry.java` (`merchant_id` now nullable).
- `payment-service`: `MerchantResolver.java` (checks `MerchantContextHolder` before
  the Feign call — task 10).
- Every servlet service's `application.yaml` + `docker-compose.yml`: added
  `paymentflow.internal-context.secret`/`max-clock-skew-seconds` (common-lib's filter
  is SERVLET-conditional and activates everywhere, even where nothing sends it a
  header yet) and, where new, `SPRING_KAFKA_BOOTSTRAP_SERVERS`.
- `.env.example`: documented `INTERNAL_CONTEXT_SECRET`.

**Endpoints added**
- `/v1/payments/**` — gateway route (key-authenticated, `RewritePath` onto
  payment-service's existing `/api/v1/payments/**`; no controller duplication).
- `POST/GET /api/v1/merchants/me/api-keys`, `POST .../{id}/rotate`,
  `DELETE .../{id}` — merchant-service, JWT-authenticated.
- `POST /internal/v1/api-keys/verify` — merchant-service, service-to-service only.
- `POST /api/v1/auth/verify-email`, `/resend-verification`,
  `/password-reset/request`, `/password-reset/confirm` — identity-service, public.
- Removed: `POST /api/v1/merchants/me/api-key/rotate` (V1, superseded by the
  multi-key endpoints above — D99).

**Database changes**
- `merchant`: `V3__api_keys_v2.sql` (rebuilds `api_keys`: `mode`, `key_type`, `name`,
  `scopes text[]`, `last_used_at`, `expires_at`, `grace_expires_at`; drops the
  single-active-key partial unique index; backfills existing rows to
  `LIVE`/`SECRET`/`'{*}'`); `V4__outbox_events.sql`.
- `identity`: `V2__email_verification_password_reset.sql` (`email_verifications`,
  `password_resets`, `users.email_verified_at`, `outbox_events`).
- `notification`: `V2__generalize_email_log.sql` (`email_log.merchant_id` → nullable).

**Kafka topics added:** `merchant.events` (producer: merchant-service; consumer:
audit-service), `identity.events` (producer: identity-service; consumer:
notification-service). Both via the transactional-outbox pattern (D3), mirrored from
payment-service's M5 implementation into two new producers.

**Redis features added:** `apikey:v1:<sha256>` (gateway's verify cache, positive +
negative, evicted directly by merchant-service on revoke — D122);
`apikey:lastused:<keyId>` (merchant-service's throttle marker for `last_used_at`
writes — D121).

**Testing performed**
- Unit: `ApiKeyFormat` (credential classification, sha256), `InternalContextSigner`
  (roundtrip, tampered field, wrong secret, missing signature, null-tolerant fields),
  `InternalContextFilter` (no-header pass-through, valid signature authenticates,
  tampered/stale signature 401s, incomplete header set 401s), `ApiKeyService`
  (issue/default-scopes/issueDefaultSet/verify/revoke-evicts-cache/rotate-with-grace),
  `MerchantService` (4-key onboarding), `MerchantResolver` (new case: Feign client
  never invoked when a `MerchantContext` is present — `verifyNoInteractions`).
- Integration (gateway, `ApiKeyAuthenticationIntegrationTest`, 6 cases against real
  Redis + Reactor Netty stubs for merchant-service/payment-service): valid secret key
  authenticates and proxies with a signed internal context; unknown key → 401;
  publishable key on a write route → 403; publishable key can read; a
  client-forged `X-PF-Internal-Merchant-Id` header is stripped and never reaches
  payment-service; `/v1/**` with no credential at all → 401 (fail-closed, not
  accidentally `permitAll`).
- Regression: full monorepo test suite re-run — **220 tests, 0 failures, across all
  10 modules** (`common-dto`, `common-lib`, `gateway-service`, `identity-service`,
  `merchant-service`, `notification-service`, `payment-service`, `transaction-service`,
  `audit-service`, `analytics-service`) — the existing JWT-path
  `GatewayIntegrationTest` (9 cases) passed unchanged, confirming the milestone's own
  highest-risk regression surface held.
- Manual verification deferred to before M16 kickoff (not yet run against the full
  local docker-compose stack) — see Next Milestone below.

**Design decisions:** D98–D122 (§11) — D118–D122 newly logged this milestone
(internal-context payload extension, the two-chain `SecurityConfig` split, grace-window
rotation, throttled `last_used_at`, direct cross-service Redis eviction).

**Problems → Solutions (real bugs found during implementation, not hypothetical)**
1. *`WebClient.Builder` had no bean in gateway-service* — Spring Cloud Gateway's
   reactive starter does not pull in Boot's `WebClientAutoConfiguration`, contrary to
   the plan's assumption. `ApiKeyVerificationClient` now builds its own `WebClient` via
   `WebClient.builder()` directly instead of injecting a builder bean.
2. *`InternalContextSigner`/`InternalContextProperties` had no bean in gateway-service* —
   common-lib's `InternalContextAutoConfiguration` is correctly `SERVLET`-conditional
   (D11's split) and so never fires on the reactive gateway, but the gateway still
   needs to *sign* (not just verify). Added `InternalContextBeanConfig` to
   gateway-service registering both explicitly.
3. *`RewritePath=/v1/payments/(?<segment>.*), /api/v1/payments/${segment}` silently
   never rewrote the bare `POST/GET /v1/payments`* (create/list — the most common real
   request) — the regex required a literal trailing `/` the bare path doesn't have, so
   the path passed through unrewritten and 404'd against payment-service. Fixed to
   `/v1/payments(?<segment>/?.*)`, and separately discovered mid-fix that `${segment}`
   in a YAML filter value is intercepted by Spring's own property-placeholder
   resolution before Gateway's regex engine ever sees it — the documented Spring Cloud
   Gateway workaround (`$\{segment}`, backslash-escaped) was required. A genuine,
   two-layered framework gotcha, not a typo.
4. *Reactor Netty's test-stub route matcher doesn't treat `/**` as matching zero
   trailing segments* the way Spring's PathPattern does (confirmed the real gateway
   route/rewrite was correct once problem 3 was fixed — this was purely a test-stub
   issue). `ApiKeyAuthenticationIntegrationTest`'s payment-service stub now registers
   both the bare path and `/**` explicitly for GET and POST.
5. *Mockito `UnnecessaryStubbingException`* in the rewritten `ApiKeyServiceTest` — a
   blanket `apiKeyRepository.save(...)` stub in `@BeforeEach` wasn't exercised by the
   verify-on-unknown/revoked-key tests. Marked `lenient()`.

**Known limitations:** see §14 — per-key rate limiting (M20), mode enforcement (M16),
scope vocabulary beyond `payments:read`/`write`, `.env`-only internal-context secret
(Secrets Manager is M29), no proactive grace-window-expiry notification, no new
Grafana panels for M15's metrics.

**Next milestone:** M16 — Test / Live Mode Isolation. Before starting: a manual
docker-compose E2E pass (register → onboard → 4 keys → `curl /v1/payments` with only
an `sk_test_` key → revoke → immediate next call fails) is still owed per the
project's own "manual verification is also a regression check on everything upstream"
discipline (identity/merchant/gateway/payment all changed this milestone) — not yet
run, flagged here rather than skipped silently.

**Post-acceptance validation & corrections (2026-07-22).** The owed docker-compose E2E
was run and **the M15 API-key path did not actually work end-to-end** — the happy-path
completion criterion ("a payment can be created with only a secret key") held only in the
gateway integration test, which **stubbed payment-service** and so never exercised the
real Spring Security chain. Three defects, all now fixed and re-verified (full E2E green:
create-with-only-`sk_test_` → 201; `pk_test_` read 200 / write 403; forged
`X-PF-Internal-*` header stripped; revoke → immediate 401; no-cred → 401):

1. **`InternalContextAutoConfiguration` was missing from `common-lib`'s
   `META-INF/spring/…AutoConfiguration.imports`** — so `InternalContextFilter` was never
   registered in any servlet service. Added the import.
2. **The gateway forwarded the client's `Authorization: Bearer sk_…` downstream**, where
   payment-service's OAuth2 resource server tried to decode the API key as a JWT and
   rejected it 401 ("Malformed token") before the internal context was consulted.
   `ApiKeyAuthenticationWebFilter` now strips `Authorization` on the API-key path — the
   signed internal context replaces, not supplements, the key (**D123**).
3. **`InternalContextFilter` authenticated *ahead of* Spring Security's chain**, so
   `SecurityContextHolderFilter` replaced the `Authentication` it set and the request
   reached `AuthorizationFilter` unauthenticated. The filter is now provided as a bean
   (its standalone servlet registration disabled) and added **inside** each servlet
   service's chain via `http.addFilterBefore(internalContextFilter,
   AuthorizationFilter.class)` — wired in payment-service (the only M15 internal-context
   consumer); other servlet services wire it when they gain `/v1` routes (**D124**).

**Regression tests added** so this cannot silently recur: `payment-service`
`InternalContextAuthenticationIntegrationTest` (a signed context authenticates through the
*real* chain and creates a payment with no JWT; tampered signature → 401; no credential →
401) and an assertion in the gateway `ApiKeyAuthenticationIntegrationTest` that the
downstream never receives the client's `Authorization` header. Root-cause lesson recorded:
**an integration test that stubs the very service whose behaviour is under test proves
nothing about that service** — the stub hid three real defects at once.

**Build determinism.** During E2E validation, repeated `docker compose build` failures
traced to the shared `Dockerfile` builder having **no BuildKit cache mount for the Gradle
home** — every image re-downloaded the entire dependency graph cold, 8× redundantly, with
no tolerance for a transient registry hiccup. Added
`RUN --mount=type=cache,target=/root/.gradle …`; the per-service dependency+build step
dropped from ~242 s cold to ~21 s warm with zero re-downloads across services.

**Repository stabilization phase (2026-07-22, between M15 and M16).** The docker-build
cache mount above (Fix #1) was the first of an 8-item plan approved in full and
implemented incrementally, each fix in its own commit, verified before the next began.

1. **Docker BuildKit Gradle cache** — see "Build determinism" above.
2. **Deterministic resilience timing** (`MerchantResilienceIntegrationTest`,
   `MerchantResolverTest`) — replaced absolute wall-clock latency ceilings with a
   `CountDownLatch` gate the test drains explicitly, so a loaded CI box can't push a
   legitimate fail-fast past a too-tight fixed timeout.
3. **Testcontainers image pre-pull in CI** (`ci.yml`) — `postgres:17-alpine`,
   `redis:8-alpine`, `confluentinc/cp-kafka:7.7.1` pulled as a discrete, retriable step
   before the Gradle run, so a transient Docker Hub hiccup surfaces there instead of as a
   flaky mid-suite `ContainerFetchException`.
4. **Awaitility migration** — four Kafka-consumer integration tests
   (transaction/audit/notification/analytics-service) each carried an identical,
   hand-copied `awaitTrue(BooleanSupplier, Duration)` poll loop. Replaced all four with
   `org.awaitility:awaitility` and deleted the duplicated helpers.
5. **Deterministic redelivery-noop assertions** — the same four services' "redelivering
   the same event is a no-op" tests slept a blind fixed duration and hoped the duplicate
   had been evaluated by then. Since every producer keys its message by `paymentId`, a
   follow-up real domain event sharing that key is guaranteed (same Kafka partition, same
   consumer, in-order) to be processed *after* the duplicate — awaiting the follow-up's
   own effect is therefore deterministic proof the duplicate was already handled.
6. **Deterministic bulkhead drain** — folded into #2's `CountDownLatch` gate, which also
   replaced a blind `Thread.sleep` used to drain an in-flight call off the bulkhead's sole
   thread between tests.
7. **Awaitility for circuit-breaker state transitions** — two tests
   (`MerchantResolverTest`, `MerchantResilienceIntegrationTest`) slept a fixed margin past
   `waitDurationInOpenState`, betting Resilience4j's own internal scheduler had already
   flipped `OPEN→HALF_OPEN`. Replaced with `await().until(() -> circuitBreaker.getState()
   == HALF_OPEN)` — polling the real state machine instead of guessing elapsed time.
8. **Disabled Foojay JDK auto-download inside Docker builds** — the builder stage's own
   base image already is the exact toolchain JDK every module requires, so the
   project-wide Foojay resolver is never actually needed there; scoped
   `-Porg.gradle.java.installations.auto-download=false` to just the Dockerfile's
   `./gradlew … bootJar` invocation (not `gradle.properties`), so local developers without
   JDK 25 installed and CI's non-Docker build-and-test job (which pre-installs JDK 25 via
   `actions/setup-java` and never touches this code path) are both unaffected.

**Root-cause pattern across the whole phase:** every one of the 8 fixes replaced either a
blind fixed-duration wait/sleep with a poll on the actual condition being waited for, or a
reachable-but-unnecessary external network dependency with a structural guarantee it can't
be reached — the same discipline, applied repeatedly, rather than eight unrelated patches.

**Residual, out-of-scope observation:** a `ContainerFetchException`
("Can't get Docker image: postgres:17-alpine") was reproduced twice locally during Fix #4
verification when four Testcontainers-backed suites built concurrently — confirmed
transient (immediate re-run green) and already mitigated in CI by Fix #3's pre-pull step,
which local `./gradlew` runs don't get. Not part of the approved 8-item plan; noted for a
future fix if it recurs.

Full verification after every fix: affected test(s) re-run individually (several fixes
re-run multiple times to confirm no residual flakiness), the owning module's full suite,
and a full `./gradlew clean build` — all green throughout. Fix #8 additionally rebuilt all
8 Docker images from scratch (`--no-cache`) and confirmed zero Foojay/toolchain-download
activity in any build log.

### M16 — Test/Live Mode Isolation ✅ (complete, 2026-07-22)

**Objectives.** Make `mode` (`test`/`live`) a *structural* isolation boundary across the
data plane (§4.4), not a filter queries remember to apply. M15 already resolves mode from
the API key, HMAC-signs it into `X-PF-Internal-Mode`, and verifies it into
`MerchantContext.mode` — but that mode is then *discarded* (`MerchantResolver` returns a
`MerchantSummary` with no mode), so no payment, idempotency record, or event is partitioned
by it, and the JWT/dashboard path has no mode at all. M16 threads mode through persistence,
idempotency, the event envelope, and every consumer; a `sk_test_` key must never read,
mutate, or observe a live object (cross-mode read → 404, never 403).

**Decomposition (approved 2026-07-22).** Seven independently-testable, independently-
committable sub-milestones: **M16.1** `EventEnvelope.mode` (common-dto); **M16.2**
payment-service data plane (schema/entity/`RequestModeResolver`/idempotency/reads +
`X-PF-Mode` header for the JWT path + gateway strip); **M16.3** transaction-service (per-mode
clearing account); **M16.4** analytics-service; **M16.5** audit-service; **M16.6**
notification-service; **M16.7** consolidated docs + full manual E2E. M16.3–M16.6 are mutually
independent. Approved recommendations: M16.x numbering; `schemaVersion` deferred to M21;
`X-PF-Mode` header for the JWT/dashboard mode; `PaymentResponse.mode` as a `"test"`/`"live"`
string; all existing rows backfill to `"live"`.

#### M16.1 — `EventEnvelope` carries `mode` ✅ (2026-07-22)

**Summary.** `common-dto`'s `EventEnvelope` gained a nullable `String mode` field
(§5/§11-D125). The field is `@JsonInclude(NON_NULL)`, so a producer that hasn't set it (every
producer, as of M16.1) omits it entirely and the wire form is **byte-identical to the pre-M16
envelope** — nothing else in the platform changes behaviour yet. A `null` mode read back is
interpreted as `"live"` by consumers (M16.3–6), matching the row-backfill semantics.

**Files modified.** `common-dto/.../event/EventEnvelope.java` — added `mode` as the 6th record
component (before the generic `payload`); added a **backward-compatible mode-less constructor**
and retained the 4-arg `of(...)` factory (both leave mode `null`) so every existing caller — the
4-arg `of(...)` in payment/merchant/identity publishers and the 6-arg direct constructor in the
transaction/audit/notification/analytics integration tests — compiles untouched; added a new
5-arg `of(eventType, aggregateId, correlationId, mode, payload)` factory for M16.2+ producers.
`common-dto/.../event/EventEnvelopeTest.java` — added five tests: mode-carrying factory reaches
the wire; mode-less factory omits `mode` from JSON; mode-less constructor leaves mode null;
legacy JSON without `mode` deserializes to `null` (the backward-compat read path); mode
round-trips when present.

**DB / Kafka / Redis / API.** None. Envelope-only, additive; no topic, schema, or contract
change. (`payment.events` and consumers are unaffected until M16.2+.)

**Verification.** `:common-dto:test` green (13 tests, incl. the 5 new). Full `./gradlew clean
build` green across all modules — every service compiles against the new envelope, and the
transaction/audit/notification/analytics integration tests exercise it through the real Jackson 3
(`tools.jackson`) runtime via their 6-arg constructor + serialize/deserialize round-trips, so the
runtime path is validated, not just common-dto's Jackson-2 test. One transient F6
`ContainerFetchException` (postgres:17-alpine) in notification-service during the full build —
confirmed unrelated by an immediate clean re-run (all 18 notification tests green).

**Decision.** D125 (nullable/`NON_NULL` additive design; `schemaVersion` deferred to M21).

#### M16.2 — Payment-service data plane is mode-partitioned ✅ (2026-07-22)

**Summary.** payment-service became the first mode-partitioned data plane. Every payment,
idempotency record, and payment event now carries a `mode` (`"test"`/`"live"`), resolved once
per request; reads and idempotency are scoped by mode; a credential operating in one mode can
never read or mutate a payment in the other (cross-mode → **404, not 403**, §4.4).

**Mode resolution (`RequestModeResolver`).** One rule, on the servlet request thread: (1)
API-key path → the gateway-signed `MerchantContext.mode` (key-bound, non-overridable — a
`sk_test_` key cannot assert live); (2) JWT/dashboard path → the `X-PF-Mode` header, validated
against the new `Mode` enum (unrecognised → 400); (3) neither → default `"test"` (dashboard
opens in test mode, §3.1). The canonical persisted/wire value is the lowercase string — confirmed
`ApiKeyVerifyResult` already lowercases it, so `MerchantContext.mode()` is `"test"`/`"live"`.
`MerchantResolver`/`MerchantSummary` were left untouched — mode is resolved independently of
merchant identity, so the resilience path (and its tests) is unchanged.

**Files created (3).** `domain/Mode.java` (enum, local per schema-per-service; validation/parsing
only — entities store the string), `mode/RequestModeResolver.java`, test
`mode/RequestModeResolverTest.java`.

**Files modified (production, 11).** `domain/Payment.java` (+`mode`, non-updatable;
`create(merchantId, mode, …)`); `domain/IdempotencyRecord.java` (+`mode`; `of(merchantId, mode, …)`);
`idempotency/IdempotencyService.java` (`guarded`/`record`/`findReplay`/lock all mode-keyed; Redis
lock key → `idempotency:lock:<merchantId>:<mode>:<key>`); `repository/PaymentRepository.java`
(`findByIdAndMerchantIdAndMode`, `findByMerchantIdAndMode` — replaced the mode-blind methods);
`repository/IdempotencyRecordRepository.java` (`findByMerchantIdAndModeAndIdempotencyKey`);
`service/PaymentService.java` (resolve mode in create/mutate/get/list; thread it into create,
idempotency, and mode-scoped reads; mutate loads via the mode-scoped finder);
`event/PaymentEventPublisher.java` (5-arg `EventEnvelope.of(…, payment.getMode(), payload)` —
`PaymentEventPayload` unchanged, mode rides the envelope); `dto/PaymentResponse.java` +
`mapper/PaymentMapper.java` (+`mode`); `gateway-service/application.yaml`
(`RemoveRequestHeader=X-PF-Mode` on the `/v1` API-key route only — defense-in-depth, dashboard
`/api/v1` route keeps it).

**DB.** `payment/V2__mode_isolation.sql` — additive, backfill `'live'`, then `NOT NULL`:
`payments.mode` (+check, +composite `idx_payments_merchant_mode` replacing `idx_payments_merchant_id`);
`idempotency_keys.mode` (+check), uniqueness `(merchant_id, idempotency_key)` →
`(merchant_id, mode, idempotency_key)`. **Redis:** idempotency lock key mode-namespaced.
**Kafka:** unchanged (mode rides the M16.1 envelope). **API:** additive only — `PaymentResponse.mode`
and the optional `X-PF-Mode` header. **Intended behavior change:** JWT/dashboard payments now
default to `mode=test` (correct per §3.1); pre-M16 rows backfilled to live.

**Tests modified/added.** New `RequestModeResolverTest` (precedence table incl. invalid→400).
`IdempotencyServiceTest` (mode-threaded signatures + mode-scoped replay lookup + record carries
mode). `PaymentServiceTest` (mocks `RequestModeResolver`; mode-scoped verifications).
`PaymentTest` (factory signature). `PaymentIntegrationTest` — added: default-test + **event
envelope carries `"mode":"test"`**; `X-PF-Mode` selects live; invalid mode → 400; **cross-mode
GET → 404** while same-mode → 200; list scoped to mode; **the required regression** —
`theSameIdempotencyKeyIsIndependentPerMerchantAndMode` (same `Idempotency-Key` coexists across
Merchant A/test, A/live, B/test as three distinct payments, and a fourth same-tuple call replays
the first). `InternalContextAuthenticationIntegrationTest` — API-key path persists the signed
context's mode (test and live).

**Verification.** payment-service unit tests green (RequestModeResolver 5, Idempotency 9, Payment
domain, PaymentService); integration tests green (PaymentIntegrationTest 18 incl. the regression,
InternalContext 4); full `:payment-service:test` green; full `./gradlew clean build` green — the
four consumer services still pass unchanged, tolerating the now-populated `EventEnvelope.mode`
(they don't act on it until M16.3–6). No F6 flake this run.

**Remaining M16 work.** M16.3 (transaction-service, per-mode clearing account) → M16.4 analytics →
M16.5 audit → M16.6 notification → M16.7 consolidated docs + manual E2E. Consumer services are
deliberately still mode-unaware.

#### M16.3 — Transaction-service (ledger) is mode-partitioned ✅ (2026-07-22)

**Summary.** The first consumer to become mode-aware: the double-entry ledger is now partitioned
by mode. Test and live money can never mix because they post to **separate accounts** — the
platform clearing account is one per `(currency, mode)`, and each merchant's pending/settled
accounts one per `(type, owner, currency, mode)`. Ledger transactions and entries are stamped with
mode too. This is the mechanism §12-R3 (mode-isolation hole) guards against, realised for the ledger.

**Mode source.** The consumer reads `envelope.mode()` (populated by payment-service since M16.2);
`LedgerService` normalises a `null` mode to `"live"` at a single point (`DEFAULT_MODE`), the same
null→live backfill contract M16.1's envelope defines. No `Mode` enum in transaction-service — the
consumer trusts the upstream-validated envelope string, and the DB `check (mode in ('test','live'))`
plus the existing whole-transaction retry loop are the guard. Account resolution
(`getOrCreateAccount`) and both ledger-row builders are keyed by mode, so a test event can only ever
touch test accounts — isolation is structural, not a filter.

**Files modified (production, 5).** `domain/Account.java` (+`mode`, non-updatable; `open(…, mode)`;
`getMode()`); `domain/LedgerTransaction.java` + `domain/LedgerEntry.java` (+`mode`; `of(…, mode, …)`);
`repository/AccountRepository.java` (`findByAccountTypeAndOwnerIdAndCurrency` →
`…AndMode`); `service/LedgerService.java` (derive mode once null→live; thread through all four
posting paths, `getOrCreateAccount`, and both row builders). **DB:** `transaction/V2__mode_isolation.sql`
— additive, backfill `'live'`, then `NOT NULL`: `accounts.mode` (+check) with **both partial unique
indexes recreated to include mode** (`uq_accounts_platform_clearing (currency, mode)`,
`uq_accounts_merchant (account_type, owner_id, currency, mode)`); `ledger_transactions.mode` and
`ledger_entries.mode` (+checks, denormalised for M19 query ergonomics). **Kafka/Redis/API:**
unchanged (pure consumer; mode rides the M16.1 envelope).

**Tests.** `AccountTest` (factory signature). `LedgerServiceTest` (mode-scoped finder stub; asserts a
no-mode envelope resolves accounts and entries to `"live"`). `TransactionIntegrationTest` — balance
helpers gained mode-aware overloads (existing tests default to live) + a mode-carrying publish helper
+ **the required balance-independence regression** `testAndLivePostingsAreBalanceIsolatedAndNeverAffect
TheOtherMode`: authorize in test (only test balances move; live still zero), then authorize in live at
a different amount (only live moves; test unchanged), and two distinct clearing accounts exist for the
currency — one per mode.

**Verification.** `:transaction-service` unit tests green (Account 5, LedgerService 11); integration
tests green (5, incl. the balance-independence regression + the existing net-to-zero / concurrency /
redelivery invariants, which now run in the live partition unchanged); full `./gradlew clean build`
green — nothing else regressed; no F6 flake this run.

**Remaining M16 work.** M16.4 analytics → M16.5 audit → M16.6 notification → M16.7 consolidated docs
+ manual E2E. analytics/audit/notification remain mode-unaware until their own sub-milestone.

#### M16.4 — Analytics-service (aggregates) is mode-partitioned ✅ (2026-07-22)

**Summary.** A direct parallel of M16.3 applied to the read-model aggregate: the
`merchant_payment_stats` row becomes **one per `(merchant_id, currency, mode)`**, so test and
live counts/totals are structurally separate and never mix. Same mode source (`envelope.mode()`,
`null → "live"` normalised once in `AnalyticsService` via `DEFAULT_MODE`); same guard (DB check +
the existing optimistic-lock whole-transaction retry loop); no `Mode` enum (the consumer trusts
the upstream-validated envelope string). The aggregate is looked up (or created) by mode, so a
test event can only ever touch the test aggregate.

**Files modified (production, 3).** `domain/MerchantPaymentStats.java` (+`mode`, non-updatable;
`open(merchantId, currency, mode)`; `getMode()`); `repository/MerchantPaymentStatsRepository.java`
(`findByMerchantIdAndCurrency` → `…AndMode`); `service/AnalyticsService.java` (derive mode
null→live; mode-scoped lookup + `open(…, mode)`). **DB:** `analytics/V2__mode_isolation.sql` —
additive, backfill `'live'`, then `NOT NULL`: `merchant_payment_stats.mode` (+check), uniqueness
`(merchant_id, currency)` → `(merchant_id, currency, mode)`. **Kafka/Redis/API/payload:** unchanged
(pure consumer; mode rides the M16.1 envelope).

**Tests.** `MerchantPaymentStatsTest` (factory signature). `AnalyticsServiceTest` (mode-scoped
finder stubs, `open(…, mode)`, and a null→live assertion on the saved aggregate).
`AnalyticsIntegrationTest` — `statsFor` gained a mode-aware overload (existing calls default to
live) + a mode-carrying publish helper + two regressions: `testAndLiveAggregatesAreSeparateRowsWith
IndependentCounts` (a full test-mode sequence and a live-mode CREATED for the same merchant+currency
produce two separate rows, each with only its own mode's counts) and **the required
`replayingTheSameEventInTestModeIsANoOpAndLeavesLiveUntouched`** (publish CREATED in test, replay
the identical eventId, deterministically confirm via a follow-up AUTHORIZED that the replay was
consumed, assert the test aggregate counted CREATED exactly once, and the live partition has no row
at all).

**Verification.** `:analytics-service` unit tests green (MerchantPaymentStats 5, AnalyticsService 9);
integration tests green (5, incl. both mode regressions + the existing concurrency/redelivery
invariants, now in the live partition unchanged); full `./gradlew clean build` green; no F6 flake.

**Remaining M16 work.** M16.5 audit → M16.6 notification → M16.7 consolidated docs + manual E2E.
audit/notification remain mode-unaware until their own sub-milestone.

#### M16.5 — Audit-service records the declared mode ✅ (2026-07-22)

**Summary.** audit-service now records each event's `mode` on its immutable `audit_log` row, feeding
the M19 Events API's mode filter. Unlike the ledger/analytics consumers, audit **records `mode`
verbatim as a nullable column and never coerces null→live** (D126) — because it is a faithful,
schema-agnostic recorder (D44) that consumes two streams through one method (`payment.events`, which
carry a mode, and `merchant.events`, which are mode-less), and coercing a mode-less event to `'live'`
in an immutable trail would be a factual lie. Audit partitions nothing, so it has no reason to apply
the consumer-side null→live interpretation.

**Files modified (production, 3).** `domain/AuditLogEntry.java` (+nullable `mode`, non-updatable;
`of(…, mode, …)`; `getMode()`); `service/AuditService.java` (read `mode` from the envelope JSON like
`correlationId` — the `NON_NULL`-omitted field is simply absent for mode-less events → null); **DB:**
`audit/V2__mode_isolation.sql` — add **nullable** `mode` (+check allowing null/test/live); **no
backfill, no NOT NULL** (deliberate, D126). Both listeners already funnel through `recordEvent`, so
payment and merchant streams are both handled with no listener change. **Kafka/Redis/API:** unchanged.

**Tests.** `AuditServiceTest` (mode-less envelope → null; new: an envelope declaring `"mode":"test"` →
`"test"`). `AuditIntegrationTest` — mode-carrying publish helper + `theDeclaredModeIsRecordedVerbatim
IncludingItsAbsence` (a test event → `"test"`, a live event → `"live"`, a mode-less event → null).

**Verification.** `:audit-service` unit tests green (AuditService 5); integration tests green (4, incl.
the mode-recording test + existing verbatim/redelivery/malformed invariants); full `./gradlew clean
build` green; no F6 flake. **Decision:** D126.

**Remaining M16 work.** M16.6 notification → M16.7 consolidated docs + full docker-compose E2E.
notification-service remains mode-unaware until M16.6.

#### M16.6 — Notification-service records the declared mode ✅ (2026-07-22)

**Summary.** notification-service now records each event's `mode` on its per-event record rows —
`email_log` and `webhook_deliveries`. Like audit (and unlike the ledger/analytics partitioners), it
**follows D126's recorder semantics — nullable, verbatim, no coercion, no backfill** — because it
consumes a **mode-less** stream too: `identity.events` (verification/password-reset emails have no
mode). Coercing an identity email to `'live'` would be a lie. `email_log` carries both payment
(mode-bearing) and identity (mode-less) emails, so its mode is `test`/`live` or `null`;
`webhook_deliveries` is payment-only so its mode is set in practice, but stays nullable for
consistency (and because M18 rebuilds the webhook subsystem, §4.5).

**Why notification ≠ transaction/analytics (the design question):** transaction/analytics consume
only `payment.events` (all mode-bearing) and **partition** their data by mode → NOT NULL + null→live
+ backfill. audit/notification consume **multiple streams incl. a mode-less one** and **record**
per-event → nullable + verbatim. `mode` is a structural partition key for the former, a recorded
attribute for the latter.

**Files modified (production, 6).** `email/EmailMessage.java` (+`mode`); `service/NotificationService.java`
(read `envelope.mode()`, pass to `EmailMessage` + `WebhookDelivery.pending`);
`listener/IdentityEventListener.java` (pass `envelope.mode()` — null for identity);
`email/SimulatedEmailSender.java` (thread `mode` into `EmailLogEntry.of`); `domain/EmailLogEntry.java`
+ `domain/WebhookDelivery.java` (+nullable `mode`; mode-carrying factory; `getMode()`). **DB:**
`notification/V3__mode_isolation.sql` — `email_log` + `webhook_deliveries` each add a **nullable**
`mode` (+check allowing null/test/live); **no backfill, no NOT NULL**. `mode` is set once at row
creation; the retry path (`WebhookRetryListener`/`WebhookDeliveryService`) re-attempts existing rows
and preserves the non-updatable `mode` untouched — no retry-path change. **Repositories/Kafka/Redis/
API:** unchanged.

**Tests.** `WebhookRetryListenerTest` (3×) + `WebhookDeliveryServiceTest` (1×): `pending(…, mode, …)`
signature. `NotificationServiceTest`: new `thePaymentEventsModeIsStampedOnBothTheEmailAndTheWebhook
Delivery` (mode-carrying envelope → `EmailMessage.mode()` and `WebhookDelivery.getMode()` are `"test"`).
`NotificationIntegrationTest`: mode-carrying publish helper + `thePaymentEventsModeIsRecordedOnBoth
TheEmailAndTheWebhookDelivery` (a test event → `"test"` on both `email_log` and `webhook_deliveries`;
a live event → `"live"`).

**Verification.** `:notification-service` unit tests green (NotificationService 6, WebhookDelivery 3,
WebhookRetryListener 5); integration tests green (6, incl. the mode-recording test + existing
delivery/retry/redelivery/malformed invariants); full `./gradlew clean build` green; no F6 flake.
**Decision:** D126 (extended to notification).

**Remaining M16 work.** M16.7 — consolidated docs + full docker-compose end-to-end validation and
milestone closure. All five data-plane/consumer sub-milestones (M16.1–M16.6) are now complete; every
service is mode-aware in the manner appropriate to its role.

#### M16.7 — Milestone closure: full docker-compose E2E validation ✅ (2026-07-22)

**Objective.** Prove test/live mode isolation holds end-to-end against the *running* platform (the
M15 discipline: a manual E2E is the regression gate that unit/integration tests can't replace), then
close M16.

**Setup.** Rebuilt all 8 images with the M16 code (`common-dto` changed, so every service) and brought
up the full stack (`docker-compose.infra.yml` + `docker-compose.yml`, 12 containers, all healthy).
Drove real HTTP through the gateway (`:8080`) and inspected every consumer's Postgres schema; note the
Postgres volume persisted **pre-M16 rows** from earlier sessions, which doubled as a live
backward-compatibility fixture.

**Verified (all green):**
- **API-key mode enforcement** — `sk_test_` payment persisted `mode=test`; `sk_live_` → `mode=live`.
- **Cross-mode read isolation** — `sk_live_` GET of a test payment → **404**; `sk_test_` GET of its own → 200.
- **Idempotency isolation** — the same `Idempotency-Key` under `sk_test_` vs `sk_live_` produced **two
  distinct payments**; a third `sk_test_` call replayed the first (idempotent within (merchant, mode)).
- **JWT mode selection** — `/api/v1/payments` with no `X-PF-Mode` defaulted to `test`; `X-PF-Mode: test`
  → test; `X-PF-Mode: live` → live.
- **`EventEnvelope.mode` propagation + payment-service isolation** — `payment.payments` stamped per row.
- **Ledger isolation (transaction)** — **two separate `PLATFORM_CLEARING` accounts** for USD (test=5000,
  live=5000) plus separate per-mode merchant settled accounts; pending nets to 0 in each mode.
- **Analytics isolation** — separate `merchant_payment_stats` rows per (currency, mode).
- **Audit recording semantics (D126)** — new events recorded `test`/`live`; **10,515 pre-M16 rows stayed
  `NULL`** (migration did no backfill/coercion).
- **Notification recording semantics (D126)** — `email_log` and `webhook_deliveries` stamped `test`/`live`
  for new events, `NULL` for legacy; webhook deliveries created (attempted against a dummy HTTPS URL).
- **Backward compatibility** — a **freshly published mode-less** `payment.events` message (no `mode`
  field, exactly a pre-M16 producer's output) was handled correctly by every consumer: partitioners
  (ledger, analytics) posted it to the **live** partition; recorders (audit, notification) recorded it
  with **NULL** mode.

**Closure verification.** Full `./gradlew clean build` green.

**Files.** `PROJECT_CONTEXT_2.md` only (this entry + §17 M16 rollup below; status line updated). No code
change in M16.7. The E2E scripts live in the session scratchpad (not committed — throwaway validation
harness).

**M16 — Test/Live Mode Isolation: COMPLETE (2026-07-22).** All seven sub-milestones (M16.1–M16.7)
implemented, verified, and committed. Mode is now a *structural* property (§4.4): API-key mode is
key-bound and non-forgeable; JWT/dashboard mode is a caller-owned `X-PF-Mode` selector; every
merchant-scoped payment/ledger/analytics row is partitioned by mode; audit and notification record it
verbatim (D126); and legacy mode-less events remain correct.

### M17 — Sandbox Simulation Engine ✅ (complete, 2026-07-23 – 2026-07-24)

**Objective.** Per §5/M17: introduce `sandbox-service`, route authorization decisions through it,
make failure requestable and deterministic for test mode while D104's simulated acquirer keeps live
mode observably different — without payment-service ever depending on anything sandbox-specific.

**Architecture review (2026-07-23, approved).** Two review passes before implementation, per this
project's standing "design before code" discipline:

1. **The M17 design itself** — three findings corrected the plan before any code was written: (a)
   the brief's "sandbox never touches live" reading conflicts with D104 and M17's own completion
   criteria; resolved as **sandbox advises both modes, only test mode is developer-controllable**
   (a live decision's `source` is structurally always `ACQUIRER`, never reachable by merchant
   input); (b) `payment_method_token` has no entry point anywhere in the platform today — added as
   D130; (c) the sandbox call must be obtained **before** the payment transaction opens, never
   inside it — D129, the fix for a connection-pool-exhaustion risk the original task list didn't
   surface. Also: `pm_card_disputed`'s dispute-event behaviour and a `PARTIALLY_CAPTURED` FSM state
   are both **out of scope** — the former has no dispute concept anywhere in V2 (§15), the latter
   would modify M5's completed, M14-load-tested all-or-nothing capture invariant for a simulation
   feature, not a product decision. Four rulings (A–D) resolved: live authorization keeps D104's
   simulated acquirer (Ruling A); the token is an additive nullable column (Ruling B, D130); webhook-
   path scenarios are defined now, enacted in M18 (Ruling C, D131); partial capture is dropped, not
   built (Ruling D).
2. **Future-extensibility pass** — should payment-service depend on an `AuthorizationAdvisor` port
   now, so a real-PSP adapter can replace sandbox later without touching payment-service? **Yes**,
   narrowly: a 1-method port + an acquirer-neutral decision contract, with sandbox as the one
   adapter behind it (D132). Explicitly **not** introduced: provider-selection strategy, multi-
   provider config, a separate adapter service — each would encode a guess about an axis (which
   provider, selected how) with zero real-PSP experience. The port's value isn't "swapping is
   easier" (extracting an interface from one class is trivial) — it's that sandbox-specific
   vocabulary (`source`, `latencyMs`, test-card identity) is prevented from leaking into
   `PaymentResponse`/`PaymentEventPayload`, which become **frozen public contracts** once M21's CI
   spec-diff ships. `PENDING` (not a sandbox-shaped `deferred{…}` object) crosses the port for
   deferred outcomes; `source`/`latencyMs`/override identity stay behind it, surfaced only via
   sandbox's own decision-log query API (M17.8) — "payment-service learns the verdict; sandbox
   keeps the reasoning."

**Decomposition.** Eight independently-committable sub-milestones: **M17.1** `sandbox-service`
module + schema + seeded test-card catalogue (no other service touched); **M17.2** `DecisionEngine`
+ internal decision API + decision log/idempotency (still no other service touched); **M17.3**
payment-service's `payment_method_token` (additive, no sandbox call yet); **M17.4** the
`AuthorizationAdvisor` port + `SandboxAuthorizationAdvisor` adapter wired into authorize, with
degradation; **M17.5** simulation overrides + control API + live-mode rejection; **M17.6** deferred
outcomes (scheduler + `sandbox.scheduled.events` + payment-service's first Kafka consumer role);
**M17.7** the live simulated acquirer (D104); **M17.8** decision-log query API, docs rendered from
the catalogue, full E2E, milestone closure. Ordering rationale: sandbox is fully built and tested
(17.1–17.2) before payment-service is touched at all; the token (17.3) lands separately from the
call (17.4) so a regression in one can never implicate the other; Kafka (17.6) is last among the
mechanisms because it is the only piece introducing a new architectural role for payment-service.

#### M17.1 — `sandbox-service` module, schema, seeded test-card catalogue ✅ (2026-07-23)

**Summary.** New Gradle module `sandbox-service` (`:8094`) boots against real Postgres with its own
`sandbox` schema and serves the seeded test-card catalogue (§8.1) over public, unauthenticated HTTP.
Deliberately the leanest possible service — no Spring Security, no Kafka, no Feign — since this
sub-milestone's only inbound interface is one piece of public reference data. Zero other service
touched.

**Files created.** Module: `sandbox-service/build.gradle.kts` (web, validation, actuator, data-jpa,
flyway, postgres — matching `analytics-service`'s minimal-dependency template; Spring Security and
Kafka are added in M17.2 and M17.6, when a caller for each actually exists);
`SandboxServiceApplication.java`; `application.yaml` (port 8094, `default-schema: sandbox`, no
`internal-context.*` config yet — that property is inert without `AbstractAuthenticationToken` on
the classpath, so it's added in M17.2 alongside the security dependency, not speculatively here).
**Domain:** `domain/TestCard.java` (natural `token` primary key — no synthetic id, since nothing
references a row except by its token), `domain/DecisionOutcome.java` (`APPROVE|DECLINE|ERROR|DELAY|
REQUIRE_ACTION` — `DELAY` is unused by any seeded card in M17.1–M17.8, reserved for a future
authorize-time-deferred scenario, matching D131's define-vocabulary-now discipline),
`domain/CaptureBehaviour.java` (`SUCCEED|FAIL|DEFER`), `domain/RefundBehaviour.java`
(`SUCCEED|FAIL`). **Data access:** `repository/TestCardRepository.java`,
`service/TestCardService.java` (the one place both this milestone's catalogue endpoint and M17.2's
decision-engine card lookup go through — one lookup path, not two). **API:**
`dto/TestCardResponse.java`, `mapper/TestCardMapper.java`, `web/TestCardController.java` (`GET
/v1/test/cards`).

**DB.** `sandbox/V1__init_sandbox.sql` — `test_cards` (token PK, outcome/capture_behaviour/
refund_behaviour as check-constrained closed vocabularies; `chk_test_cards_outcome_shape` makes the
catalogue's coherence a schema guarantee — a DECLINE row with no `decline_code` cannot be inserted).
`sandbox/V2__seed_test_cards.sql` — all 15 cards from §8.1 plus two new ones closing failure-
simulation gaps the original catalogue didn't cover: `pm_card_lostCard` (`DECLINE/lost_card`) and
`pm_card_issuerUnavailable` (`ERROR/issuer_unavailable`). `pm_card_disputed` is seeded as a plain
approve+capture (documented in its own row's description) — no dispute/chargeback concept exists
anywhere in V2 (§15), so there is no event to raise; recording that honestly rather than promising a
dispute event M17 cannot emit.

**Shared infra.** `settings.gradle.kts` (+`sandbox-service`); `docker-compose.yml` (+`sandbox-service`
block, port 8094, no `depends_on` beyond Postgres); `docker/postgres/init/01-init-schemas.sql`
(+`sandbox` schema — belt-and-suspenders documentation; Flyway's own `schemas` property would create
it regardless, exactly how every other service's schema already gets created under Testcontainers).

**Tests.** `TestCardCatalogueIntegrationTest` (`@SpringBootTest` + `@AutoConfigureMockMvc` +
Testcontainers Postgres, the `PaymentIntegrationTest` pattern): both migrations apply against a real,
empty Postgres and `GET /v1/test/cards` returns all 17 seeded rows over real HTTP, asserting the
outcome/decline-code/error-code/latency/capture-behaviour/refund-behaviour shape for a representative
card of each kind — the completion criterion this sub-milestone actually needed proven.

**Verification.** `:sandbox-service:compileJava` clean. `:sandbox-service:test` green (1 test). Full
`./gradlew clean build` green across all 9 modules — no existing service's compilation or tests
affected (confirms the "zero other files touched" scope held in practice, not just in the file list).

**Decisions.** None new in this sub-milestone (D127–D132 recorded from the design/extensibility
review, above).

**Remaining M17 work.** M17.2 — `DecisionEngine` (pure function) + `POST
/internal/v1/sandbox/decisions` + `decision_log` + decision-key idempotency (D128). Still no other
service touched.

#### M17.2 — `DecisionEngine`, the internal decision API, decision-key idempotency ✅ (2026-07-23)

**Summary.** sandbox-service can now advise on an AUTHORIZE/CAPTURE/REFUND outcome over real HTTP:
override → test card → mode default precedence (§8.2), an append-only decision log that doubles as
the idempotency store (D128), and a structural (not merely behavioural) guarantee that a live
decision can never read developer-controllable state. Still no other service touched — every test
in this sub-milestone drives the endpoint directly, signing internal-context headers the same way
payment-service will from M17.4.

**A design correction found while implementing, not assumed in the design doc.** Spring Security's
`WebAsyncManagerIntegrationFilter` propagates the `SecurityContext` across an async dispatch only for
a controller returning `Callable` — **not** for `CompletableFuture`/`DeferredResult`, which is what
M17.2's non-blocking-latency design requires the decision endpoint to return. Guarding
`/internal/v1/**` with `.anyRequest().authenticated()` made `AuthorizationFilter` reject a genuinely-
authenticated request with 401 on the async re-dispatch (no SecurityContext survived to check). Fixed
by matching merchant-service's own established precedent for its internal-only endpoint exactly:
`/internal/v1/**` is `permitAll()` at the Spring Security layer, with `InternalContextFilter`'s HMAC
verification (unconditional, runs before the controller) and the controller's own explicit
`MerchantContextHolder.get().orElseThrow()` as the real gate — not a weaker check, a different layer
enforcing it, one that doesn't depend on surviving an async boundary Spring Security doesn't cover.

**Files created.** **Domain:** `domain/Operation.java` (`AUTHORIZE|CAPTURE|REFUND`),
`domain/DecisionSource.java` (`OVERRIDE|TEST_CARD|MODE_DEFAULT|ACQUIRER` — the field a live decision
must never carry `OVERRIDE`/`TEST_CARD` for), `domain/OverrideScenario.java` (the six authorize/
capture-affecting scenarios from §8.2; `duplicate_webhooks`/`webhook_failure` deliberately excluded,
D131 — the engine never reasons about webhook delivery), `domain/DecisionLogEntry.java` (append-only
JPA entity; `overrideId` has no FK until M17.5's table exists). **Engine (no Spring context needed to
test):** `engine/TestCardProfile.java` (a plain record the engine takes instead of the JPA `TestCard`
entity, which has no public constructor — the minimal fix that makes `DecisionEngine` actually
unit-testable, not a premature abstraction), `engine/OverrideSnapshot.java`, `engine/EngineDecision.java`,
`engine/DecisionEngine.java` — `decide(operation, card, override)` for test mode; `decideLive(operation)`
for live mode, with **no card/override parameters at all**, so §7's mode-isolation guarantee is a
method signature that cannot accept developer-controllable state, not merely a branch that chooses to
ignore it. **Service/API:** `repository/DecisionLogRepository.java`, `dto/SandboxDecisionRequest.java`
(carries no `merchantId`/`mode` — those come only from the verified signed context, §7 barrier ①),
`dto/SandboxDecisionResponse.java`, `service/SandboxDecisionService.java` (decision-key replay lookup
→ mode-gated evaluation → persist, with a `DataIntegrityViolationException` catch-and-requery for the
genuine concurrent-same-key race), `web/SandboxDecisionController.java` (returns
`CompletableFuture<SandboxDecisionResponse>` unconditionally; the decision is evaluated and persisted
*eagerly*, only the response's delivery is delayed — no `Thread.sleep` anywhere, latency is simulated
via `CompletableFuture.delayedExecutor` against a dedicated daemon-thread pool,
`config/SandboxAsyncConfig.java`). **Security:** `config/SecurityConfig.java`,
`security/RestAuthenticationEntryPoint.java`, `security/RestAccessDeniedHandler.java`,
`security/SecurityErrorWriter.java` — mirror payment-service's classes of the same name, minus
OAuth2/JWT (sandbox-service never accepts a JWT at all; its only authenticated callers sign the same
internal-context mechanism a different way).

**Files modified.** `build.gradle.kts` (+`spring-boot-starter-security`, for `InternalContextFilter`
only — no `oauth2-resource-server`); `application.yaml` (+`paymentflow.internal-context.*`, now that
`AbstractAuthenticationToken` is on the classpath and the property is no longer inert; +
`spring.mvc.async.request-timeout: 15000`, comfortably above the 10s injectable-latency ceiling so
Spring's own async timeout can never fire before the latency it's timing).

**DB.** `sandbox/V3__decision_log.sql` — additive, one new table, no change to `test_cards`.

**Tests.** `DecisionEngineTest` (26 cases) — the full card × override × operation precedence matrix
with no Spring context: every `DecisionOutcome`/`CaptureBehaviour`/`RefundBehaviour` branch, override-
beats-card for every applicable scenario, an override that doesn't apply to the current operation
falling through correctly (`DELAY_SETTLEMENT` at AUTHORIZE, `FORCE_DECLINE` at CAPTURE/REFUND, no
override scenario ever applying to REFUND), and all three `decideLive` operations. **Note on scope
allocated deliberately:** override *expiry* (count/time) is **not** tested here — that state doesn't
exist until M17.5's `simulation_overrides` table and `OverrideService`; the engine itself has no
notion of expiry by design (§ engine javadoc), so those tests belong with the service that resolves
"is this override still active," not with the pure function that assumes it already was.
`SandboxDecisionIntegrationTest` (7 cases, Testcontainers Postgres, real signed headers via
`InternalContextSigner` directly): missing context → 401; unknown token → mode-default approve; a
known decline card → `DECLINE`/`card_declined`/`TEST_CARD`; **decision-key replay returns the
original decision even when retried with a different, approving token** — the D128 property, with an
assertion that exactly one `decision_log` row exists for the key; **live mode approves even when
handed a token that declines in test mode** — proving §7's isolation empirically, not just by
construction; an unknown operation → 400; `pm_card_slow` delays the response by ~5s while the decision
itself is already recorded (verified via elapsed wall-clock time around the call).

**Verification.** `:sandbox-service:test` green (34 tests: 26 `DecisionEngineTest` + 7
`SandboxDecisionIntegrationTest` + 1 `TestCardCatalogueIntegrationTest`, carried over from M17.1).
Full `./gradlew clean build` green across all 9 modules — no other service's compilation or tests
affected.

**Decisions.** None new (D127–D132 already recorded); the async/Spring-Security interaction above is
an implementation finding, not a new architectural decision.

**Remaining M17 work.** M17.3 — payment-service's `payment_method_token` (D130): additive migration,
DTO/entity/mapper/response field. No sandbox call yet.

#### M17.3 — payment-service's `payment_method_token` ✅ (2026-07-24)

**Summary.** `Payment` gains an optional `paymentMethodToken` — the test-card token (§8.1) a payment
authorizes against — as a purely additive concept (D130). No sandbox call exists yet (M17.4); the
column exists so M17.4 has something to read instead of landing schema and call together. A payment
created without a token behaves exactly as every payment did before M17, which is also exactly what
lets every M16.2 test keep passing untouched.

**Files created.** `db/migration/V3__payment_method_token.sql` — `alter table payments add column
payment_method_token varchar(64)`, nullable, no backfill, no constraint.

**Files modified.** `domain/Payment.java` (+`paymentMethodToken` field, `updatable = false` — set once
at creation, like every other payment-defining field; `create()`'s signature extended, getter added).
`dto/CreatePaymentRequest.java` (+optional `paymentMethodToken`, `@Size(max = 64)` — no other
validation, since the token's meaning, a known test card or not, is sandbox-service's business, not
payment-service's). `dto/PaymentResponse.java` / `mapper/PaymentMapper.java` (field threaded through to
the API response). `service/PaymentService.java` (`request.paymentMethodToken()` passed into
`Payment.create(...)`).

**Tests.** Every existing call site of `Payment.create(...)` / `new CreatePaymentRequest(...)` updated
to the new arity (`PaymentTest`, `PaymentServiceTest`, `MerchantResilienceIntegrationTest`) — all pass
`null` unless the token is what's under test, keeping every pre-M17 assertion unchanged.
`PaymentIntegrationTest` gains two cases: `paymentMethodTokenRoundTripsWhenSupplied` (a token in the
request body comes back on the created payment's response) and `paymentMethodTokenIsNullWhenOmitted`
(the field is absent from the response when never supplied — no `null` literal serialized, matching
Jackson 3's default).

**Verification.** `:payment-service:test` green (full suite, real Postgres/Kafka via Testcontainers,
no tests skipped). Full `./gradlew clean build` green across all 9 modules — sandbox-service and every
other service unaffected, confirming the migration and field addition are additive in practice, not
just by intent.

**Decisions.** None new (D130 already recorded).

**Remaining M17 work.** M17.4 — the `AuthorizationAdvisor` port + `SandboxAuthorizationAdvisor` adapter
wired into `authorize()`, with degradation (D129, D132).

#### M17.4 — `AuthorizationAdvisor` port, `SandboxAuthorizationAdvisor` adapter, wired into authorize ✅ (2026-07-24)

**Summary.** `PaymentService.authorize()` now obtains a real authorization decision from sandbox-service
instead of unconditionally transitioning to `AUTHORIZED`. Exactly as D132 specified: payment-service
depends only on a one-method `AuthorizationAdvisor` port and two acquirer-neutral records
(`AuthorizationRequest`/`AuthorizationDecision`); `SandboxAuthorizationAdvisor` is the one adapter behind
it, and every sandbox-specific concept (`source`, `latencyMs`, the raw `REQUIRE_ACTION` outcome, the
decision key) is translated or discarded inside that adapter — nothing beyond
`APPROVED`/`DECLINED`/`ERROR`/`PENDING` and a decline/error code ever reaches `PaymentService`. No
provider-selection strategy, multi-provider config, or feature flag exists or was introduced (D132's
explicit non-goal).

**The sandbox call happens before any transaction opens (D129).** `authorize()` no longer goes through
the shared `mutate()` helper (capture/void/refund still do): it reads the payment once outside a
transaction — a fail-fast FSM check (rejecting an already-authorized/voided/failed payment before ever
calling sandbox) plus the immutable facts (`paymentMethodToken`, `amountMinor`, `currency`) the decision
needs — calls `authorizationAdvisor.advise(...)`, then opens a transaction, re-loads the payment under
optimistic locking, and applies the decision. Authority over the payment's actual state is
re-established at that reload, never assumed from the earlier read.

**REQUIRE_ACTION decision (confirmed with Isa 2026-07-24).** `pm_card_authRequired` is a currently-seeded,
reachable test card with no equivalent in the port's `APPROVED/DECLINED/ERROR/PENDING` vocabulary, and
payment-service has no "requires further customer action" FSM state — genuinely undecomposed anywhere in
M17.1–M17.8. Resolved: fold `REQUIRE_ACTION` into `DECLINED` with `declineCode="authentication_required"`
rather than add a fifth port-level outcome, since `PaymentService` has no FSM state to do anything
different with it in M17.4 either way; revisit only if a real step-up-auth flow is ever scoped.

**A design finding, not a new decision.** `FeignAuthorizationForwardingConfig`'s `RequestInterceptor`
(M8) is a global Spring bean, not scoped to `MerchantClient` — it forwards the caller's JWT to *every*
Feign client, including the new `SandboxClient`. Harmless (sandbox-service has no OAuth2 resource server
and `InternalContextFilter` only reads `X-PF-Internal-*` headers, so an incidental `Authorization` header
is simply ignored) but worth recording rather than leaving as an unexplained header in a request trace.

**Files created.** **Port** (`authorization/`): `AuthorizationAdvisor.java` (the one-method interface),
`AuthorizationRequest.java` (`paymentId`/`merchantId`/`mode`/`paymentMethodToken`/`amountMinor`/
`currency` — no decision key, no provider concept), `AuthorizationDecision.java` (outcome +
decline/error code, with `approved()`/`declined(code)`/`error(code)` factories), `AuthorizationOutcome.java`
(`APPROVED|DECLINED|ERROR|PENDING` — `PENDING` is part of the contract per D132 but produced by no
adapter until M17.6). **Adapter** (`authorization/sandbox/`): `SandboxAuthorizationAdvisor.java` (the
Retry → CircuitBreaker → TimeLimiter → ThreadPoolBulkhead chain around the call, composed
programmatically exactly like `MerchantResolver`, D49 — minus the `RequestAttributes` hand-off, since
this call forwards no caller JWT and has no thread-affinity requirement to work around), `SandboxClient.java`
(Feign interface; internal-context headers passed as explicit `@RequestHeader` params per call, not a
shared interceptor), `SandboxDecisionRequest.java`/`SandboxDecisionResponse.java` (payment-service-local
projections of sandbox's wire shape, D4's schema-per-service philosophy applied to REST contracts —
mirrors `MerchantSummary`'s precedent), `SandboxFeignClientConfig.java` (hard socket timeouts, mirrors
`FeignClientConfig`), `SandboxResilienceProperties.java` (timeouts/backoff plus the fixed internal-context
identity — `serviceKeyId`/`serviceScopes` — payment-service asserts on its own authority when calling
sandbox), `SandboxResilienceConfig.java` (retry backoff-with-jitter customizer; reuses
`MerchantResilienceConfig`'s existing shared `ScheduledExecutorService` bean rather than declaring a
second one, since that pool's job is already generic). `exception/SandboxServiceUnavailableException.java`
(mirrors `MerchantServiceUnavailableException`, maps to 503).

**Files modified.** `service/PaymentService.java` (`authorize()` rewritten per D129 above;
`applyAuthorizationDecision` translates the neutral decision into the payment's transition —
`APPROVED` → `payment.authorize()` / `PaymentAuthorized`, `DECLINED`/`ERROR` → `payment.fail(code)` /
`PaymentFailed`). `application.yaml` (+`paymentflow.services.sandbox.base-uri`;
+`paymentflow.resilience.sandbox-service.*`; +`resilience4j.*.instances.sandboxService.*` — sized above
the platform's currently-reachable maximum injected latency, `pm_card_slow`'s ~5s, with headroom: 7s
socket read timeout, 8s TimeLimiter budget, 6s `slowCallDurationThreshold` so a deliberate simulation
feature doesn't erode the circuit breaker's health signal the way genuine sandbox-service slowness
should). `docker-compose.yml` (+`PAYMENTFLOW_SERVICES_SANDBOX_BASE_URI`; sandbox-service deliberately not
added to payment-service's `depends_on`, same rationale as merchant-service, D8-era M8 precedent).

**Tests.** `PaymentServiceTest` (unit, Mockito): `authorizeFailsThePaymentOnADeclinedDecision`,
`authorizeFailsThePaymentOnAnErrorDecision`, `authorizeNeverCallsTheAdvisorWhenThePaymentCannotBeAuthorized`
(fail-fast pre-check verified via `verifyNoInteractions(authorizationAdvisor)`) alongside the existing
approve-path test, now driven by a mocked `AuthorizationAdvisor`. `PaymentIntegrationTest` (Testcontainers):
extended its shared JDK `HttpServer` stub with `/internal/v1/sandbox/decisions` (approve-by-default,
decline/error keyed off `pm_card_chargeDeclined`/`pm_card_processingError` in the request body) and added
`authorizeFailsThePaymentWhenSandboxDeclines`, `authorizeFailsThePaymentWhenSandboxErrors`,
`authorizingAnAlreadyFailedPaymentIsRejectedWith409WithoutCallingSandboxAgain` — full HTTP-level proof of
the fail-fast FSM check. New `SandboxResilienceIntegrationTest` (Testcontainers), mirroring
`MerchantResilienceIntegrationTest`'s structure exactly but scoped to the `sandboxService` instance and
`POST /authorize`: a healthy sandbox approves; sandbox down surfaces 503 without mutating the payment (a
retry under a new Idempotency-Key against the now-healthy sandbox then succeeds); sandbox too slow fails
fast via TimeLimiter rather than hanging the request thread; repeated failures open the circuit, which
then recovers through half-open to closed.

**Verification.** `PaymentServiceTest`, `PaymentIntegrationTest`, and `SandboxResilienceIntegrationTest`
all green in isolation; `:payment-service:test` green (full suite, real Postgres/Kafka/Redis via
Testcontainers); `:sandbox-service:test` green and unaffected (M17.4 touches only payment-service). Full
`./gradlew clean build` green across all 9 modules.

**Decisions.** None new beyond D127–D132 (already recorded) and the REQUIRE_ACTION resolution above,
which is a scoping call within M17.4's own boundary, not a new architectural decision.

**Remaining M17 work.** M17.5 — simulation overrides + control API + live-mode rejection.

#### M17.5 — Simulation overrides, control API, live-mode rejection ✅ (2026-07-24)

**Summary.** The "chaos knob" (§8.2) is real: a merchant can now force the next N test-mode
authorizations (or the next N seconds) to decline, error, add latency, time out, rate-limit, or
defer capture settlement — through a public, key-authenticated control API — and
`SandboxDecisionService`'s override lookup (documented as a gap since M17.2) is wired in. Every
scenario is contained inside sandbox-service; payment-service is completely untouched by this
milestone — it still only ever sees `APPROVED`/`DECLINED`/`ERROR`/`PENDING` through
`AuthorizationAdvisor` (M17.4), exactly as before.

**Schema (`sandbox/V4__simulation_overrides.sql`).** `simulation_overrides` (merchant, mode,
scenario, decline/error code, latency, remaining count, expiry, revoked-at). Two structural
guarantees, not just runtime checks: `chk_simulation_overrides_mode check (mode = 'test')` makes a
live-mode row impossible regardless of what the application layer does or fails to check (§7); a
partial unique index (`merchant_id, mode where revoked_at is null`) makes "at most one active
override per merchant/mode" a schema fact, not just application discipline. Additive FK from
`decision_log.override_id` to this table — M17.2's own migration comment anticipated exactly this
("`override_id` has no foreign key yet... M17.5 adds the FK additively").

**A broader vocabulary than the engine's, by design.** `domain/SimulationScenario` (8 values,
matching §8.2's full table) is deliberately wider than `DecisionEngine`'s own 6-value
`OverrideScenario` (M17.2, untouched): `toEngineScenario()` maps 6 of them 1:1 and returns empty for
`DUPLICATE_WEBHOOKS`/`WEBHOOK_FAILURE` — D131's "defined now, enacted by M18" scenarios are
validated and persisted here (so M18 has something to read) but never reach the engine at all,
keeping M17.2's "the engine never reasons about webhook delivery" property exactly intact.

**Files created (sandbox-service, flat packages matching the module's own convention).**
`domain/SimulationScenario.java`, `domain/SimulationOverride.java` (JPA entity; no setters for
`remainingCount`/`revokedAt` — both change only through the repository's atomic `@Modifying`
queries, never entity mutation; no `@Version`, D127); `repository/SimulationOverrideRepository.java`
(`decrementRemainingCount` — D127's single atomic conditional `UPDATE ... WHERE remaining_count > 0`,
a no-op by SQL semantics for a `null`-count row rather than needing a separate branch;
`revokeActive`); `service/OverrideService.java` (create supersedes any existing active override for
the same merchant/mode; live-mode create rejected with `ForbiddenException`, defense-in-depth
alongside the schema check; scenario-specific field validation the way `SandboxDecisionRequest`'s own
cross-field rules are validated — in the service, not bean-validation annotations);
`dto/CreateSimulationOverrideRequest.java`, `dto/SimulationOverrideResponse.java`,
`mapper/SimulationOverrideMapper.java` (sets `enactedFrom: "M18"` only for the two webhook
scenarios); `web/SimulationController.java` (`POST /v1/test/simulations`, `GET`/`DELETE
/v1/test/simulations/active` — plain, non-async MVC returns, so the existing
`.anyRequest().authenticated()` catch-all in `SecurityConfig` already covers it; no `SecurityConfig`
change needed, its own M17.2 javadoc already anticipated this exact caller).

**Files modified.** `service/SandboxDecisionService.java` — `evaluate()` now looks up the active
override via `OverrideService.findActive`, hands the engine an `OverrideSnapshot` when one maps to an
engine scenario, and consumes it (D127) only when the engine's own returned `source` is actually
`OVERRIDE` — the engine's existing "does this scenario apply to this operation" precedence check
(§8.2, unchanged since M17.2) is the single source of truth for whether the override was really
used, so the service never re-derives or second-guesses that. `overrideId` now flows into
`DecisionLogEntry` instead of always `null`.

**The gateway route (D100's existing mechanism, not a new one).** `sandbox-service` has no OAuth2
resource server and never will for this endpoint family — its control API is reachable only via the
gateway asserting the same HMAC-signed internal context it already asserts for payment-service's
`/v1/payments` (M15), routed by one new declarative entry
(`gateway-service/application.yaml`: `Path=/v1/test/simulations/**`, direct passthrough, no rewrite
needed since sandbox-service's own controller path already is `/v1/test/simulations`). No changes to
`ApiKeyAuthenticationWebFilter`: the existing generic "a publishable key cannot mutate" check already
covers POST/DELETE here with no path-specific scope needed.

**Tests.** `SimulationOverrideTest` (unit, domain-only): the three independent ways an override ends
(revoked, expired, exhausted), tested without persistence. `OverrideServiceTest` (unit, Mockito):
live-mode rejection, every scenario's required-field validation, latency-ceiling validation, the
"needs a count or a duration" rule, revoke-before-create, duration-to-expiry conversion, consume
delegation. `SandboxDecisionIntegrationTest` (extended, Testcontainers): a `FORCE_DECLINE` override
beats an approving card and is consumed exactly once (the very next authorize falls back to the
card); an authorize-only override never applies to `REFUND` and is never consumed by it; a webhook
scenario override never reaches the engine at all. New `SimulationControllerIntegrationTest`
(Testcontainers): create/get-active/revoke over real HTTP with signed headers; live-mode POST → 403;
a scenario missing its required field → 400; neither count nor duration → 400; a webhook scenario →
201 with `enactedFrom: "M18"`; a second create supersedes the first; no active override → 404;
missing internal context → 401. Gateway: `ApiKeyAuthenticationIntegrationTest` gained a sandbox stub
and one test proving `/v1/test/simulations` resolves through the new route with a signed context.

**A bug caught by the test suite, not by inspection.** The first draft of
`chk_simulation_overrides_remaining_count_positive` required `remaining_count > 0` unconditionally —
which made D127's own atomic decrement (1 → 0, the exhaustion case the whole mechanism exists to
produce) violate the table's own constraint, surfacing as a 500 from
`SandboxDecisionIntegrationTest`'s new override tests. Fixed to `>= 0` (a negative value is what's
actually invalid; requiring positive-at-creation is `OverrideService`'s job, not this constraint's) —
recorded here because it's exactly the kind of schema/application split this project's own review
discipline exists to catch.

**Verification.** New unit tests green in isolation; `:sandbox-service:test` green (full suite,
Testcontainers Postgres); `:gateway-service:test` green (full suite, including the new route test);
`:payment-service:test` green and unaffected (M17.5 touches no payment-service file at all — verified
by grepping for any cross-reference in both directions before commit). Full `./gradlew clean build`
green across all 9 modules.

**Decisions.** None new beyond D127/D131 (already recorded, now actually implemented rather than
just designed for).

**Remaining M17 work.** M17.6 — deferred outcomes: the scheduler, `sandbox.scheduled.events`, and
payment-service's first Kafka consumer role.

#### M17.6 — Deferred outcomes: the scheduler, `sandbox.scheduled.events`, payment-service's first Kafka consumer role ✅ (2026-07-24)

**Summary.** `pm_card_delayedSettlement` and the `DELAY_SETTLEMENT` override now do what §8.1/§8.2
document: authorize succeeds immediately, and the payment's capture settles asynchronously ~N seconds
later, applied through the *same* FSM guard (`Payment.capture()`) a synchronous call uses. sandbox-service
gains its first producer role (`sandbox.scheduled.events`); payment-service gains its first consumer
role. `AuthorizationAdvisor` is untouched — payment-service's `authorize()` flow doesn't change at all;
the scheduling trigger is entirely internal to sandbox-service's own decision orchestration.

**The design question this milestone actually turned on.** §8.1 describes `pm_card_delayedSettlement`
as "authorizes now, captures asynchronously later," but no milestone in M17.1–M17.8's decomposition
wires payment-service's `capture()` endpoint to call sandbox at all (only `authorize()`, M17.4) — and
D131's "`DELAY` is unused by any seeded card in M17.1–M17.8" already forecloses the *other* obvious
reading (a deferred-*authorization* outcome). Resolved without touching `DecisionEngine` (a pure
function, D103/M17.2's own charter: "no persistence, no I/O") or payment-service's capture HTTP
contract (V1, M5): `SandboxDecisionService`, after a successful AUTHORIZE decision, runs one additional
read-only lookahead — `decisionEngine.decide(CAPTURE, sameCard, sameOverride)` — purely to ask "would
this payment's capture defer?" If the engine's answer carries `deferredOperation`, a `scheduled_outcomes`
row is written in the *same transaction* as the authorize decision; the override is consumed (D127) via
the identical `source == OVERRIDE` check M17.5 already established. No seeded card or override makes an
AUTHORIZE decision *itself* carry `deferredOperation` today, so this lookahead is the only thing that
ever schedules anything — but the mechanism is generic (whatever `deferredOperation`/`deferredDelayMs`
the engine returns), not hardcoded to capture.

**Schema.** `sandbox/V5__scheduled_outcomes.sql` — `scheduled_outcomes` (payment, merchant, mode,
operation, outcome, fire-at, delivered-at), mirroring `outbox_events`' shape (D3) exactly: a
`delivered_at IS NULL` predicate is the "still pending" query, same as payment-service's own outbox.
`chk_scheduled_outcomes_mode check (mode = 'test')` — the same structural (not just runtime)
mode-isolation guarantee `simulation_overrides` already has (§7). `payment/V4__processed_sandbox_events.sql`
— `processed_events`, payment-service's own idempotent-consumer dedup table (D2), mirroring
transaction-service's identical table (M6) — generic by design despite the migration's filename
(Flyway filenames are permanent once applied), not scoped to sandbox specifically.

**Files created (sandbox-service).** `domain/ScheduledOutcome.java` (JPA entity, `markDelivered()`),
`repository/ScheduledOutcomeRepository.java`, `service/ScheduledOutcomeService.java` (the write side,
called only from within `SandboxDecisionService`'s existing transaction), `event/SandboxScheduledOutcomePayload.java`,
`scheduler/ScheduledOutcomeRelay.java` (the poller — `@Scheduled` + `@Transactional`, publishes to
`sandbox.scheduled.events` via a `KafkaTemplate<String,String>`, marks delivered; a failed publish is
left undelivered for the next tick, at-least-once, D2, identical shape to payment-service's
`OutboxRelay`), `config/KafkaProducerConfig.java`/`config/KafkaTopicConfig.java` (mirror
payment-service's exactly). `build.gradle.kts` gains `spring-boot-starter-kafka` — this service's first
Kafka dependency, exactly where M17.1's own comment said it would land ("Kafka ... is added in M17.6,
when a caller for it exists").

**Files created (payment-service, confined to the existing sandbox adapter package plus one neutral
consumer-idempotency table).** `authorization/sandbox/SandboxScheduledEventListener.java` — a thin
`@KafkaListener` translator: parses the sandbox-shaped envelope, and for a `CAPTURE` operation makes one
plain call, `paymentService.applyDeferredCapture(eventId, eventType, paymentId, mode)` — no sandbox
vocabulary crosses out of this package (D132's discipline extended to the Kafka boundary, not just the
synchronous port). `authorization/sandbox/SandboxScheduledOutcomePayload.java` (the wire-shape
projection, D4). `event/ProcessedEvent.java` + `repository/ProcessedEventRepository.java` — deliberately
*not* placed in the sandbox package: this is generic Kafka-consumer dedup infrastructure (an event id
and type, nothing sandbox-shaped), living alongside `PaymentEventPayload`/`PaymentEventPublisher` where
any future consumer role can reuse it, matching `PaymentService.applyDeferredCapture`'s own
sandbox-agnostic signature.

**Files modified.** `service/SandboxDecisionService.java` — the capture-lookahead-and-schedule step
described above. `service/PaymentService.java` — `applyDeferredCapture(eventId, eventType, paymentId, mode)`:
the entire "dedup check → `Payment.capture()` → publish → mark processed" sequence in *one*
`transactionTemplate` attempt, retried whole on `OptimisticLockingFailureException` (mirrors
transaction-service's `LedgerService.processEvent`, M6, exactly — and deliberately *not* split across
the listener's own transaction and this method's, which would have nested the retry loop inside an
outer transaction and broken per-attempt retry semantics, a bug caught during design, not by a failing
test). An already-CAPTURED (or otherwise non-`AUTHORIZED`) payment — the client's own explicit capture
beat the deferred event, or this is a redelivery — is a durable no-op, not an error.
`repository/OutboxEventRepository.java` gains `findTopByAggregateIdOrderByCreatedAtDesc` — a
Kafka-triggered mutation has no caller/JWT to resolve a merchant through, so `merchantContactEmail`/
`merchantWebhookUrl` for the `PaymentCaptured` event are sourced from the payment's own most recent
prior event instead (every event already carries them, D43) rather than a merchant-service call this
code path has no request context to make.

**Files modified (infrastructure).** `docker-compose.yml` — sandbox-service gains
`SPRING_KAFKA_BOOTSTRAP_SERVERS` and a `kafka: condition: service_healthy` dependency.
`sandbox-service`/`payment-service` `application.yaml` — producer/consumer Kafka config,
`paymentflow.scheduled-outcomes.relay-interval-ms` (1s), `paymentflow.kafka.sandbox-scheduled-events-topic`.

**Tests.** `SimulationOverrideTest`/`OverrideServiceTest` untouched (M17.5's own suite, unaffected).
`SandboxDecisionIntegrationTest` (extended): authorizing `pm_card_delayedSettlement` schedules a
`CAPTURE` row (~5s out) without changing the authorize outcome; an active `DELAY_SETTLEMENT` override
schedules a deferred capture *and* is consumed even though it never applies to the AUTHORIZE decision
itself; a plain approving card schedules nothing. New `ScheduledOutcomeRelayIntegrationTest`
(Testcontainers Postgres + Kafka): a due row is published with the correct envelope shape and marked
delivered; a not-yet-due row is not published (fixed a real test bug here — a fresh consumer group with
`auto-offset-reset=earliest` also sees an *earlier test method's* leftover message on the shared topic;
filtering by this test's own `paymentId` key, not a raw record count, is what actually proves the
negative). `PaymentServiceTest` (unit, Mockito): `applyDeferredCapture`'s already-processed/not-found/
mode-mismatch/already-captured branches. New `SandboxScheduledEventIntegrationTest` (Testcontainers
Postgres + Kafka, mirrors `TransactionIntegrationTest`'s pattern exactly — publish real messages, poll
for the async effect, no live sandbox-service required to test this consumer in isolation): a deferred
capture transitions `AUTHORIZED` → `CAPTURED` with the correct `PaymentCaptured` event (merchant fields
included); redelivering the same event id is an idempotent no-op; a payment already captured directly is
untouched by a subsequent deferred event; a client's direct capture racing the deferred consumer for the
same payment never double-captures (concurrency correctness, D127-style optimistic-lock retry proven
against a real database, not mocked).

**Verification.** All new/targeted tests green in isolation; `:sandbox-service:test` green (69 tests,
full suite); `:payment-service:test` green (full suite, unaffected pre-M17.6 tests included); full
`./gradlew clean build` green across all 9 modules; `docker compose -f docker-compose.infra.yml -f
docker-compose.yml config` validates cleanly (the full live-stack `docker-compose up` + health + E2E
validation is M17.8's own explicit scope, not repeated here).

**Decisions.** None new — D3 (transactional outbox), D127 (atomic override consumption), D131
(webhook-scenario deferral), and D132 (the port boundary) already cover every design choice this
milestone made; the capture-lookahead mechanism is an implementation consequence of those, not a new
architectural decision.

**Remaining M17 work.** M17.7 — the live simulated acquirer (D104).

#### M17.7 — The live simulated acquirer (D104) ✅ (2026-07-24)

**Summary.** Live mode is no longer indistinguishable from test mode's own deterministic default: it
now applies a small stochastic decline rate, an occasional transient error, and a realistic latency
distribution — configurable per environment (§8.4) — recorded in `decision_log` with `source=ACQUIRER`
so behaviour stays explainable after the fact. `AuthorizationAdvisor` (M17.4) needed zero changes:
`SandboxAuthorizationAdvisor`'s mapping already treats any `DECLINE`/`ERROR` outcome generically, so a
live acquirer decline flows through the exact same port-level path a test-mode decline does.

**Correction to a scope misstatement in M17.6's own changelog entry, caught before it did any
damage.** M17.6's "Remaining M17 work" pointer (just above, now fixed) mislabeled M17.7 as "the
decision-log query API" — the original M17 architecture review (§17, this document, 2026-07-23)
assigns that to **M17.8**, and M17.7 to the live simulated acquirer. Caught and confirmed with Isa
before writing any M17.7 code; corrected here rather than silently compounding the error into the
implementation itself.

**Where the implementation actually landed.** `DecisionEngine.decideLive` was, by its own M17.2
javadoc, always the designated extension point ("Until M17.7 wires the simulated acquirer, this is the
same deterministic approve every mode default is") — completing it is not a redesign of a completed
milestone, it is that milestone's own documented gap. The engine stays a pure function even though live
mode is now stochastic: `decideLive(Operation, double outcomeDraw, double latencyDraw)` takes the random
draws as explicit parameters rather than calling `Math.random()` itself, so `SandboxDecisionService` (an
existing orchestration-layer bean, already the home of every other piece of I/O/randomness this service
needs) owns the actual random source, and the engine remains exhaustively testable with plain boundary
values exactly like every other branch in `DecisionEngineTest`.

**Files created.** `engine/SimulatedAcquirerProperties.java` — `declineRate`/`errorRate`/
`latencyMeanMs`/`latencyStdDevMs`, `@ConfigurationProperties(prefix = "paymentflow.simulated-acquirer")`.

**Files modified.** `engine/DecisionEngine.java` — constructor now takes `SimulatedAcquirerProperties`;
`decideLive` computes an outcome from `outcomeDraw` against the decline/error slices (`card_declined`/
`processing_error` — reusing the platform's existing stable codes, not inventing new ones) and a latency
from `latencyDraw` (a uniform spread around the configured mean, clamped to the same 10s platform-wide
ceiling every other injected latency respects), always tagged `DecisionSource.ACQUIRER`.
`service/SandboxDecisionService.java` — the live-mode branch of `evaluate()` now draws two `Random#
nextDouble()` values and passes them through. `application.yaml` —
`paymentflow.simulated-acquirer.{decline-rate=0.03, error-rate=0.01, latency-mean-ms=120,
latency-std-dev-ms=40}`, small enough not to be a nuisance in local dev, real enough to be measurably
different from test mode's default.

**A real regression caught by re-running the suite, not by inspection.** `SandboxDecisionIntegrationTest`
had one pre-existing M17.2 test (`liveModeIgnoresADecliningTokenAndApproves`) asserting the *placeholder*
behaviour `decideLive` was always going to stop having — `source=MODE_DEFAULT` for live mode. Fixed by
asserting the property that's actually now guaranteed (`source=ACQUIRER`, deterministically, regardless
of any token) rather than the specific stochastic outcome, which is no longer deterministic and
shouldn't be asserted as if it were. Every other `MODE_DEFAULT` assertion in that file was already
correctly scoped to test-mode calls and needed no change.

**Tests.** `DecisionEngineTest` (extended, no Spring context): decline/error/approve slice boundaries
against explicit draws; latency centered on the configured mean and clamped within the configured
spread; CAPTURE and REFUND share AUTHORIZE's distribution. `SandboxDecisionIntegrationTest` (extended):
the fixed regression test above, plus a new statistical test —
`liveModeProducesAMeasurablyDifferentOutcomeDistributionThanTestModesDeterministicApprove` — driving 300
real live-mode decisions over HTTP and asserting every one carries `source=ACQUIRER` while at least one
comes back non-`APPROVE` (with the configured 4% combined decline+error rate, the chance of zero
non-approvals in 300 trials is roughly 1 in 80,000 — this is the completion criterion "Live mode's
simulated acquirer produces a measurably different outcome distribution from test mode," asserted the
way a genuinely non-deterministic property should be, not pinned to an exact count).

**Verification.** `:sandbox-service:test` green (72 tests, full suite); `:payment-service:test` green
and unaffected (M17.7 touches no payment-service file — `AuthorizationAdvisor`'s port-level mapping
already handles any acquirer-shaped decline/error generically); gateway-service untouched, not
re-verified. Full `./gradlew clean build` green across all 9 modules.

**Decisions.** None new — D104 (already recorded) is what this milestone implements; no new
architectural choice was made beyond it.

**Remaining M17 work.** M17.8 — the decision-log query API, docs rendered from the test-card catalogue,
full E2E validation, and milestone closure.

---

#### M17.8 — Decision-log query API, docs wiring, full production-style validation, M17 closure ✅ (2026-07-24)

**Summary.** Merchants can now retrieve *why* a decision was made, not just its verdict:
`GET /v1/test/decisions` (paginated, newest first) and `GET /v1/test/decisions/payments/{paymentId}`,
both scoped to the caller's own `merchantId`/`mode` from the verified internal context — the same
signed-header mechanism `SimulationController` (M17.5) already uses, no new auth pattern. Closes M17
with a full production-style validation pass: all 9 images rebuilt, the whole compose stack brought up
and exercised end-to-end, every downstream propagation path (ledger, audit, analytics, notification)
inspected directly in Postgres, not just asserted by a green test suite.

**Files created.** `repository/DecisionLogRepository.java` (two new derived-query methods —
`findByMerchantIdAndModeOrderByCreatedAtDesc` paginated, `findByMerchantIdAndModeAndPaymentIdOrderBy
CreatedAtDesc` for the single-payment view) — added to the existing M17.2 repository, not a new one.
`dto/DecisionLogEntryResponse.java`, `mapper/DecisionLogMapper.java`, `service/DecisionLogQueryService.java`,
`web/DecisionLogController.java` (`@RequestMapping("/v1/test/decisions")`).

**Files modified.** `gateway-service/application.yaml` — two new route entries, kept independent of
M17.5's own route (`sandbox-service-decisions` for the query API; `sandbox-service-test-cards` for the
catalogue, see below) rather than broadening an existing predicate. `gateway-service/SecurityConfig.java`
— one `permitAll()` added to chain #1's `authorizeExchange`, scoped to `GET /v1/test/cards/**` only.

**A real gap found and closed: the test-card catalogue had no path to the outside world.**
`TestCardController` (`GET /v1/test/cards`, M17.1) was built with its own javadoc already declaring it
"the single source the documentation (M17.8) renders from" — but no gateway route ever existed for it,
and chain #1's `anyExchange().authenticated()` would have 401'd it even if a route had. Since M17.8's own
scope is exactly "docs rendered from the catalogue," leaving the catalogue unreachable from outside the
docker network would have left that scope item undone in substance while appearing done on paper. Fixed
with the new route + a narrowly-scoped `permitAll()` (GET only, that one path only — every other `/v1/**`
path still requires authentication). This *is* new M17.8 work, not a redesign of M17.1: M17.1 built the
data and endpoint, M17.8 (per its own original scope) is what was always supposed to wire it externally.
Verified end-to-end: `curl http://localhost:8080/v1/test/cards` with **no** `Authorization` header at all
returns the full 17-card catalogue through the gateway.

**A genuine pre-existing regression found and fixed: the root `Dockerfile` never learned about
`sandbox-service`.** `docker compose build` failed outright — "Configuring project ':sandbox-service'
without an existing directory is not allowed" — because the Dockerfile's builder-stage `COPY
<module>/build.gradle.kts` list (added once per module, M9) was never updated when `sandbox-service` was
added in M17.1. This had gone undetected for M17.1 through M17.7 because none of those milestones actually
ran a Docker build — all verification until now was `./gradlew test`/`build` only. Fixed with one line
(`COPY sandbox-service/build.gradle.kts sandbox-service/build.gradle.kts`, alongside the other eight).
This is a V1-pattern (M9) file shared by every service, not sandbox-specific machinery — but M9's own
work was correct for the V1 service set at the time; the gap was introduced when M17.1 (V2) added a ninth
module without updating the shared file, and is fixed here as ordinary V2 bugfix work. No PROJECT_CONTEXT.md
change follows from this: nothing about M9's historical description became inaccurate, so touching that
frozen file would violate the pointer-note-only invariant confirmed during M17.5's own doc-consistency
check, for no actual architectural-consistency gain.

**Tests.** `DecisionLogControllerIntegrationTest` (new, Testcontainers Postgres, real HTTP via MockMvc,
signed internal-context headers): newest-first ordering, merchant/mode scoping (a merchant cannot see
another merchant's decisions, nor another mode's), single-payment lookup, a declined decision exposing
its code/source, and a missing-context request rejected 401. `ApiKeyAuthenticationIntegrationTest`
(gateway-service, extended): `aValidSecretKeyReachesTheDecisionLogQueryApiWithASignedInternalContext`
(the query API route resolves independently of M17.5's route) and
`theTestCardCatalogueIsReachableWithNoAuthorizationHeaderAtAll` (the new permitAll rule actually took
effect, not just that the route predicate matches — asserted with zero `Authorization` header at all).

**Full production-style validation (live docker-compose stack, not just the test suite).**
- All 9 service images rebuilt clean from source (`docker compose build`), including the Dockerfile fix;
  all 13 containers (`postgres`, `redis`, `kafka`, `kafka-ui`, and all 9 services) reported `healthy`.
- Full gateway E2E flow driven over real HTTP against the running stack, using a freshly-registered user,
  merchant, and the merchant's own onboarding-issued `sk_test_`/`sk_live_` keys (no test-only bypass):
  - **Synchronous authorization** — `pm_card_visa` created → authorized → `AUTHORIZED` immediately.
  - **Deferred authorization** — `pm_card_delayedSettlement` authorized → `AUTHORIZED` immediately, then
    self-transitioned to `CAPTURED` (`capturedAmountMinor` populated) ~5s later with no further client
    call, confirming the M17.6 scheduler → Kafka → payment-service consumer chain end-to-end.
  - **Simulation overrides** — a `FORCE_DECLINE` override (`insufficient_funds`, `remainingCount=1`)
    correctly overrode `pm_card_visa`'s normal approve outcome; `remaining_count` decremented 1→0
    (D127's atomic conditional update, confirmed directly in Postgres).
  - **Replay/idempotency** — both the create and authorize calls replayed with identical
    `Idempotency-Key`s returned byte-identical responses (same id, timestamps, terminal state); the
    decision log confirmed only 3 decisions exist for 3 distinct payments — the replayed authorize did
    **not** produce a second `decision_log` row, confirming D128's decision-key idempotency held under
    a real replay, not just a unit test.
  - **Decision-log API** — both endpoints queried live; entries correctly carry `source` (`TEST_CARD`/
    `OVERRIDE`/`ACQUIRER`), `declineCode`, and `overrideId` where applicable.
  - **Live-mode rejection** — `sk_live_` attempting `POST /v1/test/simulations` correctly 403'd
    ("Simulation overrides are only available in test mode"); by contrast, `GET /v1/test/decisions` with
    the same live key correctly returned **200** with that mode's own decisions (empty until a live
    payment existed, then one entry after — confirming the query API scopes by mode rather than
    rejecting live mode outright, which is the correct behaviour: only *overrides* are test-only, per §7,
    not the ability to see live decisions).
  - **D104 spot-check** — one live-mode `pm_card_visa` authorization logged `source=ACQUIRER` with a
    non-zero injected latency (151ms), distinct from test mode's `latencyMs=0`.
- **Postgres inspection** (`docker exec` into `paymentflow-postgres`): all 35 expected tables present
  across all 9 schemas; `payment.payments`, `sandbox.decision_log`, `sandbox.scheduled_outcomes`,
  `payment.processed_events`, and `sandbox.simulation_overrides` all held exactly the rows the E2E flow
  above should have produced, with no extras and no gaps.
- **Event propagation confirmed directly in Postgres**, not inferred from application logs:
  `transaction.ledger_transactions` recorded `PaymentAuthorized`/`PaymentCaptured` entries (including for
  the *deferred* capture — proving the Kafka-triggered capture publishes a real outbox event
  indistinguishable from a synchronous one); `audit.audit_log` recorded every lifecycle event for all
  4 test payments; `analytics.merchant_payment_stats` showed correct per-mode counts (`test`:
  created=3/authorized=2/captured=1, `live`: created=1/authorized=1) and `total_captured_amount_minor`;
  `notification.email_log` recorded a `Payment update: <EventType>` entry for every lifecycle event, all
  correctly `mode`-tagged.
- Stack torn down to rebuilt-image state after the Dockerfile/gateway fixes (not the pre-fix images);
  re-verified healthy and re-ran the decision-log query against the fresh containers — data survived the
  restart correctly (Postgres-backed, as expected).
- Full `./gradlew clean build` green across all 9 modules (zero test failures, confirmed both from the
  Gradle task summary and by scanning every module's JUnit XML output directly).

**Decisions.** None new — the gateway route/permitAll addition and the Dockerfile fix are both completions
of already-decided scope (M17.1's own javadoc for the former; M9's established per-module COPY pattern for
the latter), not new architectural choices.

**M17 status: complete.** All eight sub-milestones (M17.1–M17.8) implemented, verified independently, and
validated together on a live, freshly-rebuilt docker-compose stack. `AuthorizationAdvisor` (D132) needed
zero changes across M17.5–M17.8, confirming the abstraction held exactly as designed. No known regressions
in V1 or M15/M16 behaviour — the full E2E flow above re-exercises the create → authorize → capture/fail
lifecycle (V1's own core path) alongside every M17-specific capability.

---

### M18 — Webhooks as a Product ✅ (complete, 2026-07-25)

**Objective.** Per §5/M18: evolve notification-service from "POST the merchant's one URL" into a real
webhook subsystem — many endpoints, event-type subscriptions, HMAC-signed payloads, an explicit retry
schedule, a complete delivery log, manual replay, endpoint auto-disable, and SSRF defence. Closes V1
known issue #2 (§2.11): a merchant cannot cryptographically verify a webhook came from this platform.

**Repository review (2026-07-25).** A full read of the codebase before any code was written, per this
project's standing "understand before modifying" discipline. Eight places where §5/M18's task list
differs from the repository as it actually stands were found and resolved rather than assumed away:

1. **Migration numbering.** Task 1 names `V2__webhooks.sql`, written when `notification` was still at
   V1; it has since gained V2 (M15's `email_log` generalisation) and V3 (M16.6's mode columns). This
   milestone's migration is **`V4__webhooks.sql`** — a Flyway ordering fact, not a design change.
2. **Task 10's "migration" is not a migration.** `merchants.webhook_url` lives in merchant-service's
   schema, which notification-service cannot read (D4) and deliberately never asks for (D43). Resolved
   as **D135** (lazy adoption from the event payload).
3. **The `/api/v1` mirror has no consumer until M23.** Resolved as **D133** (deferred; no OAuth2
   resource server in notification-service).
4. **§4.7 lists three topics; task 7 names two.** Resolved as **D134** (`webhook.deliveries` carries
   the first dispatch, replacing V1's inline post-commit attempt).
5. **Cross-language signature verification has no tooling in this repository.** Resolved as **D136**.
6. **Redis is a new dependency for notification-service** — task 4's endpoint-list cache. Additive,
   following merchant-service's `CacheConfig` precedent (D30/D38: cache immutable DTOs, never
   entities, with a type-aware serializer).
7. **"HTTPS-only" endpoints would break every existing local and test webhook sink**, all of which are
   `http://localhost:…`. Gated on a configuration property (default-secure, relaxed in the test/local
   profiles) and documented alongside the SSRF allow-list rather than silently exempted.
8. **D131's webhook-path scenarios are assigned to M18 by the decision log but appear nowhere in
   M18's task list.** `duplicate_webhooks`/`webhook_failure` are already validated, constrained, and
   persisted by sandbox-service (M17.5) with `toEngineScenario()` deliberately returning empty for
   both. Enacting them requires notification-service → sandbox-service, an edge that does not exist
   today. Included in M18.8 rather than left as a silent no-op past its own stated milestone.

**Architectural note: this milestone changes notification-service's charter more than any milestone
has changed a service since M5.** It goes from Kafka-only — no REST layer, no Spring Security, no
Redis, no outbound dependency beyond one HTTP POST — to a service with a public API, an authentication
layer, a cache, a hostile-input threat surface, and a synchronous dependency on sandbox-service. That
is four new failure domains, which is why the decomposition isolates each one and why V1's delivery
path stays live and unmodified through M18.5.

**Decomposition.** Nine independently-committable sub-milestones: **M18.1** schema + domain model (no
behaviour change at all); **M18.2** notification-service's web/security layer + the endpoint management
API + gateway route and `webhooks:manage` scope; **M18.3** `WebhookEventFactory` and the canonical
`evt_` object, written alongside V1's delivery rather than replacing it; **M18.4** `WebhookSigner`,
the signature specification, and cross-language vectors (D136); **M18.5** the SSRF guard and hardened
HTTP client; **M18.6** fan-out, the delivery executor, and the cutover off V1's single-URL path;
**M18.7** the explicit retry schedule, `.retry`/`.dlq`, and auto-disable; **M18.8** replay API,
delivery-log query API, and D131 enactment; **M18.9** legacy-URL adoption, the webhook guide (§9.4),
full docker-compose E2E, and milestone closure. Ordering rationale: schema → API → object shape →
crypto → safety → delivery → durability → visibility → closure, so every dependency points backwards
and the one irreversible step (M18.6's cutover) happens only after all five of its inputs are
independently proven. The two decisions that later milestones inherit permanently — the `evt_` event
vocabulary (M18.3, which M19's Events API projects into) and the signature scheme (M18.4, which M22's
SDKs implement) — each get their own gate ahead of any code that consumes them.

#### M18.1 — Webhook schema and domain model ✅ (2026-07-25)

**Summary.** The `notification` schema gains the four tables §4.5/§4.6 specify, with their entities and
repositories. Deliberately zero behaviour change: nothing reads or writes the new tables yet, V1's
single-URL delivery path is untouched, and `NotificationIntegrationTest` — the test that exercises that
path against a real broker — passes unmodified. This sub-milestone exists so the table shape is
reviewable on its own, while changing it is still free; after M18.6 cuts over, it would be a migration
against live delivery state.

**Files created.** Migration: `notification/V4__webhooks.sql`. **Domain:**
`domain/WebhookEndpoint.java`, `domain/WebhookSubscription.java`, `domain/WebhookEvent.java`,
`domain/WebhookDeliveryAttempt.java`, `domain/EndpointDisableReason.java`, `domain/AttemptOutcome.java`.
**Data access:** `repository/WebhookEndpointRepository.java`, `repository/WebhookSubscriptionRepository.java`,
`repository/WebhookEventRepository.java`, `repository/WebhookDeliveryAttemptRepository.java`.

**Files modified.** None. No existing source file, configuration file, or migration was touched — the
property that makes this sub-milestone's regression claim checkable rather than asserted.

**DB.** `V4__webhooks.sql` creates `webhook_endpoints`, `webhook_subscriptions`, `webhook_events`, and
`webhook_delivery_attempts`. Design points worth recording:

- **`webhook_deliveries` is retained and evolved, not replaced.** §4.5 lists four *new* tables, but M18
  still needs per-`(event, endpoint)` delivery state — status, attempt count, optimistic-lock version —
  which is exactly what V1's `webhook_deliveries` already models. Reusing it keeps M18.7's retry/DLQ
  work on M7's proven shape instead of standing up a parallel concept, and keeps V1's existing DLQ
  regression tests meaningful. `webhook_delivery_attempts` is its per-attempt child. The columns that
  turn it into a fan-out target (`webhook_event_id`, `endpoint_id`, and dropping
  `uq_webhook_deliveries_event_id`) land in **M18.6**, with the writer that needs them — not here,
  where they would break V1's `findByEventId` returning an `Optional`.
- **`mode` is NOT NULL on `webhook_endpoints` and `webhook_events`**, deliberately unlike the nullable,
  never-coerced `mode` on `email_log` and `webhook_deliveries` (D126). Those are *recorders*, faithfully
  writing back whatever an event declared including nothing. These two are *partitions* in M16.2–16.4's
  sense — a test endpoint receiving a live event is the exact isolation failure M16 exists to prevent,
  and a table queried *by* mode makes a null unqueryable rather than merely unknown. `webhook_events`
  resolves an absent envelope mode to `live` at write time, which is D125's stated consumer semantics.
- **`event_ref` is derived, not random.** The public identifier is `"evt_"` plus the source envelope
  `eventId`'s 32 hex digits. The determinism is load-bearing for M19: audit-service stores the same
  envelope id and must project its rows into this exact shape, and a derived id lets it do so with no
  shared sequence, no coordination, and no lookup back into the `notification` schema.
- **The request is stored verbatim on every attempt** rather than referenced from `webhook_events.data`.
  A retry re-signs with a fresh timestamp and a replay may use a rotated secret, so the bytes genuinely
  differ between attempts; a shared reference would show a merchant something they were never sent.
- **`BLOCKED` is a first-class attempt outcome**, distinct from `FAILED_TRANSPORT`. A merchant whose
  endpoint the egress guard (M18.5) never contacted must be told exactly that, not shown a connection
  error implying we tried.
- **Constraints carry the invariants, not just the entities**: `chk_webhook_endpoints_previous_secret_shape`
  (a rotation window with a secret but no expiry would never lapse),
  `chk_webhook_endpoints_disabled_shape` (an auto-disabled endpoint that is still enabled is a
  contradiction), `chk_webhook_delivery_attempts_status_shape` (a recorded status only makes sense when
  the endpoint actually answered), and `uq_webhook_endpoints_merchant_mode_url` (a second registration
  of the same URL would silently double every delivery — a duplicate-webhook bug the merchant would
  diagnose as a platform fault). The same coherent-shape discipline `V1__init_sandbox.sql` and
  `V4__simulation_overrides.sql` apply, extended to this schema.

**API / Kafka / Redis / infra.** None. No endpoint, topic, cache, compose entry, or Gradle dependency
changed in this sub-milestone.

**Tests.** 14 new, 43 green in the module.
- *Unit, no Spring:* `WebhookEndpointTest` (6) — the dual-secret grace window is usable one second
  before expiry and unusable at it (D120's read-time-comparison shape, no scheduler); a success resets
  the consecutive-failure streak, without which a merely flaky endpoint would eventually be
  auto-disabled as if it were dead; a platform auto-disable records its reason while a merchant disable
  does not; re-enabling clears both the annotations and the streak. `WebhookEventTest` (4) — the public
  id is derived deterministically and stably from the source event id, and an absent envelope mode is
  read as `live` (D125). `WebhookSubscriptionTest` (3) — wildcard matches everything including event
  types that do not exist yet (§4.10: additive changes are never breaking), and matching is exact
  rather than prefix-based, so a `payment.authorized` subscription never leaks `payment.captured`.
- *Integration, Testcontainers Postgres:* `WebhookSchemaIntegrationTest` (8) — the migration applies on
  top of the existing three and all four entities validate against it (Hibernate runs `ddl-auto:
  validate`, so a mapping mismatch would fail context startup); endpoints resolve only within their own
  merchant *and* mode, with a real id from the other mode returning empty so callers surface 404 not 403
  (D102); the same URL is registrable in both modes but not twice within one; deleting an endpoint
  cascades to its subscriptions at the FK rather than in application code; one internal event yields at
  most one canonical event (D2's dedup gate, enforced by the database); attempts round-trip with their
  jsonb request/response intact and in attempt order; and — via raw SQL, bypassing the entities
  entirely — the schema rejects a bad mode, an enabled-but-auto-disabled row, an unexpiring rotation
  window, and an attempt whose outcome and status disagree, proving the database is a real backstop and
  not merely a mirror of the Java guards.
- Postgres only, with `spring.kafka.listener.auto-startup=false`: this sub-milestone adds no messaging,
  and standing up a broker to test a schema would buy nothing but runtime. `NotificationIntegrationTest`
  remains the test that exercises the real Kafka pipeline and is deliberately left untouched.

**Verification.** What was actually run, in order:
1. `.\gradlew.bat :notification-service:test` — **43 tests, all passing**, including the 6 pre-existing
   `NotificationIntegrationTest` cases (real Kafka + real Postgres + a real HTTP sink) that constitute
   V1's delivery-path regression gate.
2. `.\gradlew.bat clean build` — `BUILD SUCCESSFUL`, but **not** an honest regression signal on its own:
   Gradle's build cache (`org.gradle.caching=true`) restored the unchanged modules' test results rather
   than re-executing them, so nothing outside notification-service actually ran. Recorded because
   reporting a cache-restored green as an executed green is exactly the kind of claim this project's
   "verify, never assume" rule exists to prevent.
3. `.\gradlew.bat test --rerun-tasks` — forced genuine re-execution. Failed, but **not on this
   milestone's code**: `gateway-service` and `analytics-service` both raised
   `IllegalStateException at DockerClientProviderStrategy` — Testcontainers could not obtain a Docker
   client. Root cause was host contention, not a regression: `docker ps` showed 20 running containers
   (this platform's full 13-container compose stack, plus two unrelated projects' stacks), and Gradle's
   `org.gradle.parallel=true` had several Testcontainers-backed modules competing for the daemon at once.
4. `.\gradlew.bat :gateway-service:test :analytics-service:test --rerun-tasks --no-parallel
   --max-workers=1` — both modules **fully green** on re-execution (27 + 18 tests), confirming (3) was
   environmental.
5. Aggregated every module's JUnit XML directly rather than trusting the task summary (M17.8's own
   discipline): **372 tests, 0 failures, 0 errors, 0 skipped** across all 12 modules.
6. **Applied the migration against the live, populated compose database**, not only against an empty
   Testcontainers one — the distinction the "verify, never assume" rule exists for, since Testcontainers
   only ever proves a migration works on an empty schema. `docker compose -f docker-compose.infra.yml -f
   docker-compose.yml up -d --build notification-service` rebuilt the image (also re-confirming the
   Dockerfile still builds this module — the gap M17.8 discovered had gone unnoticed for seven
   sub-milestones because nothing ran a Docker build) and restarted the container against the existing
   database. `notification.flyway_schema_history` then showed **V1–V4 all `success = t`**, `\dt
   notification.*` showed the four new tables alongside the three existing ones, and the container
   reported `healthy`. Pre-existing data was untouched: 10,459 `email_log` rows, 17
   `webhook_deliveries`, and 10,444 `processed_events` (accumulated across M14's load tests and M17.8's
   E2E) all survived, with the four new tables correctly empty — nothing writes them yet.

**Environment note for future milestones.** `--rerun-tasks` across the whole monorepo while the
docker-compose stack is up is unreliable on this machine — too many Testcontainers modules contend for
one Docker daemon under `org.gradle.parallel=true`. Either stop the compose stack first, or add
`--no-parallel --max-workers=1` to forced full-suite re-runs. Not a code defect and not worth a §14
entry, but it will recur and cost time otherwise.

**Decisions.** D133–D136 recorded in §11 from the pre-implementation review; none new to this
sub-milestone itself.

**Known deviation from convention.** `WebhookSchemaIntegrationTest` imports
`org.testcontainers.containers.PostgreSQLContainer`, which Testcontainers 2.x deprecates in favour of
`org.testcontainers.postgresql.PostgreSQLContainer`, and so compiles with a deprecation note. Every
existing integration test in this repository imports the same class; diverging in one new file would
trade a warning for an inconsistency. Flagged rather than silently accepted — a repository-wide import
migration is the right shape for this, and belongs to a stabilization pass, not to M18.

**Remaining M18 work.** M18.2 — notification-service's web and security layer (sandbox-service's
`InternalContextFilter`-only shape, D133), the `/v1/webhook_endpoints` management API with `whsec_`
secrets revealed once and dual-secret rotation, the gateway route, and `webhooks:manage` added to
`ApiKeyAuthenticationWebFilter.requiredScopeFor` — which is the first extension of that mapping since
M15 left it as an explicitly-anticipated known issue (§14).

#### M18.2 — notification-service's web/security layer and the endpoint management API ✅ (2026-07-25)

**Summary.** notification-service stops being Kafka-only. It gains Spring Security (for
`InternalContextFilter` and nothing else), its first REST controller, and the public
`/v1/webhook_endpoints` management API: register, list, read, update, delete, and rotate the signing
secret — all merchant- and mode-scoped from the verified context. The gateway routes to
notification-service for the first time, and `webhooks:manage` becomes the first scope beyond
`payments:*` that the platform actually enforces. V1's delivery path is still untouched: nothing reads
the endpoint table yet.

**Files created.** `config/SecurityConfig.java`, `config/WebhookProperties.java`;
`security/SecurityErrorWriter.java`, `security/RestAuthenticationEntryPoint.java`,
`security/RestAccessDeniedHandler.java` (all three byte-for-byte the sandbox-service/payment-service
shape, so every service's security-failure envelope stays identical); `domain/WebhookEventType.java`;
`service/WebhookSecretGenerator.java`, `service/WebhookEndpointService.java`;
`dto/CreateWebhookEndpointRequest.java`, `dto/UpdateWebhookEndpointRequest.java`,
`dto/WebhookEndpointResponse.java`, `dto/WebhookEndpointCreatedResponse.java`;
`mapper/WebhookEndpointMapper.java`; `web/WebhookEndpointController.java`.

**Files modified.** `notification-service/build.gradle.kts` (+`spring-boot-starter-security`,
+`spring-boot-webmvc-test`; the module javadoc's "deliberately no REST API, no Spring Security" note
rewritten rather than left contradicting the code). `notification-service/application.yaml`
(+`paymentflow.webhooks.*`; the `internal-context` comment corrected — it said the filter "always
no-ops here", which stops being true the moment a route exists).
`repository/WebhookSubscriptionRepository.java` (+`findByEndpointIdIn`, the list view's N+1 avoidance).
`gateway-service/application.yaml` (+`notification-service-webhook-endpoints` route,
+`paymentflow.services.notification.base-uri`). `ApiKeyAuthenticationWebFilter.java`
(+`webhooks:manage` in `requiredScopeFor`). `docker-compose.yml` (gateway gains
`PAYMENTFLOW_SERVICES_NOTIFICATION_BASE_URI`; notification-service gains
`PAYMENTFLOW_WEBHOOKS_REQUIRE_HTTPS: "false"`).

**API added (`/v1` tier — a public promise).** `POST /v1/webhook_endpoints` (201, the only response
carrying a raw `whsec_`), `GET /v1/webhook_endpoints`, `GET /v1/webhook_endpoints/{id}`,
`PATCH /v1/webhook_endpoints/{id}`, `DELETE /v1/webhook_endpoints/{id}` (204),
`POST /v1/webhook_endpoints/{id}/rotate_secret`. No `/api/v1` mirror (D133).

**Gateway.** One new route, direct passthrough (no `/api/v1` controller exists to rewrite onto), with
`RemoveRequestHeader=X-PF-Mode` for the same defence-in-depth reason `/v1/payments` has it — mode is
key-bound via the signed context and must never be client-selectable, so a test key physically cannot
manage a live endpoint.

**Security.** notification-service's first `SecurityFilterChain`: `InternalContextFilter` registered
*inside* it via `addFilterBefore(…, AuthorizationFilter.class)` (D124), `anyRequest().authenticated()`,
actuator health/info/prometheus/metrics public. No OAuth2 resource server at all (D133) — this service
never sees a JWT. Unlike sandbox-service it needs no `permitAll()` carve-out, because every controller
method returns a plain value rather than a `CompletableFuture`, so Spring Security's async-dispatch gap
(the reason sandbox-service's `/internal/v1/**` is `permitAll`) does not arise.

**Design points.**
- **The canonical event vocabulary is defined here, not in M18.3**, because the management API must
  validate subscriptions against it — an API that accepts `payment.authorised` and silently delivers
  nothing would be this API's single most likely self-inflicted integration failure. `WebhookEventType`
  is the closed vocabulary (7 values, `payment.<past-tense>` in lower snake_case) plus the
  internal↔canonical mapping M18.3's factory consumes. Deliberately not payment-service's internal
  strings: D4 says a consumer must not adopt a producer's internal names as its own public contract.
- **The URL is immutable after registration.** It is half of the endpoint's identity
  (`uq_webhook_endpoints_merchant_mode_url`); repointing it silently would leave a delivery history
  attached to a destination that never received any of it.
- **A wildcard collapses redundant explicit subscriptions** rather than erroring: `["*",
  "payment.authorized"]` stores just `["*"]`, so the stored set never overstates what the endpoint is
  actually selected by.
- **Embedded credentials in a URL are refused, not redacted.** `http://user:pass@host/hook` would be
  written verbatim into `webhook_delivery_attempts.request_url` on every attempt; refusing at
  registration is the only point where that is cheap.
- **`maxEndpointsPerMode` (16)** — not in §5's task list, added because fan-out cost is linear in it
  for every event (M18's own risk table), and a bound set at registration is free where one discovered
  under load is an incident.

**Tests.** 61 green in notification-service (18 new), 31 in gateway-service (4 new).
- `WebhookEndpointApiIntegrationTest` (14): the secret is returned once and only its SHA-256 is
  persisted (asserted against `OpaqueTokenGenerator.sha256Hex`, not merely "a secret came back");
  cross-mode and cross-merchant reads, patches, and deletes all 404 rather than 403 (D102); the same
  URL registers in both modes but conflicts within one; an unknown event type is rejected *with the
  documented vocabulary in the message*; an empty subscription list is rejected; a wildcard collapses
  redundancy; embedded credentials and relative URLs are rejected; PATCH leaves unsent fields intact;
  disable/re-enable round-trips and re-enabling zeroes the failure streak; rotation issues a new secret
  while keeping the old hash usable within its grace window; an unsigned request is 401; and a request
  whose signature was computed for `test` but whose header claims `live` is 401 — the mode in a signed
  context cannot be edited in flight.
- `WebhookEndpointHttpsPolicyIntegrationTest` (4): the production `require-https=true` setting rejects
  `http://` and `file://`, accepts `https://`, and treats an uppercase `HTTPS://` scheme as valid
  (RFC 3986 says scheme comparison is case-insensitive; a case-sensitive check would reject a
  legitimate URL for a reason no merchant could guess). **A separate class deliberately**, because the
  main suite must run with the rule relaxed — leaving the production default as the one branch no test
  exercises would have created exactly the kind of gap this milestone exists to close.
- `ApiKeyAuthenticationIntegrationTest` (+4): a `webhooks:manage` key reaches the new route with a
  signed context, on both the bare path and a nested one (the bare-path case is what M15's
  `RewritePath` bug was caught on, so it is asserted explicitly rather than assumed); a
  `payments:write` key is refused with `INSUFFICIENT_SCOPE`; and a `webhooks:manage` key is refused
  on `/v1/payments`. The two directions together prove the mapping is a real per-route decision rather
  than a check that happens to pass for whichever key is tried first.

**A test that was written wrong and corrected before it could mislead.** The HTTPS case was first
written inside the main suite as `aPlainHttpEndpointIsRejectedWhenHttpsIsRequired` while that suite
runs with `require-https=false` — so it asserted `201 Created` under a name claiming rejection. It
would have passed forever while testing the opposite of its name. Split into its own
properties-overriding class instead. Recorded because a green test asserting the inverse of its own
name is worse than no test, and the only reason it was caught is that the assertion had to be written
to match the configuration rather than the intent.

**Decisions.** None new — D133 (no `/api/v1` mirror, no OAuth2 resource server) is applied here rather
than decided here.

**Remaining M18 work.** M18.3 — `WebhookEventFactory`, the canonical `evt_` object written from
`payment.events` alongside V1's existing delivery (dual-write, V1 still authoritative).

#### M18.3 — `WebhookEventFactory` and the canonical `evt_` event object ✅ (2026-07-25)

**Summary.** The platform's internal event vocabulary becomes a public one. Every consumed
`payment.events` message now also writes a canonical, merchant-facing `webhook_events` row in the same
transaction — a dual-write with no behaviour change: V1's single-URL delivery is still the only thing
that delivers, and still delivers the internal envelope. M18.6 is the cutover. Writing the canonical
event a sub-milestone before anything reads it means that cutover changes a *reader* only, and that the
event shape can be inspected against real traffic before any merchant receives one.

**Files created.** `event/CanonicalPaymentObject.java`, `event/WebhookEventBody.java`,
`service/WebhookEventFactory.java`.

**Files modified.** `service/NotificationService.java` (+`WebhookEventFactory` dependency, one call
inside the existing transaction block; javadoc explains the dual-write and why it is temporary).
`domain/WebhookEvent.java` (`resolveMode` made public — the body must show the same resolved mode the
row stores). Tests: `NotificationServiceTest` (constructor), `NotificationIntegrationTest` (+2 cases).

**The public event contract, decided here.**
- **Envelope:** `{id, object:"event", type, apiVersion, created, mode, data:{object:{…}}}`.
- **`data` wraps `object`** rather than holding the resource directly. Redundant today, deliberately:
  it is the seam that lets a later revision add `previousAttributes` as a sibling without changing the
  *type* of `data`, which §4.10 would classify as breaking.
- **`CanonicalPaymentObject` is a translation, not a passthrough.** `PaymentNotificationEventPayload`
  carries `merchantContactEmail` and `merchantWebhookUrl` — routing fields D43 embedded for this
  platform's own consumers. Serializing the internal payload directly would echo a merchant's contact
  email into every webhook body delivered to whatever endpoint happens to receive it. There is a test
  asserting neither value appears in a serialized body.
- **`object` discriminators** on both the envelope (`"event"`) and the resource (`"payment"`), so a
  client deserializing a heterogeneous stream branches on a field rather than on the event name.
- **camelCase**, matching every other response this platform emits. M21 owns the contract freeze; this
  was not the milestone to introduce a second naming convention.
- **The stored `apiVersion` is the platform's current one, not the receiving endpoint's pin.**
  Per-endpoint pinning (§5/M18 task 3) is a *rendering* concern for M21, once more than one revision
  exists: one stored event transformed at delivery time is what lets endpoints on different pins share
  a single canonical record. Storing it pre-transformed per endpoint would mean N rows for one
  occurrence, which `uq_webhook_events_source_event_id` exists to forbid.

**A finding with consequences for M18.4: Postgres normalizes `jsonb` on round-trip.** An integration
assertion written as a byte-exact substring match on the stored `data` failed — Postgres returned
`{"id": "…", "mode": "test", …}` with its own key order and spacing, not the bytes Jackson wrote. The
test was wrong, but the underlying fact matters far more than the test did: **the delivered body must
never be assembled by splicing the stored `jsonb` text**, or the signed bytes would depend on
Postgres's formatter and could differ between the original attempt and a retry read back from the
database. `WebhookEventFactory.serialize` therefore parses `data` into a `JsonNode` and re-serializes
the whole body through Jackson, making the output a function of the data alone — identical on every
attempt, on every node, after any round trip. That is precisely the property a receiver re-computing
the HMAC over the body it received depends on, so it is asserted directly
(`serializingIsStableSoTheSignatureCoversWhatIsDelivered`). Had this surfaced in M18.6 instead, it
would have presented as intermittently invalid signatures on retries only.

**Tests.** 70 green in the module (9 new).
- `WebhookEventFactoryTest` (7, no Spring, no database): all seven internal payment types map to their
  canonical names; an unmapped internal type (`ApiKeyIssued`, and an invented future one) yields empty
  and writes nothing rather than raising — a future internal event must be addable without
  notification-service rejecting it; redelivery of one internal event returns the same `evt_` and saves
  exactly once (D2); an absent envelope mode resolves to `live` in *both* the row and the body; the
  body carries the documented envelope with a nested `data.object`; the body leaks neither the
  merchant's contact email nor their webhook URL; and serialization is stable across calls.
- `NotificationIntegrationTest` (+2, real Kafka + real Postgres): the canonical event is produced by
  actually consuming a `payment.events` message — with `evt_` matching the derived form, the canonical
  type, the resolved mode, and a parsed `data.object` — while V1's delivery still reaches `DELIVERED`
  unchanged; and a non-payment internal type is email-logged as before with no `evt_` minted.

**Decisions.** None new. The event vocabulary itself was defined in M18.2 (`WebhookEventType`) because
the management API had to validate against it; M18.3 is where it is consumed.

**Remaining M18 work.** M18.4 — `WebhookSigner`, the signature specification, and the cross-language
test vectors (D136).

#### M18.4 — `WebhookSigner`, the signature specification, and cross-language vectors ✅ (2026-07-25)

**Summary.** The `PaymentFlow-Signature` header exists, is specified in prose next to the code that
implements it, and is **proven interoperable by running two independent implementations** — not
deferred to M22 as an assertion. Closes V1 known issue #2 (§2.11) at the algorithm level; M18.6 is
where deliveries start carrying it.

**Files created.** `service/WebhookSigner.java`;
`src/test/resources/signature-vectors/webhook-signature-vectors.json` (5 vectors),
`signature-vectors/verify.js`, `signature-vectors/verify.py`;
`src/test/java/.../service/WebhookSignerTest.java`.

**Files modified.** None.

**The specification (frozen from here).**
```
PaymentFlow-Signature: t=1785758400,v1=<hex>[,v1=<hex>]

  signed_payload = "{t}" + "." + "{raw request body}"
  v1             = lowercase hex HMAC-SHA256(secret, signed_payload)
  secret         = the endpoint's whsec_… value as UTF-8 bytes, prefix included
```
Receivers recompute over the bytes they received, compare in constant time, and **reject a `t` outside
their tolerance window**. Multiple `v1` values may appear: during a rotation window a delivery is
signed with both the current and the superseded secret, so a receiver that has switched and one that
has not both verify. A verifier accepts if any candidate matches.

**Design points.**
- **The timestamp is inside the signed payload (D105), and that is the whole point.** A signature over
  the body alone is replayable forever because nothing binds the message to a moment. Asserted
  directly: a header 301 seconds old fails a 300-second tolerance, and *editing* `t` to move the
  message back into the window also fails, because `t` is signed.
- **Skew is absolute, not "too old".** A timestamp far in the future is equally evidence the header was
  not produced by us for this delivery.
- **`common-lib`'s `InternalContextSigner` is deliberately not reused.** It signs a fixed set of
  pipe-delimited internal fields with a platform-wide secret for a service-to-service hop, and its
  canonical string is an internal detail free to change. This signs an arbitrary body with a
  per-endpoint secret and is a frozen, publicly documented, third-party-implemented contract. Sharing
  an implementation would couple a changeable internal format to an unchangeable external one — the
  same reasoning that keeps `WebhookEventType` from reusing payment-service's internal event names.
  Recorded explicitly because "no duplicated code" would otherwise argue for merging them, and here
  the duplication is the correct call.
- **The `whsec_` prefix is part of the key and is not stripped.** "Strip the prefix before using it as
  the HMAC key" is a plausible misreading that would silently produce a wrong signature for every
  delivery, so the spec states it and a test asserts the two interpretations differ.
- **Verification is non-short-circuiting** across candidate signatures — the loop does not break on a
  match, so work done is independent of which (or whether a) signature matched.

**D136 executed, not merely prepared.** The decision anticipated committing vectors and scripts for
manual running. Both toolchains turned out to be present on this machine (`node v24.14.0`,
`Python 3.14.3`), so the verification was actually performed and its output recorded:

```
=== Node ===                          === Python ===
PASS  minimal_event                   PASS  minimal_event
PASS  realistic_payment_authorized    PASS  realistic_payment_authorized
PASS  unicode_body                    PASS  unicode_body
PASS  empty_body                      PASS  empty_body
PASS  rotated_secret_same_body        PASS  rotated_secret_same_body
PASS  accepts … inside the window     PASS  accepts … inside the window
PASS  rejects a replayed signature    PASS  rejects a replayed signature
PASS  rejects the wrong secret        PASS  rejects the wrong secret
PASS  rejects a tampered body         PASS  rejects a tampered body
All vectors agree (Node). exit 0      All vectors agree (Python). exit 0
```

The vectors were **generated by the Python implementation first**, then the Java signer was asserted
against them — deliberately that direction. Generating them from Java and checking Java reproduces them
would prove only that the signer is deterministic; generating them independently and having Java agree
is what makes the *specification* the thing under test. Both scripts are written from the prose spec
rather than ported from the Java, for the same reason. Neither is wired into the Gradle build (D136):
that would make `node` and `python` prerequisites for building a Java monorepo, to guard a constant
that changes roughly never.

**A vector chosen for a specific failure mode.** `unicode_body` (`café — 日本語 — ₹500`) exists because
a platform that signs in the JVM's *default* charset agrees with itself perfectly and disagrees with
every other language — a bug invisible to any single-language test suite and guaranteed to surface only
once a real integrator sends a non-ASCII description. `empty_body` covers the `"{t}."` edge, where a
naive implementation might omit the separator.

**Tests.** 77 green in the module (7 new). `WebhookSignerTest`: every committed vector matches; the
Unicode vector is called out separately; the header carries one `v1` per active secret and a dual
header verifies under either; the tolerance window is enforced at ±300s and a moved timestamp fails;
tampered bodies fail; six shapes of malformed header return `false` rather than throwing (a receiver
handing us a garbage header must not be able to raise an exception on the delivery path); and the
`whsec_` prefix is proven to be part of the key.

**Decisions.** None new — D105 and D136 are applied here.

**Remaining M18 work.** M18.5 — the SSRF guard and hardened HTTP client, which must exist before
M18.6's executor makes its first call to a merchant-controlled URL.

#### M18.5 — The SSRF guard (`EgressPolicy`) ✅ (2026-07-25)

**Summary.** Webhook delivery is the only place this platform makes an outbound HTTP request to a
destination a *merchant* chose, originating inside the VPC — so an unguarded delivery pipeline is a
request-forgery primitive aimed at the platform's own network and, in AWS, at the instance-metadata
service. `EgressPolicy` is checked immediately before every connect and returns the resolved addresses
so the caller can pin the connection to exactly what was validated. Built before M18.6's executor
exists, so no code path ever calls a merchant URL unguarded, not even briefly.

**Files created.** `egress/EgressPolicy.java`, `egress/EgressDecision.java`;
`src/test/java/.../egress/EgressPolicyTest.java`.

**Files modified.** `config/WebhookProperties.java` (+`allowedHosts`, `connectTimeout`, `readTimeout`,
`maxResponseBytes`), `config/WebhookClientConfig.java` (+`HostResolver` bean),
`notification-service/application.yaml` (+the four properties, `allowed-hosts: []`).

**What it refuses**, each entry independently justified in the class javadoc rather than as an
undifferentiated block-list: non-HTTP(S) schemes; loopback; link-local (including
`169.254.169.254`); RFC1918 private ranges and IPv6 unique-local `fc00::/7`; wildcard/any/multicast;
carrier-grade NAT `100.64.0.0/10` and `0.0.0.0/8` — neither of which `java.net` classifies as site-local
or any-local, so both would otherwise pass; **IPv4-mapped and IPv4-compatible IPv6** (`::ffff:127.0.0.1`);
and embedded credentials.

**Two properties that are the actual defence, not the list.**
1. **Every resolved address is checked, not the first.** A hostile DNS record can answer with one public
   and one private address, and which one comes first is the attacker's choice. `getAllByName`, and a
   loop.
2. **The decision carries the resolved addresses back.** Re-resolving the hostname at connect time
   would reopen a DNS-rebinding window between check and request — the check would pass on the public
   answer and the connection would land on the private one. M18.6 connects to what was validated.

**`java.net`'s own predicates are not sufficient**, which is worth recording because it is the trap:
`isLoopbackAddress()` and `isSiteLocalAddress()` both return `false` for `::ffff:127.0.0.1` and
`::ffff:10.0.0.1`. An implementation built on those predicates alone looks thorough, passes a casual
review, and is bypassable with a five-character prefix. `EgressPolicy` extracts the embedded IPv4
address from a mapped/compatible IPv6 address and re-checks it on its own; `isSiteLocalAddress()` also
covers only the deprecated `fec0::/10` for IPv6, so `fc00::/7` is matched explicitly.

**The allow-list is the single deliberate exemption.** Empty by default and in every deployed
environment; local compose and the integration suite populate it so `localhost` sinks are reachable.
Exempting a named host is a visible, configured act — weakening `EgressPolicy` itself for local
convenience would have weakened it in production too, and that is the shape this decision was
deliberately avoiding.

**Tests.** 109 green in the module (32 new). `EgressPolicyTest` is table-driven with **DNS injected**,
which is what makes it exhaustive: a name resolving to a private address, or to one public *and* one
private, is expressible without owning a domain or depending on the internet from a unit test. 16
hostile URLs; 4 IPv6-wrapped IPv4 addresses; split-horizon DNS; 4 non-HTTP schemes; HTTPS enforcement;
embedded credentials; unparseable/hostless/relative URLs; unresolvable hosts refused rather than
attempted; the allow-list exempting exactly one host and nothing else; and the allow-list being empty
by default.

**Decisions.** None new.

**Remaining M18 work.** M18.6 — fan-out, the delivery executor, and the cutover off V1's single-URL
path. **Opens with a defect fix**: see D137 below.

#### M18.6 — Fan-out, the hardened delivery executor, and the cutover ✅ (2026-07-25)

**Summary.** The cutover. One canonical event now produces N deliveries — one per enabled, subscribed
endpoint — each dispatched through `webhook.deliveries` (D134), signed, egress-checked, and recorded as
a full delivery-log attempt. V1's `merchantWebhookUrl` delivery path is retired. Opens with the
correction of a defect this milestone introduced in M18.2 (**D137**).

**A defect found and fixed: `whsec_` secrets cannot be SHA-256 hashed.** §4.9 states that every secret
the platform holds — `sk_`, `whsec_`, refresh tokens — is "stored only as SHA-256". M18.2 implemented
that literally. It is correct for the other two, which the platform only ever *verifies* (hash what was
presented, compare). It is **not implementable for a webhook signing secret**, because the platform must
*use* it as an HMAC key on every delivery: a one-way digest can only produce signatures the merchant —
who holds the original — could never reproduce. Every delivery would have carried an unverifiable
signature, and the failure would have surfaced not as a test failure but as integrators reporting that
verification "just doesn't work". Fixed as **D137**: encrypted at rest (AES-256-GCM) rather than
hashed. Found while wiring the executor, i.e. at the first moment the secret was actually *used* rather
than merely stored — which is exactly why the sub-milestone that uses a thing should not be far from the
one that stores it.

**Files created.** `crypto/WebhookSecretCipher.java`; `service/WebhookFanOutService.java`,
`service/WebhookDeliveryExecutor.java`, `service/WebhookDeliveryProcessor.java`,
`service/WebhookDispatcher.java`, `service/LegacyEndpointAdopter.java`;
`listener/WebhookDeliveryListener.java`; migration `V5__webhook_delivery_fanout.sql`;
`src/test/java/.../TestWebhookProperties.java`.

**Files modified.** `domain/WebhookEndpoint.java` (secret columns become `*_encrypted`),
`domain/WebhookDelivery.java` (+`webhookEventId`, `endpointId`, `nextAttemptAt`,
`replayedFromDeliveryId`, `forEndpoint`/`replayOf` factories; `eventId`/`webhookUrl`/`payload` become
nullable), `service/WebhookEndpointService.java` (encrypt instead of hash),
`service/NotificationService.java` (**the cutover**), `config/KafkaTopicConfig.java` (+3 topics),
`config/WebhookProperties.java`, `config/WebhookClientConfig.java`, `application.yaml`.
Tests: `NotificationIntegrationTest` and `NotificationServiceTest` **rewritten**;
`WebhookEndpointApiIntegrationTest`, `WebhookEndpointTest`, `WebhookSchemaIntegrationTest`,
`EgressPolicyTest`, `WebhookEventFactoryTest` updated.

**DB.** `V5__webhook_delivery_fanout.sql`: drops the two hash columns and adds
`signing_secret_encrypted`/`previous_secret_encrypted` (no conversion is possible — a digest cannot be
turned back into its input, so re-issuance is the only path; safe because the table has never existed in
a deployed environment, and the migration says so rather than leaving it to be discovered);
`webhook_deliveries` gains `webhook_event_id`, `endpoint_id`, `next_attempt_at`,
`replayed_from_delivery_id`; `uq_webhook_deliveries_event_id` is **dropped** — one row per event is
precisely what fan-out breaks — and replaced by a partial unique index on
`(webhook_event_id, endpoint_id) where replayed_from_delivery_id is null`, partial because a replay is
deliberately a second delivery of the same event to the same endpoint.

**Kafka.** Three new topics (D106): `webhook.deliveries`, `.retry`, `.dlq`. Six partitions on the first
two rather than three, because `WebhookDispatcher` keys by **endpoint id** — partitions are what let
deliveries to different endpoints proceed in parallel while deliveries to one endpoint stay strictly
ordered, so a merchant sees their own events in the order the platform produced them and one busy
endpoint cannot reorder another's.

**Design points.**
- **D134 realised**: the `payment.events` consumer now writes rows and publishes, instead of making N
  blocking HTTP calls on its own thread.
- **The JDK `HttpClient`, not `RestClient`.** Redirect policy (`Redirect.NEVER`), per-request timeouts,
  and reading the response through a *bounded* stream are all first-class there and awkward or
  unavailable through `RestClient`. `BodyHandlers.ofString()` would buffer a hostile gigabyte response
  before any cap could apply — the difference between refusing one and being defeated by one.
- **Redirects are never followed.** A `302` to `169.254.169.254` is the standard way to walk straight
  through an egress check that validated only the original URL; following them would make M18.5
  decorative.
- **The body is rendered and signed per attempt**, not snapshotted at fan-out. A retry therefore carries
  a fresh timestamp and a fresh signature, which is what keeps the receiver's replay window meaningful
  across a ~24-hour schedule.
- **`TransactionTemplate`, not `@Transactional`, in `WebhookDeliveryProcessor`.** `process()` calls its
  own read and write methods, and a self-invocation does not pass through the Spring proxy — the
  annotations would have been silently inert and the "no network call inside a transaction" guarantee
  would have been the opposite of what the code claimed. Caught while writing it; recorded because it is
  the kind of defect that produces no symptom until a connection pool is exhausted under load.
- **A disabled endpoint is skipped, not failed.** Disabling between fan-out and dispatch is the merchant
  asking us to stop; counting it against the endpoint's failure streak would auto-disable something
  already disabled and corrupt the signal M18.7 depends on.

**D135's position moved from M18.9 to here, and that is the point.** M18.6 is the commit where fan-out
replaces the legacy path. From it onward, a merchant who configured `merchants.webhook_url` and has not
registered an endpoint receives **nothing** — silently, because "no subscribed endpoints" is a
legitimate outcome indistinguishable from "not subscribed". Deferring adoption by three sub-milestones
would have meant shipping a silent regression for every existing integration and then fixing it.
`LegacyEndpointAdopter` runs immediately *before* fan-out, inside the same transaction, so the very
event that triggers adoption is also delivered by it. The decision (D135) is unchanged; only its
position moved, and the cutover is what created the need.

**Tests.** 114 green in the module (12 changed/new, net).
- `NotificationIntegrationTest` **rewritten** (12 cases, real Kafka + Postgres + JDK sinks): three
  endpoints with different subscriptions receive exactly the right two (M18's own completion
  criterion); **every delivery is signed and verified by recomputing over the received bytes with the
  merchant's secret — and a wrong secret is asserted *not* to verify**, without which the first
  assertion proves nothing; the delivered payload is the canonical `evt_` object and contains neither
  `merchantContactEmail` nor the internal envelope; a test-mode event never reaches a live endpoint; a
  disabled endpoint receives nothing; every attempt is logged with its real request headers, body,
  status, and duration; a legacy URL is adopted on first event and subscribed `*`; a merchant with
  registered endpoints is **never** augmented by the legacy column; redelivery duplicates nothing; a
  non-merchant-facing internal type is emailed but never delivered; a malformed message does not kill
  the consumer; and mode is recorded on the email, the event, and the delivery.
- `NotificationServiceTest` **rewritten** (7): fan-out orchestration, including two ordering assertions
  that matter — adoption strictly *before* fan-out, and dispatch strictly *after* the transaction
  commits (a message published inside a transaction that then rolls back would point at a row that does
  not exist).
- `TestWebhookProperties` added because `WebhookProperties` grew a field in three consecutive
  sub-milestones and each time broke every unrelated test that constructed one. Centralising it also
  means no test carries a long list of values it does not care about — which is what was making the
  values it *does* care about invisible.

**Decisions.** **D137** (see §11).

**Remaining M18 work.** M18.7 — the explicit retry schedule on `webhook.deliveries.retry`, dead-lettering,
and endpoint auto-disable.

#### M18.7 — The explicit retry schedule, dead-lettering, and auto-disable ✅ (2026-07-25)

**Summary.** A failing delivery now walks a **published** schedule — 0s, 5s, 30s, 2m, 10m, 1h, 6h, 12h:
eight attempts over ~19h12m — then dead-letters. An endpoint failing 20 consecutive times across
distinct events is switched off and the merchant emailed. Two real defects were found by the
integration test, one of them serious.

**Files created.** `service/WebhookRetrySchedule.java`, `service/WebhookRetryRelay.java`; migration
`V6__webhook_endpoint_contact_email.sql`; `service/WebhookRetryScheduleTest.java`,
`WebhookRetryAndAutoDisableIntegrationTest.java`.

**Files modified.** `service/WebhookDeliveryProcessor.java` (scheduling, dead-lettering, auto-disable),
`domain/WebhookEndpoint.java` (+`contactEmail`), `repository/WebhookDeliveryRepository.java`
(+`findDueForRetry`, +the M18.8 query methods), `service/WebhookEndpointService.java`,
`service/LegacyEndpointAdopter.java`, `web/WebhookEndpointController.java`,
`service/NotificationService.java`, `NotificationServiceApplication.java` (+`@EnableScheduling`),
`application.yaml`.

**A fixed table, not exponential backoff — deliberately unlike V1's D46.** Two merchant-facing reasons:
the schedule is **published**, so an integrator can say when the next attempt lands and how long they
have to fix an endpoint before it dead-letters, which is impossible to state honestly about a randomised
backoff; and the intervals are chosen to cover the failure modes that actually happen (a blip, a deploy,
a short outage, a long one) rather than to be a smooth curve. Jitter is absent for the same reason —
deliveries are already spread across endpoints by the dispatcher's partition keying, and an unpredictable
schedule is worth less than the thundering-herd protection jitter would buy at this scale. If M28
measures a herd problem, jitter is a bounded addition to one class.

**A polling relay, not a delayed message.** Kafka has no per-message delay. The alternatives were parking
a consumer thread on a `sleep` (V1's approach — defensible for a 30-second backoff, untenable for a
six-hour one: it would hold a partition assignment for hours and stall every other delivery on it) or a
tier of delay topics per interval. Polling `next_attempt_at` is the same shape payment-service's
`OutboxRelay` (D3) and sandbox-service's `ScheduledOutcomeRelay` (M17.6) already use here, and it
survives a restart for free — a sleeping thread does not. It is also why `next_attempt_at` is a column:
a delivery's next attempt has to be visible in the delivery log and durable across a deploy.

**Two defects found by the integration test, not by review.**
1. **The auto-disable notification was rolling back the delivery bookkeeping.** The email was sent
   inside the same transaction that recorded the attempt and scheduled the retry, with a `null`
   recipient — notification-service has no merchant lookup (D43) and nothing on the endpoint carried an
   address. `email_log.recipient_email` is `NOT NULL`, so the insert failed, and **the whole transaction
   rolled back with it**: the failed attempt and the scheduled retry were never persisted, so a dead
   endpoint retried forever without ever advancing toward dead-lettering. Fixed in both halves — the
   address is now stored on the endpoint (`V6`, sourced from the verified `MerchantContext` at
   registration per D118, or the event payload at legacy adoption per D43, so still no new dependency),
   and the notification is sent **after** the transaction commits. A notification is a side effect of a
   state change, never a precondition for recording it.
2. **`@Transactional` on self-invoked methods is inert** (carried over from M18.6 and corrected there):
   `process()` calls its own read and write methods, which do not pass through the Spring proxy.
   Replaced with the explicit `TransactionTemplate` this service already uses.

**Two design corrections made while writing it.** An in-memory `alreadyNotified` set was drafted to
avoid emailing on every attempt of an already-disabled endpoint — discarded, because it does not survive
a restart and grows without bound. The transaction is the only place that can distinguish "this attempt
disabled it" from "it was already disabled", so `record` returns both the result *and* whether it
auto-disabled; the two are carried separately because they coincide (the attempt that exhausts the
schedule can also be the one that crosses the threshold, and a single enum would silently drop one of the
two notifications).

**Tests.** 124 green in the module (10 new).
- `WebhookRetryScheduleTest` (6, pure): the documented schedule is 8 attempts totalling 19h12m35s —
  asserted, so the published promise and the code cannot drift; each completed attempt selects the next
  delay in order; **the exhaustion boundary** (7 done → one retry left, 8 done → none), where an
  off-by-one is the difference between 8 attempts and 9 or between dead-lettering a delivery still owed
  a retry; an empty schedule (deliver once, never retry) is a legitimate configuration that must not
  loop or index negatively; and impossible attempt counts return empty rather than throwing, so a
  corrupted counter cannot take down a delivery worker.
- `WebhookRetryAndAutoDisableIntegrationTest` (4, real Kafka + Postgres + an endpoint that 500s every
  time): a dead endpoint walks the whole schedule and dead-letters with exactly 3 attempts numbered
  1,2,3, all `FAILED_STATUS` with status 500, and **no lingering `next_attempt_at`** (which would have
  the relay re-dispatching it forever); the endpoint is auto-disabled with the right reason and the
  merchant is emailed; a disabled endpoint then **stops consuming the retry budget entirely** (the whole
  point of auto-disable); and a success resets the streak, so a merely flaky endpoint is never disabled
  as though it were dead.

**Decisions.** None new.

**Remaining M18 work.** M18.8 — the replay API, the delivery-log query API, and D131's
`duplicate_webhooks`/`webhook_failure` enactment.

#### M18.8 — Delivery-log query API, manual replay, and D131 enactment ✅ (2026-07-25)

**Summary.** The half of the milestone that makes the other half debuggable. `GET /v1/webhook_deliveries`
and `/{id}` return every attempt with its full request and response; `POST /{id}/replay` re-sends a past
delivery as a distinct new one; and D131's two webhook-path simulation scenarios — defined and stored by
sandbox-service since M17.5, enacted nowhere until now — finally do something.

**Files created.** `dto/WebhookDeliveryResponse.java`, `dto/WebhookDeliveryAttemptResponse.java`;
`mapper/WebhookDeliveryMapper.java`; `service/WebhookDeliveryQueryService.java`;
`web/WebhookDeliveryController.java`; `sandbox/SandboxWebhookScenario.java`,
`sandbox/SandboxScenarioClient.java`; `WebhookDeliveryLogAndReplayIntegrationTest.java`,
`sandbox/SandboxScenarioClientTest.java`.

**Files modified.** `service/WebhookDeliveryProcessor.java` (scenario enactment),
`repository/WebhookDeliveryAttemptRepository.java` (+batch read), `application.yaml`,
`gateway-service/application.yaml` (+route), `ApiKeyAuthenticationWebFilter.java` (scope extended to the
new path).

**API added.** `GET /v1/webhook_deliveries` (paginated, newest first), `GET /v1/webhook_deliveries/{id}`,
`POST /v1/webhook_deliveries/{id}/replay` (201). All `webhooks:manage`, all merchant- and mode-scoped.

**Offset pagination, deliberately — not cursors.** D107 introduces signed cursor pagination in **M19**,
as one decision applied across every public list endpoint at once. Adopting a second convention here,
one milestone early and for one resource, is precisely the drift M19 exists to prevent; §5/M19 also
explicitly retains `PageResponse` for endpoints that predate it. Recorded because "the newest endpoint
uses the older convention" looks like an oversight and is not.

**Replay semantics.** A replay is a **new delivery** with its own attempts, pointing back through
`replayed_from_delivery_id`; the original is never touched. That is load-bearing rather than tidy: a
delivery log that mutates when you replay it cannot answer "what happened the first time", which is
usually the question being asked. The event is re-rendered and re-signed at send time like any other
attempt, so a replay carries a current timestamp and passes a receiver's tolerance window — a replay
reproducing the original signature would be rejected by any correctly implemented receiver, which is
exactly the check §9.4 instructs them to perform. Replaying into a **disabled** endpoint is refused with
an actionable message rather than accepted: silently accepting would create a delivery the processor
skips forever, leaving a `PENDING` row that never resolves and no explanation anywhere.

**D131 enacted, and built to be ignorable.** `SandboxScenarioClient` reads the merchant's active
override from sandbox-service — notification-service's **only** synchronous dependency on another
service. Every failure mode (unreachable, slow, 404, 500, malformed, null scenario, empty body) resolves
to `Optional.empty()`, meaning "behave normally", and **live mode never makes the call at all** — not
because the override would be rejected, but because a live delivery must not depend on sandbox-service
being reachable even to be told no. A simulation feature able to break a real delivery by being
unavailable would be worse than no simulation feature, and would invert D103's whole point about sandbox
being advisory. The two scenarios:
- `duplicate_webhooks` sends a genuine second request with its own signature and its own logged attempt.
  Logging it matters: a duplicate the merchant cannot see is indistinguishable from a platform bug, and
  the point of the scenario is for them to prove their consumer is idempotent on `event.id` (§8.3).
- `webhook_failure` overrides the recorded outcome **after** the real call, not instead of it. The
  endpoint still receives the delivery; what the developer is exercising is this platform's retry
  schedule and their own alerting — not their endpoint's ability to return an error, which they can
  already test by returning one.

The enum is a local two-value copy rather than an import of sandbox's eight-value `SimulationScenario`:
D4's schema-per-service rule applies to an enum crossing a service boundary exactly as it does to an
event payload, and the other six scenarios are none of this service's business.

**Tests.** 141 green in the module (17 new).
- `WebhookDeliveryLogAndReplayIntegrationTest` (10, real HTTP + Postgres): the log returns full request
  and response per attempt including the signature header we sent (a signature is not a secret, and
  comparing it is the entire debugging loop for a verification failure); merchant and mode scoping on
  both the list and the single read; **replay creates a new delivery and the original is byte-for-byte
  unchanged**, with both then visible as distinct rows; replay is permitted even when the original
  succeeded (a merchant whose consumer crashed after 200-ing needs exactly that); replay into a disabled
  endpoint is refused with a message naming the fix; replaying another merchant's delivery is 404; a
  pre-fan-out V1 row cannot be replayed; pagination and ordering; and an unsigned request is 401.
- `SandboxScenarioClientTest` (7, real HTTP stub): both scenarios parse; the request carries the signed
  internal context; an engine-only scenario is ignored; **live mode makes zero calls** (asserted on the
  call counter, not just the return value); all five server-side failure modes resolve to empty; an
  unreachable sandbox resolves to empty; and disabling the integration skips the call entirely.

**Decisions.** None new — D131 is enacted here, D107 is deferred to M19 here.

**Remaining M18 work.** M18.9 — the merchant-facing webhook guide (§9.4), the signature specification as
published documentation, full docker-compose E2E, regression verification, and milestone closure.

#### M18.9 — The webhook guide, live E2E validation, and M18 closure ✅ (2026-07-25)

**Summary.** The merchant-facing guide (§9.4) is written and kept honest by a test; the whole subsystem
is validated on a live docker-compose stack against real signature-verifying receivers; the full suite is
re-executed across every module.

**Files created.** `notification-service/docs/WEBHOOKS.md`; `WebhookDocumentationConsistencyTest.java`.
**Files modified.** `docker-compose.yml` (webhook env for notification-service, `extra_hosts` for
host-machine sinks), `.env.example` (+`WEBHOOK_SECRET_ENCRYPTION_KEY` with its rotation warning),
`application.yaml` (retry schedule → explicit YAML sequence).

**The guide is tested, not just written.** `WebhookDocumentationConsistencyTest` asserts the published
numbers against the **running configuration**: the 8-attempt schedule and every interval in its table,
the 19h12m35s total, the 20-failure auto-disable threshold, the 48h rotation window, the 5s timeout, the
8 KB response cap, the 16-endpoint limit, the signature specification's exact wording, and — the one most
likely to rot — that **every value of `WebhookEventType` appears in the guide**, since adding an event
type is a one-line change and the guide is the only place a merchant can learn the name exists. R10 calls
documentation drift fatal for a developer platform and D115 answers it by executing samples in CI; this
is the same discipline applied to the part available now.

**A real configuration bug the doc test caught.** `retry-schedule: 5s,30s,2m,10m,1h,6h,12h` as a
comma-separated YAML scalar did **not** bind to a 7-element `List<Duration>` — `maxAttempts()` came back
wrong, meaning the platform would have retried once instead of seven times while the guide promised
eight. Nothing else would have noticed: the integration tests override the schedule with their own short
values, so they were exercising a correctly-bound list the whole time and the *default* was the broken
one. Fixed by using an explicit YAML sequence. This is the clearest argument in the milestone for
asserting documentation against live configuration rather than against literals.

**Live E2E on the running stack.** Images rebuilt, all six migrations applied against the **populated**
database (V1–V6 `success = t`), containers healthy. Driven over the real gateway with a freshly
registered user, merchant, and the merchant's own onboarding-issued keys — no test-only bypass. The
receivers were three Node HTTP sinks written from `docs/WEBHOOKS.md` alone, each holding only its own
`whsec_`, recomputing the HMAC over the raw body and enforcing the 300s window — the same check a
merchant would write.

- **Fan-out by subscription** — endpoints subscribed to `payment.authorized`, `*`, and
  `payment.refunded`. The first received 1 event, the second received 2 (`payment.created` +
  `payment.authorized`), the third received **0**. Exactly the milestone's first completion criterion.
- **Signature verified by third-party code** — every delivery reported `verified: true` from the Node
  sinks, with `PaymentFlow-Event-Id` matching the body's `evt_`.
- **SSRF** — `http://169.254.169.254/latest/meta-data` registered successfully (scheme and credentials
  are all registration validates) and was then refused **at the connect boundary**, recorded as
  `BLOCKED` with "The destination resolves to a blocked address range." Distinct from a transport
  failure, as designed.
- **Delivery log** — `GET /v1/webhook_deliveries` returned each delivery with its attempts, outcome,
  response status, and duration.
- **Replay** — `POST /{id}/replay` → 201 with a new delivery id and `replayedFromDeliveryId` pointing at
  the original; re-reading the original showed `attemptCount` unchanged and `replayedFromDeliveryId`
  null. The original really is untouched.
- **Dual-secret rotation, proven on the wire** — after `rotate_secret`, the next delivery's header
  carried **two** `v1` values (confirmed in `webhook_delivery_attempts.request_headers`), and a receiver
  still holding the **rotated-out** secret verified it successfully. A receiver mid-rollout keeps working,
  which is the entire purpose of the window.
- **Mode isolation** — the live key saw **0** endpoints where the test key saw 4, and a cross-mode read
  of a real test endpoint id returned **404**, not 403 (D102).
- **Scope enforcement** — a `pk_test_` key (`payments:read`) on `/v1/webhook_endpoints` → **403**.
- **Secrets at rest** — `select count(*) … where signing_secret_encrypted like 'whsec_%'` returned **0**:
  no row holds a plaintext secret.
- **A false alarm worth recording**: the rotation check initially reported one signature, which looked
  like rotation failing. It was the test reading the sink before the post-rotation delivery arrived —
  the entry it saw was a pre-rotation one. Confirmed against `webhook_delivery_attempts` directly, which
  is why that table exists. Verified, not assumed, in both directions.

**Regression verification.** `.\gradlew.bat build --rerun-tasks --no-parallel --max-workers=1` —
**BUILD SUCCESSFUL in 1h 17m**, with every module's suite genuinely re-executed rather than restored
from the build cache (the trap M18.1 recorded). Aggregated from each module's JUnit XML rather than the
task summary: **482 tests, 0 failures, 0 errors** across all 11 test-bearing modules — notification 149,
payment 101, sandbox 78, gateway 31, merchant 24, common-lib 22, transaction 20, analytics 19,
common-dto 17, identity 12, audit 9. Serial and `--no-parallel` deliberately, per M18.1's own
environment note about Docker contention.

**M18 status: complete.** All nine sub-milestones implemented, verified independently, and validated
together on a live stack. V1 known issue #2 is closed. Four defects were found and fixed during the
milestone (D137's unhashable secret, the auto-disable notification rolling back delivery bookkeeping,
inert `@Transactional` on self-invocation, and the unbound retry schedule), three of which would have
been invisible until a merchant reported them.

---

### M19 — Public Read APIs & Query Surface ✅ (complete, 2026-07-25 – 2026-07-26)

**Objective.** Per §5/M19: build the complete public read surface — payments with rich filtering,
refunds as first-class objects, balance and ledger reads, an events API, and analytics — with
consistent list, pagination, and error semantics across every resource. Closes V1 known issues #3 and
#4 (§2.11): three services with no API at all.

**Repository review (2026-07-25).** Seven places where §5/M19 differs from the repository as it stands
were found before any code was written:

1. **`GET /v1/payments` already exists** and returns offset `PageResponse` (M15 routed it via the
   gateway's `RewritePath` onto `/api/v1/payments`). Moving it to cursors is a public-contract change —
   resolved as **D139**.
2. **`/v1` and `/api/v1` are literally the same handler**, so they cannot return different envelopes.
   Resolved by splitting the controller and removing the rewrite (M19.7).
3. **Task 4's "canonical `evt_` shape M18 defined"** lives in notification-service, which D4 forbids
   audit-service from importing. Resolved as **D140** (extract to `common-dto`).
4. **No refund history exists to back-fill** — only a running total was ever stored. Refund objects
   exist from M19 forward; the accumulator remains authoritative for historical payments.
5. **§4.9's scope vocabulary has no scope** for balance/events/analytics. Extended in M19.7.
6. **`payment_stats_hourly` does not exist** and requires the only write-path change in M19.
7. **`metadata` on webhook endpoints** puts M19 inside notification-service, one milestone after M18
   finished it. Additive column only.

**Decomposition.** Eight sub-milestones: **M19.1** shared pagination primitives; **M19.2** payments
list (metadata, filters, cursors); **M19.3** refunds as a resource; **M19.4** transaction-service's
first web layer; **M19.5** canonical event shape extraction + audit events API; **M19.6** analytics
hourly buckets + query API; **M19.7** gateway routes and scopes; **M19.8** the cross-cutting isolation
sweep, `EXPLAIN` checks, E2E and closure. Ordering: shared primitives → the resource that sets the
conventions → resources that reuse them → the two services whose architecture changes → the gateway
(nothing is publicly reachable until deliberately routed) → the proofs that span every endpoint.

#### M19.1 — `CursorPage`, signed cursors, and `ListQuery` ✅ (2026-07-25)

**Summary.** The primitives every public list endpoint in M19 is built from, in the shared modules so
five endpoints cannot drift apart (task 1 and task 7).

**Files created.** `common-dto`: `dto/page/CursorPage.java`. `common-lib`: `query/Cursor.java`,
`query/CursorCodec.java`, `query/ListQuery.java`, `autoconfigure/QueryAutoConfiguration.java`. Tests:
`CursorPageTest`, `CursorCodecTest`, `ListQueryTest`.
**Files modified.** `common-lib`'s `AutoConfiguration.imports` (+`QueryAutoConfiguration`).

**Design points.**
- **`CursorPage` is a new type, not a replacement for `PageResponse`.** D107 retains offset pagination
  for the internal tier, and rewriting it would change a contract M23's dashboard work has not been
  written against yet.
- **No total count**, deliberately: a cursor page cannot report one cheaply, and computing it would
  mean a second full-table count on every request — the unbounded query M19's own risk table warns
  about. `hasMore` answers the only question a paginating client has.
- **The over-fetch is trimmed in one place.** Callers fetch `limit + 1`; the extra row is what
  determines `hasMore` without a count, and `CursorPage.of` trims it so "did we remember to trim?"
  cannot become a per-endpoint bug.
- **Cursors are signed** (D107, **D138**), keyed on the internal-context secret. The signature is
  defence in depth rather than the isolation boundary — every repository method takes merchant and mode
  from the verified context and ignores what a cursor claims (D101) — but a forged cursor failing
  loudly beats one that silently resolves to an empty page nobody can explain.
- **`(createdAt, id)`, not `createdAt` alone.** Two rows in the same millisecond would make the
  boundary ambiguous, and under load that is not hypothetical.
- **`limit` is clamped, not rejected**, at 100. Failing a request for asking for too much teaches a
  client nothing actionable; a short page plus `hasMore` is self-describing. A *non-positive* limit is
  rejected, because clamping it up to 1 would return a page nobody asked for.

**Tests.** 22 new, all green. `CursorCodecTest` (9): round-trip including millisecond precision; the
cursor is opaque and URL-safe; an edited payload, a cursor from another merchant, a cursor from the
other mode, and a cursor signed with a different secret are each rejected; four shapes of malformed
cursor and one validly-signed-but-unparseable payload return 400 rather than crashing — a garbage query
parameter is not a platform failure. `ListQueryTest` (9): defaults, clamping at and above the ceiling,
non-positive rejection, `fetchSize` always `limit + 1`, cursor binding, and inverted/equal date ranges
rejected. `CursorPageTest` (7): the over-fetch boundary in both directions — an exactly-full page must
not claim `hasMore`, and the cursor must point at the last *returned* row rather than the extra one.

#### M19.2 — Payments: metadata, filters, cursor list ✅ (2026-07-25)
#### M19.3 — Refunds as a first-class resource ✅ (2026-07-25)

**Implemented as one pass, documented separately.** `PaymentResponse.refunds` (the `expand=refunds`
field) couples them at the type level, so building M19.2 alone would have meant shipping a half-wired
field with nothing behind it.

**Files created.** Migrations `V5__payments_read_api.sql`, `V6__refunds.sql`; `domain/Refund.java`,
`domain/RefundStatus.java`; `dto/RefundResponse.java`, `dto/PaymentListFilter.java`,
`dto/RefundListFilter.java`; `repository/RefundRepository.java`; `service/PaymentQueryService.java`;
`web/PaymentV1Controller.java`; `PaymentReadApiIntegrationTest`.
**Files modified.** `domain/Payment.java` (+`metadata`), `dto/PaymentResponse.java` (+`object`,
`metadata`, `refunds`), `dto/CreatePaymentRequest.java` (+`metadata`), `dto/RefundRequest.java`
(+`reason`, `metadata`), `mapper/PaymentMapper.java`, `repository/PaymentRepository.java`,
`service/PaymentService.java`. Four existing test classes updated for the widened signatures.

**DB.** `payments.metadata jsonb not null default '{}'` with a **GIN** index;
`idx_payments_merchant_mode_created (merchant_id, mode, created_at desc, id desc)` replacing M16.2's
`idx_payments_merchant_mode` (that index is this one's leftmost prefix, so keeping both would pay for
two indexes to serve one access pattern); `idx_payments_merchant_mode_status`. New `refunds` table with
`chk_refunds_failure_shape` making "FAILED without a reason" unrepresentable.

**The platform's first native query, and why.** Two things in the list are not expressible in JPQL:
`metadata @> :metadata` (jsonb containment, which is what makes the GIN index usable at all) and
`(created_at, id) < (:at, :id)` (row-wise comparison, which makes the keyset predicate a single index
range scan). The alternatives were a Hibernate function registration or a Criteria/Specification
builder — both more machinery than one readable query, for a filter that will never be anything but
containment. Contained to two repository methods, with the schema qualified explicitly because
Hibernate's `default_schema` does not apply to native SQL.

**Reads are a separate service from writes.** `PaymentQueryService` holds no FSM, no idempotency, no
outbox and no merchant/sandbox client — so the read surface cannot transition a payment, because it
holds nothing that could. M19 adds no new route into the mutation path.

**`refunded_amount_minor` is retained, not replaced.** The M5 FSM reads it to decide `REFUNDED` vs
`PARTIALLY_REFUNDED` — M19 has no business rewriting that — and pre-M19 refunds exist *only* as that
total. New refunds write the row and the accumulator in the same transaction, so they cannot disagree.
**No backfill**: a historical total cannot be decomposed into the refunds that produced it, and
synthesising one object per total would fabricate an id and a timestamp that never existed.

**`expand` is a closed whitelist with one value and no nesting.** M19's testing strategy asks for
"expand depth limits"; the limit here is structural rather than enforced, because a relation that
cannot itself be expanded has no way to form an unbounded tree. An unrecognised `expand` value is
ignored rather than rejected — a client naming a relation this version lacks is exactly the
forward-compatible case §4.10 requires them to tolerate.

**Tests.** 111 green in payment-service (10 new). `PaymentReadApiIntegrationTest` against real Postgres:
merchant/mode scoping; **pagination stable across concurrent inserts** (three rows inserted between
pages, page 2 shares nothing with page 1 — the exact case offset pagination gets wrong); **the keyset
boundary is exact when timestamps collide** (eight rows paged two at a time, each returned exactly
once); every filter narrows and combines; an unmatched status returns nothing rather than everything
(a filter that fails *open* would be far worse); metadata matches by containment, requires all keys,
and never matches `{}`; `created_before` is exclusive; refunds are scoped, filterable, and ordered
oldest-first as a history; and a cross-tenant cursor is refused before reaching the query.

**Remaining M19 work.** M19.4 — transaction-service's first web layer.

#### M19.4 — transaction-service's first web layer: balance and ledger reads ✅ (2026-07-25)

**Summary.** `GET /v1/balance` and `GET /v1/balance_transactions`. transaction-service gains Spring
Security (for `InternalContextFilter` only, D133's shape), a controller, and a query service — closing
V1 known issue #3, which required `psql` to inspect ledger state.

**Files created.** `config/SecurityConfig.java`; `security/{SecurityErrorWriter,
RestAuthenticationEntryPoint, RestAccessDeniedHandler}.java`; `dto/BalanceResponse.java`,
`dto/BalanceTransactionResponse.java`; `service/BalanceQueryService.java`; `web/BalanceController.java`;
`BalanceApiIntegrationTest`.
**Files modified.** `build.gradle.kts` (+security starter, +webmvc-test), `application.yaml` (the
internal-context comment said the filter "always no-ops here", which stopped being true),
`repository/{AccountRepository,LedgerEntryRepository,LedgerTransactionRepository}.java`.

**D42's boundary is preserved by construction, not by convention.** M19's risk table flags this as the
milestone's highest-risk change: giving the ledger a web layer could erode "written only by the Kafka
consumer". The mitigation is that `BalanceQueryService` holds no `LedgerService`, no
`TransactionTemplate`, and no account mutation — there is nothing a future endpoint on this controller
could accidentally reach. Balances are *projected* from the ledger rather than kept beside it, which is
what makes the "totals match a direct psql sum" criterion a property rather than a coincidence.

**A defect found by the integration test, not by review.** The JPQL null-guard idiom
`(:createdAfter is null or e.createdAt >= :createdAfter)` fails outright on Postgres —
*"could not determine data type of parameter $3"* — because Postgres cannot infer a bind parameter's
type from `? is null` alone. payment-service's list avoids it with explicit `cast(:x as …)` in native
SQL; here the fix is **sentinel bounds** (`Instant.EPOCH` / `9999-12-31`) so every parameter carries an
unambiguous type and the query stays JPQL. `Instant.MAX` would have been the obvious sentinel and is
outside `timestamptz`'s range, so the bound is chosen deliberately. The same fix is applied in M19.5.

**Tests.** 27 green in the module (7 new). `BalanceApiIntegrationTest`: pending and available come from
genuinely different accounts; scoping across merchant *and* mode; a merchant with no activity gets an
empty balance rather than a 404 (having no balance is a fact, not a missing resource); **the platform's
own clearing account never appears** in a merchant's balance or ledger — structurally, since its owner
is null; entries carry their payment and event type; pagination returns every entry exactly once; and
**the reported balance equals a direct SQL sum over the ledger entries**, which is meaningful only
because the balance is projected rather than stored.

#### M19.5 — Canonical event shape extraction and the Events API ✅ (2026-07-25)

**Summary.** `GET /v1/events` and `/v1/events/{id}`, served by audit-service from `audit_log`, in the
same canonical `evt_` shape M18 defined for webhook bodies. Closes half of V1 known issue #4.

**Files created.** `common-dto`: `dto/event/CanonicalEventType.java`. audit-service:
`config/SecurityConfig.java`, `security/*` (3), `dto/EventResponse.java`,
`service/EventQueryService.java`, `web/EventController.java`, migration `V3__events_api.sql`,
`EventsApiIntegrationTest`.
**Files deleted.** notification-service's `domain/WebhookEventType.java` — promoted, not copied.
**Files modified.** notification-service (`WebhookEventFactory`, `WebhookEndpointService`,
`WebhookDocumentationConsistencyTest` now import the shared enum), audit-service
(`domain/AuditLogEntry.java`, `service/AuditService.java`, `repository/AuditLogEntryRepository.java`,
`build.gradle.kts`, `application.yaml`).

**The vocabulary moved to `common-dto` (D140).** M18 defined it inside notification-service, correctly,
when that was the only service producing merchant-facing events. D4 forbids audit-service importing it,
so the choice was to duplicate a *frozen public contract* or promote it. Promoted — that is what
`common-dto` is for (`ApiError`, `PageResponse`, `EventEnvelope` are all there for the same reason), and
two hand-maintained copies drifting apart is precisely the failure R10 names. The `evt_` derivation
moved with it, so audit-service produces byte-identical ids to the ones notification-service delivered,
with no coordination — the determinism built into M18.3 for exactly this moment.

**A schema gap found while building it: `audit_log` has never had a merchant column.** That was right
for what it was — a faithful, schema-agnostic recorder (D44) — but a merchant-facing API must be
merchant-scoped, and scoping by digging into a jsonb field on every query is neither indexable nor a
guarantee. `V3` adds `merchant_id`, backfilled from `payload->>'merchantId'` with a UUID-shaped guard so
one malformed value cannot fail the migration. **Nullable**, following D126's precedent for this table:
audit consumes streams whose events genuinely have no merchant, and inventing a value to satisfy a
constraint is a lie in an immutable trail.

**Internal events cannot leak into a merchant's feed — structurally.** The list filters on the
canonical vocabulary rather than a deny-list, so `merchant.events` rows (`ApiKeyIssued`, …) are excluded
because their internal type has no canonical counterpart. D126's decision to record those honestly
rather than coerce them is what makes that correct rather than lucky. Asserted both ways: absent from
the list *and* unreachable by direct id.

**Ordered by `occurredAt`, not `recordedAt`.** A merchant asking "what happened, in order" means the
order things happened — and under Kafka redelivery those two genuinely differ.

**Tests.** 16 green in the module (7 new). Canonical shape and derived id; the isolation sweep (real id
refused for another merchant and for the other mode, 404 not 403); an internal-only event invisible both
ways; ordering by occurrence with records written in the opposite order; the type filter rejecting a typo
with the vocabulary named; four malformed event ids returning 400 rather than crashing; pagination
returning every event exactly once.

#### M19.6 — Analytics: hourly buckets and the query API ✅ (2026-07-25)

**Summary.** `payment_stats_hourly` plus `GET /v1/analytics/payments`, returning totals, a derived
success rate, and the hourly series in one response. Closes the other half of V1 known issue #4. The
only write-path change in M19.

**Files created.** `domain/PaymentStatsHourly.java`, `repository/PaymentStatsHourlyRepository.java`,
`dto/AnalyticsSummaryResponse.java`, `dto/AnalyticsBucketResponse.java`,
`service/AnalyticsQueryService.java`, `web/AnalyticsController.java`, `config/SecurityConfig.java`,
`security/*` (3), migration `V3__payment_stats_hourly.sql`, `AnalyticsQueryIntegrationTest`.
**Files modified.** `service/AnalyticsService.java` (bucket written in the same transaction and the same
retry loop as the running total, so they cannot disagree), `AnalyticsServiceTest`, `build.gradle.kts`,
`application.yaml`.

**Hourly, not daily**, because a merchant debugging "what happened this afternoon" needs finer
resolution than a day — and hourly rolls up to daily trivially while the reverse is impossible.
**No backfill**: which hour each past event fell in exists only in audit-service's trail, and
reconstructing analytics from another service's schema would couple two things D4 keeps apart. The
running totals remain complete; only the series has a start date, which is the truth.

**`failed_count` is new here and absent from the running totals** — a gap that only became visible when
something first *read* these numbers: a success rate needs a denominator that includes failures. The
running-total table is left alone rather than widened, because it could not honestly backfill one either.

**The success rate is computed by the platform, not the caller.** There is more than one defensible
denominator, and returning raw counters invites every client to pick a different one. Published
definition: `authorized / (authorized + failed)`. **Null, not zero, when nothing was attempted** — a
rate over zero attempts is unknown, and charting it as zero shows a catastrophic outage every quiet hour.

**A 90-day cap that rejects rather than truncates.** An uncapped range over an hourly table is the
unbounded query M19's risk table warns about; a silently shortened series would be charted as though it
were the whole story.

**Tests.** 26 green in the module (7 new): totals and series in one response, oldest-first; the rate's
denominator; the unknown-rate case; scoping across merchant and mode; the window cap and an inverted
range both rejected; and a partial-hour request returning the bucket that contains it.

#### M19.7 — Gateway routes and scope enforcement ✅ (2026-07-25)

**Summary.** The commit where the public surface actually opens. Five new routes, three new scopes, and
the removal of M15's `RewritePath`.

**Files modified.** `gateway-service/application.yaml` (routes + three base URIs),
`ApiKeyAuthenticationWebFilter.java` (scopes), `docker-compose.yml` (three base URIs),
`payment-service/web/PaymentV1Controller.java` (+ the mutating endpoints),
`ApiKeyAuthenticationIntegrationTest` (stub now answers the public path).

**A defect caught before it shipped.** Removing the rewrite would have broken payment *creation*:
`PaymentV1Controller` had only reads, while create/authorize/capture/refund/void lived on `/api/v1` and
were reached through the rewrite. Fixed by giving the public controller the full surface, delegating to
the same `PaymentService` — one FSM, one idempotency guard, one outbox, not a second implementation. A
rewrite covering only non-GET verbs was rejected as the alternative: one path served by two controllers
depending on the method is correct the day it is written and confusing forever after.

**Scopes.** `balance:read`, `events:read`, `analytics:read` extend §4.9's vocabulary. Read-only
resources, so no write counterpart is named — a scope for an operation the platform does not offer would
be a promise rather than a permission. Refunds deliberately reuse `payments:read`: a key that may read
payments may read their refunds, and §4.9's `refunds:write` describes *issuing* one, which is still
`payments:write` on the payment itself.

**Regression caught by the existing suite.** Three `ApiKeyAuthenticationIntegrationTest` cases failed
immediately on the route change, because the stub answered `/api/v1/payments` and requests now arrive at
`/v1/payments`. Exactly what that test exists to catch.

#### M19.8 — Verification, closure, and the six defects it found ✅ (2026-07-25)

**Summary.** The four items M19.8 originally carried as open work are closed: real `EXPLAIN` plans on
seeded data, a live docker-compose E2E across every new route, `metadata` on webhook endpoints, and a
merchant-facing read-API guide with a test keeping it honest. Doing them found **six defects**, four of
which were invisible to the existing suite by construction — the strongest argument in the milestone for
why "verified" and "designed to be correct" are different words.

**Files created.** `docs/READ_APIS.md`; `common-lib`: `query/MetadataFilterParams.java` +
`MetadataFilterParamsTest`; payment-service: `PaymentV1ReadApiHttpIntegrationTest`,
`ReadApiDocumentationConsistencyTest`; analytics-service: `AnalyticsDocumentationConsistencyTest`;
migrations `notification/V7__webhook_endpoint_metadata.sql`,
`transaction/V3__balance_read_indexes.sql`.
**Files modified.** `common-lib`: `query/ListQuery.java`. payment-service:
`repository/{Payment,Refund}Repository.java`, `service/PaymentQueryService.java`,
`web/PaymentV1Controller.java`, `application.yaml`, `PaymentReadApiIntegrationTest`.
transaction-service: `repository/LedgerEntryRepository.java`, `service/BalanceQueryService.java`.
audit-service: `repository/AuditLogEntryRepository.java`, `service/EventQueryService.java`.
analytics-service: `service/AnalyticsQueryService.java`, `dto/AnalyticsSummaryResponse.java`,
`AnalyticsQueryIntegrationTest`. notification-service: `domain/WebhookEndpoint.java`,
`dto/{Create,Update}WebhookEndpointRequest.java`, `dto/WebhookEndpointResponse.java`,
`mapper/WebhookEndpointMapper.java`, `service/WebhookEndpointService.java`,
`web/WebhookEndpointController.java`, `docs/WEBHOOKS.md`, `WebhookEndpointApiIntegrationTest`.

---

##### Defect 1 — the `metadata` filter failed *open* (payments and refunds)

`@RequestParam(name = "metadata") Map<String, String>` does not do what it reads like. Spring binds a
`Map` to "every request parameter" **only when the annotation carries no name**; naming it routes the
parameter down the ordinary single-value path, which looks for one parameter literally called
`metadata`. So `?metadata[orderId]=A-1234` bound nothing, the filter reached the query as `null`, and
the endpoint **returned the merchant's entire history as though it had been filtered**.

That is a filter failing open on a financial list — the precise failure `PaymentListFilter` was written
to prevent for `status`, where a typo is a 400 specifically because silently returning the wrong rows is
worse than erroring. It shipped because M19.2's list tests call the repository directly (deliberately,
and with good reason — the SQL is what needed proving) while M19.7's gateway tests answer from a stub.
**No test in the milestone ever sent a query string**, so the one layer between them was the one nothing
executed.

Fixed in `common-lib` as `MetadataFilterParams`, so four list endpoints cannot each get it wrong
differently. A malformed or bare `metadata` parameter is now a **400 naming the correct syntax** rather
than being discarded — the same fail-loud rule the rest of the filter set already followed.

##### Defect 2 — the null-guard idiom demoted every range and cursor predicate to a filter

`PaymentRepository.findPage`'s javadoc claimed the row-wise comparison "lets Postgres satisfy the keyset
predicate directly from `idx_payments_merchant_mode_created` as a single index range scan." The plan says
otherwise. Wrapping it as `(:cursorCreatedAt is null or (created_at, id) < (…))` makes it a **`Filter`,
not an `Index Cond`**: Postgres scans from the newest row of the merchant's partition and discards
everything above the cursor, so a deep page costs **O(depth)** — exactly what keyset pagination exists to
avoid. The same applied to `created_after`/`created_before`.

Measured on 600,000 seeded payments, one page 150 days in:

| | Buffers | Rows discarded | Time |
|---|---|---|---|
| Null-guarded (as shipped) | 2,512 | 2,568 | 1.09 ms |
| Unguarded bounds | **29** | **0** | **0.045 ms** |

The fix is the sentinel-bound idiom M19.4 had already adopted in transaction-service — for a *different*
reason (Postgres cannot infer a bind parameter's type from `? is null` alone). M19.8 found that the
choice made for type-inference reasons is also the one that produces the right plan, so the three
sentinels moved to `ListQuery` (`EARLIEST`, `LATEST`, `LAST_ID`) with bound accessors, and payment-service
adopted them. Three private copies of the same constants collapsed into one, which is what M19.1 said
shared primitives were for. **D141.**

Status, currency, amount and metadata keep their null guards deliberately: they do not participate in the
ordering, so they cost only a `Filter` on rows the index already located, and there is no sentinel for
"any status" that would not be a lie.

##### Defect 3 — `GET /v1/balance_transactions` had no index for its own ordering

`ledger_entries` carried `idx_ledger_entries_account_id` (from M6), which can *find* a merchant's entries
but cannot *order* them. Every page therefore read **every entry the merchant had ever accumulated** and
top-N sorted it. Invisible at any volume this repository produces — the seeded merchants have ~700
entries each, where the sort is free — so a fixture with 200,000 entries on one account was built to tell
an O(page) plan from an O(history) one:

| | Plan | Buffers | Time |
|---|---|---|---|
| As shipped | Parallel Bitmap Heap Scan + top-N heapsort | 5,962 | 24.72 ms |
| With `(account_id, created_at desc, id desc)` | Index Scan | **19** | **0.035 ms** |

**314× fewer buffers.** `transaction/V3__balance_read_indexes.sql` adds it. The old index is deliberately
*not* dropped, unlike M19.2's supersession of `idx_payments_merchant_mode`: it is redundant for lookups
but it also backs the `account_id` foreign key, and dropping the only index on an FK column makes every
delete on `accounts` scan this table.

##### Defect 4 — `GET /v1/balance` was a sequential scan

Resolving a merchant's accounts by `(owner_id, mode)` had no index at all. `uq_accounts_merchant` is
unique on `(account_type, owner_id, currency, mode)`, and a leading `account_type` cannot serve a lookup
that does not name one. Small today — two rows per merchant per mode per currency — and unbounded in the
only direction that matters: it grows with the number of merchants, and it is the query every dashboard
load starts with. Same migration.

##### Defect 5 — the documented `metadata[key]=value` syntax returned Tomcat's HTML 400

Found on the live stack, not by any test. Tomcat rejects `[` and `]` in a query string by default (RFC
3986 reserves them), so a client sending the published form **literally** got `HTTP 400` with Tomcat's
*HTML* error page — not merely a failure, but one that breaks the JSON error contract M21 will freeze.
Percent-encoded brackets worked, which is why most HTTP clients never saw it and why MockMvc never could:
it builds the request object directly and never goes through Tomcat's URI parser.

`server.tomcat.relaxed-query-chars: "[,]"` in payment-service, scoped to those two characters and to the
one service that serves the filter, so both spellings behave identically instead of one of them depending
on which HTTP library a developer happens to use. The E2E now asserts both. **D142.**

##### Defect 6 — `successRate: null` was not on the wire at all

`AnalyticsSummaryResponse` carried `@JsonInclude(NON_NULL)` like every other response in the platform, so
the field a quiet hour is supposed to report as `null` was **omitted entirely**. M19.6 asserted
`summary.successRate()).isNull()` on the object, which passes either way; the live response was the first
thing to show what a client actually receives.

Absence is the wrong signal here. §4.10 tells clients to expect and ignore fields a version does not
have, so silence makes "we measured and there is no answer" indistinguishable from "this API has no such
field" — hiding the one case the null exists to communicate. The annotation is removed (no other field
here is ever null, so nothing else changes on the wire) and a test now asserts the **serialized** form.
**D143.**

---

##### Query performance verification (§5/M19's `EXPLAIN` risk mitigation)

A throwaway `pf_explain` database was built from a `pg_dump --schema-only` of the **live compose stack**,
so the tables and indexes are exactly what Flyway produced rather than a hand-written approximation, then
seeded to volumes at which a plan means something: 600k payments, 60k refunds, 800k audit rows, 600k
ledger entries (plus a 200k-entry single account), 864k hourly buckets.

Final plans, every public read in its post-fix shape:

| Endpoint | Plan | Buffers | Time |
|---|---|---|---|
| `GET /v1/payments` (first page) | Index Scan `idx_payments_merchant_mode_created` | 28 | 5.0 ms |
| `GET /v1/payments` (deep cursor page) | Index Scan, same index, cursor **in the `Index Cond`** | 29 | 3.6 ms |
| `GET /v1/payments?status=` | Index Scan + `Filter` (152 discarded) | 176 | 5.4 ms |
| `GET /v1/payments?created_after=&created_before=` | Index Scan, range **in the `Index Cond`** | 31 | 0.06 ms |
| `GET /v1/payments?metadata[k]=v` | BitmapAnd of the GIN index + merchant index, then Sort | 21 | 5.1 ms |
| `GET /v1/refunds` | Index Scan `idx_refunds_merchant_mode_created` | 29 | 4.7 ms |
| `GET /v1/payments/{id}` | Index Scan `payments_pkey` | 8 | 2.0 ms |
| `GET /v1/balance` | Index Scan `idx_accounts_owner_mode` (new) | 3 | 1.2 ms |
| `GET /v1/balance_transactions` (200k-entry account) | Index Scan `idx_ledger_entries_account_created` (new) | 19 | 1.1 ms |
| `GET /v1/events` | Index Scan `idx_audit_log_merchant_mode_occurred`, range in the `Index Cond` | 28 | 9.9 ms |
| `GET /v1/events/{id}` | Index Scan `uq_audit_log_event_id` | 8 | 1.2 ms |
| `GET /v1/analytics/payments` (90-day maximum) | Bitmap Index Scan `idx_payment_stats_hourly_series` + Sort | 2,189 | 3.2–3.9 ms (warm) |

**No unexpected sequential scan remains.** Two plans keep a `Sort` and both are correct rather than
tolerated:

- The **metadata filter** sorts because a GIN index cannot supply ordering — containment and
  `ORDER BY created_at` cannot both come from one index. The BitmapAnd confirms the GIN index is doing
  the selective work, which is what it exists for.
- The **analytics series** sorts because the index is `bucket_start DESC` and a chart reads
  ascending. 2,189 buffers is the *widest legal request* (2,160 hourly buckets), which is precisely what
  the 90-day cap bounds — the cap is the mitigation, and it is now a measured one.

**Keyset pagination confirmed correct at the plan level**, not just behaviourally: the row-wise comparison
appears inside `Index Cond`, so page 500 costs what page 1 costs. That was the property M19.1 claimed and
M19.8 is the first thing to check.

---

##### Live docker-compose E2E — 83 checks, 0 failures

Images rebuilt for all nine services; every M19 migration applied against the **populated** database
(`payment` V5/V6, `audit` V3, `analytics` V3, `notification` V7, `transaction` V3, all `success = t`);
all 13 containers healthy. Driven entirely over the real gateway on `:8080` with two freshly registered
merchants and their own onboarding-issued keys — no test-only bypass, no direct service call.

- **Payments list** — envelope (`object: "list"`), `limit`, `hasMore`, cursor round-trip across a page
  boundary with no overlap, status/currency/amount filters, unknown status → 400, `limit` clamped above
  the ceiling but rejected at 0, forged cursor → 400.
- **`metadata` filter** — both the literal and percent-encoded spellings narrow to exactly one payment;
  a value nothing carries returns **0**, not everything; a bare `metadata=` is 400.
- **`expand=refunds`** — attaches the refund with its own `object: "refund"`; absent (not `[]`) without
  `expand`; an unrecognised `expand` value is ignored rather than rejected.
- **Refunds** — list, by id, filtered by payment (including a payment with none → 0), metadata filter.
- **Balance** — pending and available per currency; **the reported available balance equals a direct SQL
  sum over the ledger entries** (M19's completion criterion, verified against the live database, not a
  fixture); the platform's clearing account never appears; entries carry the payment that caused them.
- **Events** — canonical `evt_` ids, retrievable by that id, type filter, unknown type → 400, malformed
  id → 400 rather than a crash.
- **Analytics** — totals plus the hourly series in one response, the 90-day cap and an inverted range
  both rejected, and an idle merchant reporting `successRate: null` explicitly.
- **Merchant isolation** — 7 checks, every resource, both directions: a real id from the other merchant
  is **404, never 403** (D102), and neither merchant's list, balance, or ledger contains the other's rows.
- **Mode isolation** — 8 checks: test ids invisible to the live key and vice versa across payments,
  refunds, events and the ledger; a live list contains only `mode: "live"` rows; and a **client-supplied
  `X-PF-Mode: live` cannot cross the boundary**, because the gateway strips it (M16.2's defence in depth,
  now demonstrated rather than asserted).
- **Scope enforcement** — a `payments:read` key reads payments and refunds (200) and is refused balance,
  events, analytics and webhooks (403) and payment creation (403); no credential and a garbage key are
  both 401.
- **Gateway routing** — all four previously unroutable paths reach their services.
- **Webhook endpoint metadata** — stored at registration, readable afterwards, replaced wholesale by
  `PATCH`, and left untouched when the field is omitted.

##### `metadata` on webhook endpoints (§4.6's third object)

§4.6 and §5/M19's feature list both name payments, refunds **and endpoints**; M19.2/M19.3 delivered the
first two. `webhook_endpoints.metadata jsonb not null default '{}'` closes the third, exposed on create,
update and every read.

**Deliberately not indexed**, and recorded rather than glossed over: payments and refunds carry a GIN
index because their lists expose a containment filter that is unusable without one. The endpoint list has
no filter — it returns every endpoint a merchant has in one mode, hard-capped at 16 — so a GIN index here
would be paid for on every write to serve a query that does not exist. §5/M19 says metadata is "indexed
for filtering"; the index exists exactly where the filtering does. **D144.**

##### Documentation

`docs/READ_APIS.md` — the merchant-facing guide for all five read APIs, at repository root rather than
under a service because it spans five of them. Endpoint overview and required scopes, authentication and
mode binding, the list envelope and cursor semantics (including *why* there is no total count), time-range
tiling, the full filter set per resource, `metadata`, `expand`, the error table and the 404-masking rule,
then a section per resource, and the forward-compatibility contract.

Kept honest by two tests, following M18.9's precedent of asserting against running configuration rather
than literals: `ReadApiDocumentationConsistencyTest` (payment-service) checks the published page sizes
against `ListQuery`'s constants, that **every** `PaymentStatus`, `RefundStatus` and `CanonicalEventType`
value appears, the inclusive/exclusive range semantics, and — the one that would have caught Defect 1 —
**round-trips the exact filter string the guide prints back through the parser that has to accept it**.
`AnalyticsDocumentationConsistencyTest` checks the default and maximum windows against
`AnalyticsQueryService`'s constants and the published success-rate formula.
`WebhookDocumentationConsistencyTest` and `WEBHOOKS.md` were updated for endpoint metadata.

---

##### Regression verification (2026-07-26)

`.\gradlew.bat build --rerun-tasks --no-parallel --max-workers=1` — **BUILD SUCCESSFUL in 12m 28s**,
`74 actionable tasks: 74 executed`. Aggregated from each module's JUnit XML rather than the task summary
(M18.1's trap): **566 tests, 0 failures, 0 errors, 0 skipped** across all 11 test-bearing modules —
notification 152, payment 126, sandbox 78, common-lib 46, gateway 31, analytics 30, transaction 27,
common-dto 24, merchant 24, audit 16, identity 12.

**+84 tests over M18's 482**, and the growth lands where M19 did the work: common-lib +24 (the cursor,
`ListQuery` and `MetadataFilterParams` primitives), payment +25, analytics +11, and +7 each in
common-dto, transaction and audit.

**That every test genuinely re-executed was checked rather than assumed**, because a build cache that
restores a green result is indistinguishable from a green build until it hides a real failure. All 12
`:test` tasks appear in the log as executed; the only 22 `UP-TO-DATE`/`FROM-CACHE`/`NO-SOURCE` entries
are empty `processTestResources` directories and the two source-less modules (`platform-bom`, and
`load-tests`, whose Gatling simulations carry no JUnit tests). No test task was served from cache.

**A deliberate note on the wall-clock time**, since it invites a wrong conclusion: M18.9's equivalent run
took 1h 17m and this one took 12m 28s. The difference is environmental, not a reduction in what ran —
the Testcontainers images were already resident and the full compose stack was up from M19.8's E2E, so
this run paid no image-pull or cold-start cost, and Gradle reported `Configuration cache entry reused`.
The task and test counts above are the honest signal; the clock is not.

##### Completion criteria (§5/M19)

| Criterion | Status |
|---|---|
| Every V1 "no query API" known issue is closed | ✅ §2.11 #3 (transaction-service) and #4 (audit + analytics) closed |
| Cursor pagination is stable under concurrent writes | ✅ `PaymentReadApiIntegrationTest` pages with three rows inserted between pages, and the keyset boundary is exact when timestamps collide; re-confirmed on the live stack |
| Ledger totals returned by the balance API match a direct `psql` sum exactly | ✅ Asserted in `BalanceApiIntegrationTest` and again in the E2E **against the live database**, not a fixture — meaningful only because the balance is projected from the ledger rather than stored |
| Every endpoint enforces merchant and mode isolation, verified endpoint by endpoint | ✅ Per-module isolation tests plus the E2E's 7 merchant-isolation and 8 mode-isolation checks; a real id from the other merchant is 404, never 403 (D102) |
| List, error, and pagination semantics are identical across all resources | ✅ Structural rather than reviewed — one `CursorPage`, one `ListQuery`, one `MetadataFilterParams` and one `CursorCodec` serve all five resources, and `ReadApiDocumentationConsistencyTest` round-trips the published filter syntax through the parser that must accept it |

Additionally, §5/M19's `EXPLAIN` risk mitigation is satisfied by measured plans on production-scale
seeded data rather than assumption: no unexpected sequential scan remains, and keyset pagination is
confirmed correct *at the plan level* — the row-wise comparison appears inside `Index Cond`, so page 500
costs what page 1 costs.

**M19 status: complete.** All eight sub-milestones implemented, verified independently, and validated
together on a live stack over the real gateway. V1 known issues #3 and #4 are closed, and with them the
last of the "three services with no API at all" that D42 deferred in V1. **Six defects were found and
fixed during M19.8**, four of which were invisible to the existing suite by construction — a `metadata`
filter that failed *open* and returned a merchant's entire history (Defect 1), two missing indexes that
made reads O(history) instead of O(page) (Defects 3 and 4), a published filter syntax that returned
Tomcat's HTML error page (Defect 5), and a `null` the API was supposed to report but never put on the
wire (Defect 6). The milestone's own testing strategy is what found none of them: M19.2's list tests call
the repository directly and M19.7's gateway tests answer from a stub, so **no test in the milestone ever
sent a query string** until M19.8 added one. That gap — between "designed to be correct" and "observed to
be correct" — is the most transferable thing M19 produced.

**Next milestone: M20** — API request logging, usage metering, and per-key rate limits.

---

## 18. Performance Benchmarks (V2)

*(Populated by M28. V1's benchmarks remain in `PROJECT_CONTEXT.md` §14 and are the
regression baseline for the original payment hot path.)*

*No V2 benchmarks yet.*

---

## Appendix A — Planning Decisions Confirmed With the User (2026-07-20)

Four scope questions were resolved before this plan was written, because each materially
changed the milestone breakdown:

| Question | Decision | Consequence |
|---|---|---|
| Test vs live mode | **Dual-mode keys with a simulated acquirer** | M16 (mode isolation) and M17 (sandbox) both exist; D101/D104 |
| SDK scope | **Node + Python first, Java + Go later** | M22 and M26 are separate; D111 |
| Deployment posture | **Local-first, one AWS milestone at the end** | M29 is the only infra milestone; D113 |
| Frontend structure | **One Next.js app with role-based views** | M23/M24 build one app; D112 |
