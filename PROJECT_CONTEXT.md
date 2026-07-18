# PROJECT_CONTEXT.md — Distributed Payment Orchestration Platform

> **Single source of truth.** Updated after every completed milestone. If code and this
> document disagree, fix whichever is wrong — never leave it stale.

---

## 1. Project Overview

### Purpose
A production-inspired **Distributed Payment Orchestration Platform** that models how a real
payment processor (Stripe / Razorpay style) accepts, authorizes, captures, and refunds
payments across independent microservices — with idempotency, event-driven propagation,
resilience, observability, and cloud deployment. Built as a final-year portfolio piece meant
to hold up under backend / distributed-systems interview scrutiny.

### Design Principles
- **Depth before breadth** — one full vertical slice working (and deployed) before widening.
- **Database-per-service** (schema-per-service on a shared instance) — no cross-service joins.
- **Async by default** — Kafka domain events for state propagation; sync REST only when a
  caller cannot proceed without a fresh, consistent answer.
- **At-least-once delivery + idempotent consumers** — no mythical distributed exactly-once.
- **Transactional Outbox** — never dual-write to DB and Kafka non-atomically.
- **Money is integer minor units** (`BIGINT`) + currency code — never floating point.
- **Explicit state machine** for payment lifecycle — illegal transitions are rejected.
- **Clean Architecture** per service; constructor injection only; immutable record DTOs.
- **Secrets never hardcoded**; **Flyway** owns schema (Hibernate `ddl-auto=validate`).

---

## 2. Technology Stack

| Layer | Technology |
|---|---|
| Language / Framework | Java 25 (LTS), Spring Boot 4.0.x |
| API Gateway | Spring Cloud Gateway (reactive) |
| Security | Spring Security, JWT (access + refresh), BCrypt, RBAC |
| Persistence | Spring Data JPA, PostgreSQL 17, Flyway migrations |
| Cache / Locks | Redis 8 (cache-aside, TTL, distributed locks) |
| Messaging | Apache Kafka (KRaft mode), retry + dead-letter topics |
| Sync calls | OpenFeign (only where consistency requires it) |
| Resilience | Resilience4j (circuit breaker, retry, timeout, bulkhead) |
| Observability | Micrometer, Prometheus, Grafana, Loki, Micrometer Tracing (W3C) |
| Docs | OpenAPI / Swagger UI (springdoc) |
| Testing | JUnit 5, Mockito, Testcontainers, Gatling (load) |
| Build | Gradle (Kotlin DSL), multi-module monorepo, version catalog |
| Containers | Docker (multi-stage), Docker Compose |
| Cloud | AWS ECS Fargate, ECR, RDS PostgreSQL, ElastiCache Redis, S3, Secrets Manager, ALB, Route53, ACM |
| IaC | Terraform (remote state: S3 + DynamoDB lock) |
| CI/CD | GitHub Actions |
| Frontend | Next.js, TypeScript, Tailwind CSS |

---

## 3. Microservices

| Service | Responsibility |
|---|---|
| **gateway-service** | Edge: routing, JWT validation, Redis rate-limiting, CORS, security headers, correlation-id injection |
| **identity-service** | Users, auth, BCrypt, JWT issue/refresh, RBAC |
| **merchant-service** | Merchant onboarding, API-key issuance, merchant profile caching |
| **payment-service** | Core: payment FSM, create/authorize/capture/refund, idempotency, Saga orchestration, transactional outbox |
| **transaction-service** | Double-entry ledger; idempotent consumer of payment events; optimistic locking |
| **audit-service** | Immutable audit trail; event sink |
| **notification-service** | Webhook delivery + email; retry topic + DLQ |
| **analytics-service** | Read models / aggregates for reporting |
| **common-dto** | Shared immutable DTOs + Kafka event schemas |
| **common-lib** | Exceptions, error envelope, filters, JSON logging, security utils |
| **platform-bom** | Dependency version alignment |

---

## 4. Communication Flow (payment happy path)

```
Client → ALB → Gateway (JWT, rate-limit) → Payment Service
  Payment Service: validate idempotency key → persist Payment(PENDING)
    → write outbox row (same TX) → return 201
  Outbox relay → Kafka: payment.events (PaymentAuthorized)
    → Transaction Service (ledger entry, idempotent)
    → Audit Service (append audit)
    → Notification Service (webhook to merchant; retry → DLQ on failure)
    → Analytics Service (update read model)
```

**Payment state machine:** `CREATED → AUTHORIZED → CAPTURED → REFUNDED`
(plus `FAILED`, `VOIDED`, `PARTIALLY_REFUNDED`). Transitions are guarded.

---

## 5. Data & Messaging Conventions

- **Money:** `amount_minor BIGINT`, `currency CHAR(3)`. Never `double`/`float`/`FLOAT`.
- **Idempotency:** `Idempotency-Key` header → checked in Redis then `idempotency_keys` table.
- **Kafka topics:** `<domain>.events` (e.g. `payment.events`), retry `<topic>.retry`,
  DLQ `<topic>.dlq`. Consumer groups named `<service>-<topic>`.
- **IDs:** UUID (v7 preferred for index locality) for external entity IDs.
- **Timestamps:** UTC, `TIMESTAMPTZ`.

---

## 6. Roadmap & Milestones

Phases must not be skipped. Each milestone is a confirm-gate.

### Phase 1 — Local Development (Docker Compose)
- **M0** Repo bootstrap: monorepo, Gradle multi-module, compose infra, this file
- **M1** Shared modules (`common-dto`, `common-lib`)
- **M2** Identity Service (auth, JWT, refresh, RBAC, Flyway, Testcontainers)
- **M3** Gateway Service (routing, JWT validation, rate-limit, CORS) — *first e2e slice*
- **M4** Merchant Service (onboarding, API keys, caching)
- **M5** Payment Service (FSM, idempotency, outbox, Kafka publish)
- **M6** Transaction Service (double-entry ledger, idempotent consumer)
- **M7** Audit + Notification + Analytics (consumers, webhooks, DLQ)
- **M8** Resilience4j (circuit breakers, retries, timeouts, bulkheads)

### Phase 2 — Containerization
- **M9** Per-service multi-stage Dockerfiles, healthchecks, layered jars

### Phase 3 — CI/CD
- **M10** GitHub Actions: test + build + image; branch protection

### Phase 4 — Terraform Infrastructure
- **M11** VPC, ECR, RDS, ElastiCache, Kafka, ALB, Secrets Manager, IAM, remote state

### Phase 5 — AWS ECS Fargate
- **M12** ECS task defs + services, ALB target groups, secrets injection, CD deploy

### Phase 6 — Observability
- **M13** Prometheus + Grafana + Loki, dashboards, alerts, distributed tracing

### Phase 7 — Performance
- **M14** Gatling load tests; record P95/P99/throughput/error-rate

### Finalization
- **M15** Next.js merchant console, OpenAPI polish, README, diagrams, interview notes

---

## 7. Status

- **Current milestone:** M8 (Resilience4j) — *pending approval*
- **Completed milestones:** M0 (repo bootstrap) ✅ · M1 (shared modules) ✅ · M2 (Identity Service) ✅ · M3 (Gateway Service) ✅ · M4 (Merchant Service) ✅ · M5 (Payment Service) ✅ · M6 (Transaction Service) ✅ · M7 (Audit + Notification + Analytics) ✅
- **Pending milestones:** M8–M15

---

## 8. Settled Decisions

1. **Build tool:** ✅ Gradle (Kotlin DSL) with centralized version catalog.
2. **Repo layout:** ✅ Monorepo.
3. **Build order:** ✅ Depth-first vertical slice.
4. **Base Java package:** ✅ `com.paymentflow` (e.g. `com.paymentflow.identity`).
5. **AWS Kafka:** Amazon MSK vs self-managed Kafka on ECS — *deferred to M11*.

---

## 9. Technical Decisions & Trade-offs (log)

| # | Decision | Alternatives | Rationale |
|---|---|---|---|
| D1 | Async Kafka events for state propagation | Sync REST everywhere | Loose coupling, resilience, replayability |
| D2 | At-least-once + idempotent consumers | "Exactly-once" | Distributed exactly-once is impractical; dedup is honest & robust |
| D3 | Transactional Outbox for DB→Kafka | Direct publish after commit | Eliminates dual-write inconsistency |
| D4 | Schema-per-service on shared PG | Instance-per-service | Isolation without 8× cost |
| D5 | Saga (orchestration) in Payment Service | 2PC / choreography | No distributed locks; central visibility of payment flow |
| D6 | Integer minor units for money | Decimal/double | Avoids float rounding corruption |
| D7 | Java 25 toolchain via Gradle + Foojay auto-provision | Use local JDK (26) | Reproducible builds independent of the machine's JDK; Docker/CI compile on JDK 25 too |
| D8 | Convention plugins in a `build-logic` included build | `subprojects{}` / `buildSrc` | No duplicated build config; stable configuration cache; reusable plugins |
| D9 | Dedicated host-port range for local infra (55432/56379/59092) | Standard ports | Coexists with other local stacks; internal container ports stay standard |
| D10 | Kafka topic names use dots only, never underscores | mixed separators | Prevents Kafka metric-name collisions (`.`/`_` ambiguity) |
| D11 | `common-lib` is a Spring Boot auto-config starter; web deps are `compileOnly` | plain shared jar forcing spring-web on all | Servlet stack not leaked onto the reactive gateway; servlet pieces self-activate via `@ConditionalOnWebApplication(SERVLET)` |
| D12 | Custom immutable `ApiError` envelope with stable `code` | RFC 9457 `ProblemDetail` | Keeps `common-dto` framework-free; gives clients a stable machine-readable contract; field errors omit rejected values (no leaking secrets) |
| D13 | Spring Boot 4 native structured (JSON) logging, MDC-fed | logstash-logback-encoder | Zero extra deps; correlation/request ids flow automatically; format chosen per service via property |
| D14 | Defer the Kafka event envelope to M5 | build it now in `common-dto` | No real producers yet; designing the abstraction without them risks getting it wrong (YAGNI) |
| D15 | RS256 (asymmetric) JWTs + public JWKS endpoint | HS256 shared secret | Validators (gateway, services) verify with the public key; no secret sharing across the fleet |
| D16 | Opaque, SHA-256-hashed, rotating refresh tokens in DB | stateless refresh JWT | Revocable (real logout), replay-detectable; a DB leak can't mint tokens; access tokens stay stateless |
| D17 | Identity also validates its own tokens (resource server) + method `@PreAuthorize` | trust the gateway only | Per-service zero-trust; RBAC demonstrable now, independent of the edge |
| D18 | Ephemeral RSA keypair if none configured (dev); PEM from Secrets Manager in prod | commit a dev key | No secret in git; JWKS distributes the public key regardless |
| D19 | **Spring Boot 4 uses Jackson 3** (`tools.jackson.*`), not Jackson 2 | assume `com.fasterxml` | The auto-configured `ObjectMapper` bean is `tools.jackson.databind.ObjectMapper`; inject that. Jackson 2 lingers transitively but has no bean |
| D20 | Boot 4 **modular auto-config**: add `spring-boot-flyway`, `spring-boot-webmvc-test`; Testcontainers **2.x** renamed artifacts to `testcontainers-*` | rely on Boot 3 coordinates | Autoconfig split out of the monolithic `spring-boot-autoconfigure`; plain `flyway-core` no longer wires Flyway |
| D21 | Security errors rendered as `ApiError`: filter-chain via handlers, method-security via a service-local `@RestControllerAdvice` | let 403s fall through to 500 | `@PreAuthorize` denials surface at the DispatcherServlet, not the filter chain; common-lib stays security-agnostic |
| D22 | Gateway routes/filters defined declaratively in YAML (`spring.cloud.gateway.server.webflux.routes`) | `RouteLocatorBuilder` fluent Java config | Ops can retune routes/rate-limits per environment without a redeploy; consistent with how every other cross-cutting setting in the platform already lives in `application.yaml` |
| D23 | Gateway authenticates only (valid-JWT gate); RBAC stays in each downstream service | duplicate role checks at the edge | Avoids a second, drift-prone copy of authorization rules as more services are added; consistent with D17's per-service zero-trust stance |
| D24 | Redis rate-limit key: authenticated → `user:<sub>`, unauthenticated → `ip:<remote-addr>` | key by IP for everyone | Isolates one busy authenticated user from another; still rate-limits the brute-forceable `/api/v1/auth/**` endpoints by source IP since no token exists yet at that point |
| D25 | Gateway ships its own reactive `CorrelationIdWebFilter` / `GatewayErrorWebExceptionHandler`, not common-lib's servlet ones | make common-lib's filter/handler stack-agnostic | common-lib's servlet auto-configuration correctly stays inactive on the reactive gateway (D11); duplicating the *behavior* in a reactive-native form was the planned shape, not a workaround |
| D26 | Full MDC-in-reactive log correlation deferred to M13 (Micrometer Tracing/Observation) | bolt on ad hoc Reactor-Context→MDC bridging now | WebFlux isn't thread-bound per request, so servlet-style MDC doesn't transplant cleanly; the header still crosses the wire correctly today (the actual cross-service requirement), which is what M3 asks for |
| D27 | Extracted `OpaqueTokenGenerator` (SecureRandom + SHA-256) into common-lib; identity's `RefreshTokenService` and merchant's `ApiKeyService` both use it | duplicate the same ~15-line helper in each service | "No duplicated code" is a standing project requirement; low-risk, behavior-preserving refactor now that a genuine second consumer exists (rule of three deliberately not invoked earlier, at a single use) |
| D28 | Merchant ownership derived from the JWT subject, never a path parameter; the one role-gated endpoint (list-all) stays `@PreAuthorize`-based like identity | accept an owner/merchant id as a path/query parameter | Structurally impossible to request another merchant's profile by guessing an id — no IDOR surface to defend, by construction |
| D29 | Single active API key per merchant, rotate-in-place (mirrors D16's refresh-token rotation); enforced with a partial unique index (`WHERE revoked_at IS NULL`) | multiple named/scoped keys | Simpler mental model and code, consistent with an already-approved pattern; the DB — not just application logic — guarantees at most one active key |
| D30 | Cache-aside via Spring `@Cacheable`/`@CacheEvict` over an immutable response DTO, never the JPA entity; Redis JSON serialization via `GenericJacksonJsonRedisSerializer` (Jackson 3-aware) | cache the entity directly; Boot's default JDK-serialization `RedisCacheManager` | Caching a JPA entity risks stale/detached-entity bugs on deserialization; JDK serialization produces an opaque binary blob inconsistent with the platform's JSON-everywhere convention |
| D31 | No cross-service "validate API key" endpoint built yet, even though payment-service will eventually need one | build the contract now, speculatively | YAGNI — no real caller exists before payment-service (M5); same rationale as D14 (don't guess an abstraction's shape before a real consumer exists) |
| D32 | Payment creation/mutation authenticated via JWT through the gateway (matches §4's already-approved communication-flow diagram exactly); merchant resolved server-side via OpenFeign to merchant-service's existing `/me`, forwarding the caller's JWT | merchant API-key-based server-to-server auth for payment creation | Confirmed with the user before implementing (D31 had flagged this as genuinely open); API-key-based payment creation is deferred to whenever a real caller for it exists — none does in this platform yet |
| D33 | `TransactionTemplate` (not declarative `@Transactional`) wraps the state-mutation + outbox-write step inside `IdempotencyService.guarded(...)` | plain `@Transactional` on the service method | Declarative `@Transactional` commits only *after* the method returns to its caller; the idempotency Redis lock must be held until that commit lands, and releasing it in the same method's own `finally` releases it before commit. `TransactionTemplate` lets one method correctly sequence lock → commit → unlock without a cross-bean self-invocation split |
| D34 | `Idempotency-Key` required on every mutating endpoint (create/authorize/capture/refund/void), not just create | require it only on `POST /payments` (mirrors some real payment APIs) | Uniform guard across the whole lifecycle — a retried authorize/capture/refund/void is exactly as replay-able a network-retry scenario as create |
| D35 | Capture is all-or-nothing (no partial capture); refund supports partial amounts, accumulating to `REFUNDED` once the full captured amount is refunded | model partial capture too | The approved FSM (§4) lists no `PARTIALLY_CAPTURED` state — only `PARTIALLY_REFUNDED` — so partial capture isn't part of the approved lifecycle |
| D36 | Payment event payloads (`PaymentEventPayload`) live in payment-service's own package, not common-dto — only the structural `EventEnvelope<T>` wrapper is shared | share the concrete payload DTO so consumers can import it directly | Extends schema-per-service (D4) to messaging contracts: consumers (M6+) define their own copy matching the known JSON shape, so no consumer's compile-time dependencies couple to payment-service's internal model |
| D37 | `currency` stored as `VARCHAR(3)`, not literal `CHAR(3)` despite §5's wording | fight Hibernate's schema validator to keep `CHAR(3)` | Hibernate's schema validator has a known rough edge validating a plain JPA `String` against a `CHAR` column even with a `columnDefinition` override; `VARCHAR(3)` is functionally identical for a code that is always exactly 3 characters |
| D38 | merchant-service's Redis cache serializer uses its own dedicated `ObjectMapper` (via `GenericJacksonJsonRedisSerializer.builder().enableDefaultTyping(...)`, scoped to `com.paymentflow.merchant`), not the app's shared Jackson bean | reuse the app's shared `ObjectMapper` for the cache serializer too (as originally shipped in M4) | A cache read has no target type to deserialize into ahead of time — the serializer needs embedded type metadata to reconstruct the concrete class instead of a raw `Map`. Found as a real bug during M5's manual E2E testing (see Problems below) and fixed retroactively in merchant-service |
| D39 | Transaction-service ledger posts on `Authorized` + `Captured` + `Refunded`/`PartiallyRefunded` (confirmed with the user before implementing); `Voided`/`Failed` reverse only if `previousStatus` was `AUTHORIZED` | post only on `Captured` + `Refunded` (no pending-obligation modeling) | Recognizing a pending obligation at authorization time gives the ledger visibility into authorized-but-not-yet-captured exposure, matching how real payment processors reconcile; `Created` never posts (nothing promised yet) |
| D40 | Three ledger accounts per currency: one platform-wide `PLATFORM_CLEARING` (debit-normal), and per-merchant `MERCHANT_PENDING` / `MERCHANT_SETTLED` (both credit-normal) | a single merchant account with a status flag, or per-payment sub-accounts | Debit/credit normalcy by account *type* keeps `Account.apply(direction, amount)` a pure, table-driven function; a fully-refunded lifecycle nets every account back to zero, a strong correctness invariant exercised in the integration tests |
| D41 | `PaymentEventPayload`/`PaymentLedgerEventPayload` carry `eventAmountMinor` — the incremental delta for this specific event (full amount for authorize/capture/void, the partial/remaining amount for a refund) — not a running total | have the consumer diff against the previous ledger state itself | The producer (payment-service) already knows the delta at the moment of the state transition; recomputing it downstream from ledger history would duplicate FSM knowledge into transaction-service and break schema-per-service's messaging analogue (D36) |
| D42 | transaction-service ships no REST API, no Spring Security, no OpenFeign client — its only inbound interface is the `payment.events` Kafka stream | give it a read API for ledger/account balances now | Scoped exactly to the approved roadmap line ("double-entry ledger, idempotent consumer, optimistic locking"); a query API has no approved consumer yet (same YAGNI rationale as D14/D31) — `spring-boot-starter-web` is kept only for actuator's HTTP health endpoint, matching every other service ahead of M9's container healthchecks |
| D43 | Merchant webhook destination stored as a nullable `webhook_url` on merchant-service's `merchants` table (self-service `PATCH /api/v1/merchants/me/webhook`, HTTPS-only); payment-service's existing merchant-resolution Feign call (already runs on every mutation) also returns it, and embeds it — plus `contactEmail` — directly into `PaymentEventPayload` at publish time | notification-service calls merchant-service synchronously at delivery time to look up the URL; or a single platform-wide webhook sink for all merchants | Confirmed with the user before implementing (a genuinely open question — nothing in the platform stored a webhook destination or gave an async consumer a way to authenticate a synchronous call back). Event-carried delivery info means notification-service (M7) needs zero synchronous calls to any service, staying a pure async consumer with no new service-to-service auth problem to solve |
| D44 | audit-service parses each event as a generic JSON tree (`JsonNode`) and stores the payload verbatim in a `jsonb` column, rather than deserializing into a typed payload class | give audit-service its own local `PaymentEventPayload`-shaped copy, like every other consumer (D36) | Audit's entire job is to record whatever event came through, unchanged — it has no business reason to know any specific event's shape. A schema-agnostic append log is a better fit than replicating a payload class it would never otherwise use |
| D45 | notification-service's "email" channel is a simulated, durably logged send (`email_log` table) — no real SMTP/SES integration in this milestone | wire up real email delivery (Spring Mail + a local dev SMTP catcher) | No email provider is part of the approved stack for M7; mirrors D18's established pattern of a local, honest stand-in now with the real integration deferred until a concrete need (and provider choice) exists |
| D46 | Webhook delivery follows the outbox shape (D3): dedup check + email log + a `PENDING` `webhook_deliveries` row all commit in one short DB transaction with no network I/O inside it; the first delivery attempt happens synchronously right after that commit; a failure publishes just the event id to an explicitly-declared `payment.events.retry` topic (D10 naming), consumed by a dedicated retry listener that backs off (jittered exponential, mirroring `LedgerService`'s M6 backoff shape) and retries up to 5 total attempts before dead-lettering to `payment.events.dlq` | retry with Spring Kafka's built-in `@RetryableTopic` | `@RetryableTopic` retries the whole listener method (email logging included) and isn't a pattern any existing service uses yet; a hand-rolled explicit retry/DLQ topic pair, consistent with D10's topic-naming convention and M6's proven backoff-retry idiom, keeps the platform's patterns uniform rather than introducing a second, unrelated retry mechanism |
| D47 | analytics-service's `MerchantPaymentStats` aggregate row uses the identical optimistic-lock + whole-transaction-retry pattern as transaction-service's `LedgerService` (M6) | a different concurrency strategy for a "just a counter" table | Every event for one merchant+currency contends on the same row, exactly like M6's shared clearing account — reusing a proven, already-tested pattern beats inventing a second one for what is structurally the same problem |
| D48 | audit-service (8091), notification-service (8092), analytics-service (8093) — not the sequential 8085–8087 the port scheme would otherwise suggest | keep strict sequential ports after transaction-service's 8084 | Host port 8085 is already published by `docker-compose.infra.yml`'s Kafka-UI container (discovered when audit-service failed to bind during manual verification); jumped to 8091+ to leave clear headroom rather than renumber Kafka-UI |

---

## 10. Risks
- Scope explosion across 8 services → mitigated by depth-first build order.
- Kafka/KRaft local resource use on dev machine → infra-only compose file for fast loop.
- AWS cost during Phase 5 → single small RDS/ElastiCache, teardown scripts, cost notes.

## 11. Known Issues
- Gateway does not yet honor `X-Forwarded-*`/`Forwarded` headers (Spring Cloud Gateway 2025.x disables this by default unless `spring.cloud.gateway.server.webflux.trusted-proxies` is set). Irrelevant with no reverse proxy in front locally; must be configured in M12 once the gateway sits behind an ALB, or HSTS/scheme-dependent behavior will see the wrong (plaintext) scheme.
- Gateway-local log lines do not carry `correlationId`/`requestId` via MDC (WebFlux isn't thread-bound per request); the header still propagates correctly across the wire. Full reactive log correlation is deferred to M13 (see D26).
- No merchant-API-key-based auth path exists for payment creation (see D32) — only JWT-via-gateway, matching §4. Deferred until a real server-to-server caller for it exists.
- No circuit breaker/retry/fallback around payment-service's Feign call to merchant-service — a merchant-service outage surfaces as a 503 to the caller with no resilience yet. Resilience4j is M8, deliberately not pulled forward.
- Concurrent duplicate requests sharing an `Idempotency-Key` fail fast (409) rather than blocking briefly and replaying once the first completes. Simpler and deterministic; a documented simplification, not a bug.
- transaction-service has no query API for ledger/account balances (see D42) — verifying ledger state today requires a direct `psql` query against the `transaction` schema. Deferred until a real consumer for that data exists.
- Every event touching a given currency's shared `PLATFORM_CLEARING` account contends on the same row under concurrent load; the retry-with-jittered-backoff loop (`MAX_ATTEMPTS = 10`) handles this correctly today, but a high-throughput production system would eventually want sharded clearing accounts or a queue-per-account model instead of optimistic-lock retries. Not a concern at this platform's scale.
- notification-service's "email" channel is simulated/logged only (D45) — no real SMTP/SES provider is wired up, so nothing is actually emailed. Deferred until a provider is chosen.
- notification-service's webhook delivery has no HMAC/signature scheme yet — a receiving merchant endpoint can't cryptographically verify a webhook actually came from this platform. Real payment platforms sign webhook bodies; deferred as out of scope for this milestone's "webhook delivery + retry + DLQ" line item.
- audit-service/analytics-service (like transaction-service, D42) have no query API for their data yet — verifying audit/aggregate state today requires a direct `psql` query. Deferred until a real consumer for that data exists (candidate: M15's merchant console).
- Concurrent duplicate webhook-delivery attempts for the same event are not possible by construction today (only the main listener's inline attempt and the dedicated retry listener ever touch a `webhook_deliveries` row, never both at once) — `WebhookDelivery`'s `@Version` is defensive only, not load-bearing yet.

## 12. Future Improvements
- gRPC for internal sync calls; API versioning; blue/green on ECS; OpenTelemetry collector.

## 13. Interview Talking Points
- Why at-least-once + idempotency instead of exactly-once.
- Transactional outbox vs dual-write.
- Saga orchestration for the authorize→capture→refund flow.
- Money as integer minor units.
- Cache-aside + distributed lock (cache stampede prevention).

## 14. Performance Benchmarks
- TBD (M14).

## 15. Deployment Status
- Local infra (Postgres/Redis/Kafka/Kafka-UI): **runs, 4/4 healthy** via `docker-compose.infra.yml`.
- **identity-service:** builds, all tests pass, verified running locally on port 8081 against the compose Postgres (Flyway migrated the `identity` schema; full auth flow + RBAC exercised over HTTP).
- **gateway-service:** builds, all tests pass, verified running locally on port 8080 against the compose Redis, proxying to identity-service, merchant-service, and payment-service — full register→login→gateway-authenticated-request flow exercised over HTTP, including a real Redis-backed 429 under concurrent load.
- **merchant-service:** builds, all tests pass, verified running locally on port 8082 against the compose Postgres/Redis (Flyway migrated the `merchant` schema) — onboarding, cached profile reads (including a genuine cache-hit round trip, D38), cache-busting updates, API-key rotation, and ADMIN-only listing all exercised over HTTP through the gateway.
- **payment-service:** builds, all tests pass, verified running locally on port 8083 against the compose Postgres/Redis/Kafka (Flyway migrated the `payment` schema) — the full create→authorize→capture→partial-refund→refund lifecycle exercised over HTTP through the gateway, with every transition's event confirmed landing on the real `payment.events` Kafka topic via console consumer (correct `eventType`, `previousStatus`, and `correlationId` on each); idempotency replay, illegal-transition rejection, and cross-merchant 404-masking all confirmed live.
- **transaction-service:** builds, all tests pass, verified running locally on port 8084 against the compose Postgres/Kafka (Flyway migrated the `transaction` schema) — consumed a full real create→authorize→capture→partial-refund→refund lifecycle off the live `payment.events` topic and posted 8 correctly balanced ledger entries across 4 transactions, confirmed via direct `psql` query against the real schema; all three ledger accounts netted to zero after the fully-refunded lifecycle; `processed_events` count matched every event consumed, including the no-op `PaymentCreated`; gracefully dropped stale, incompatible messages left over from an earlier manual-testing session without crashing.
- **audit-service:** builds, all tests pass, verified running locally on port 8091 against the compose Postgres/Kafka (Flyway migrated the `audit` schema) — a real 5-event payment lifecycle (create→authorize→capture→partial-refund→refund) consumed off `payment.events` and recorded verbatim in `audit_log`, confirmed via direct `psql` query (correct `event_type`/`aggregate_id`/`correlation_id`/`payload` on every row).
- **notification-service:** builds, all tests pass, verified running locally on port 8092 against the compose Postgres/Kafka (Flyway migrated the `notification` schema) — the same real lifecycle produced 5 simulated `email_log` rows and 5 real webhook HTTP POSTs, delivered on the first attempt to a throwaway local HTTP sink and confirmed both via `psql` (`webhook_deliveries` all `DELIVERED`) and by inspecting the sink's received request bodies (correct `merchantContactEmail`/`merchantWebhookUrl` embedded per D43); separately, a second merchant configured with an unreachable webhook URL was driven through the real retry topic and correctly reached `DEAD_LETTERED` in `webhook_deliveries` after 5 attempts, with the retry listener's dead-letter log line confirming the `payment.events.dlq` publish.
- **analytics-service:** builds, all tests pass, verified running locally on port 8093 against the compose Postgres/Kafka (Flyway migrated the `analytics` schema) — the same real lifecycle produced a correct `merchant_payment_stats` row (created=1, authorized=1, captured=1 @ 15000, refunded=2 @ 15000 total across the partial+full refund), confirmed via direct `psql` query.
- **merchant-service (M7 addition):** the new `PATCH /api/v1/merchants/me/webhook` endpoint verified live through the gateway — HTTPS-only validation correctly rejected a plain-`http://` URL (400), and a valid `https://` URL round-tripped through `GET /me` with the cache correctly busted.
- Other services: skeletons only. AWS: not yet started.

## 16. Lessons Learned
- A cache-aside bug (D38) shipped in M4 and passed M4's own test suite because that suite's only two `/me` reads were separated by a cache eviction — it never exercised a genuine cache *hit* round trip. Manual, real end-to-end testing across services (not just each service's own test suite in isolation) caught what unit/integration tests scoped to a single service could not: a bug that only manifests when a second service (payment-service, via Feign) calls the first one repeatedly in the pattern real traffic actually produces. Worth remembering for M6+: a fresh service's manual E2E pass is also a regression check on everything it calls.

---

## 17. Milestone Change Log
*(One entry appended per completed milestone: Objectives · Files Created · Files Modified ·
Endpoints Added · DB Changes · Kafka Topics · Redis Features · Infra/Terraform/Docker Changes ·
Testing · Verification Steps · Design Decisions · Problems · Solutions · Next Milestone.)*

### M0 — Repository Bootstrap ✅ (2026-07-17)

**Objectives:** Stand up the monorepo skeleton, build system, and local
infrastructure so every later milestone plugs into a working, reproducible base.

**Files created**
- Root config: `.gitignore`, `.gitattributes`, `.editorconfig`, `.dockerignore`
- Gradle: `settings.gradle.kts`, `build.gradle.kts`, `gradle.properties`,
  `gradle/libs.versions.toml`, wrapper (`gradlew`, `gradlew.bat`,
  `gradle/wrapper/gradle-wrapper.jar` @ Gradle 9.6.1, `.properties`)
- Build logic: `build-logic/settings.gradle.kts`, `build-logic/build.gradle.kts`,
  `build-logic/src/main/kotlin/paymentflow.java-conventions.gradle.kts`
- Modules (build files): `platform-bom`, `common-dto`, `common-lib`, and the eight
  services (`gateway/identity/merchant/payment/transaction/audit/notification/analytics-service`)
- Infra: `docker-compose.infra.yml`, `docker/postgres/init/01-init-schemas.sql`, `.env.example`
- `PROJECT_CONTEXT.md`

**Endpoints added:** none (no application code in M0, by design).

**Database changes:** 7 schemas created on first Postgres boot (identity, merchant,
payment, transaction, audit, notification, analytics); `pgcrypto` extension enabled.

**Kafka topics added:** none (auto-create disabled; topics created explicitly later).

**Redis features added:** password auth + AOF persistence configured (no app usage yet).

**Infrastructure changes:** Local Docker Compose infra — Postgres 17, Redis 8,
Kafka 3.9 (KRaft single-node), Kafka-UI — all with healthchecks, on network
`paymentflow-network`, published on a dedicated host-port range.

**Docker changes:** `docker-compose.infra.yml` (infra-only) added.

**Testing completed:** `./gradlew build` green across all 10 modules (via Gradle
9.6.1 / JDK 25 Docker image); BOM coordinates verified on Maven Central; infra
brought to 4/4 healthy; Postgres schemas + pgcrypto verified; Redis `PING → PONG`;
Kafka topic create/describe/delete round-trip.

**Verification steps** (see "How to run / verify M0" in the milestone hand-off).

**Important design decisions:** D7–D10 (see §9). Convention plugins for zero build
duplication; Java 25 toolchain with Foojay; dedicated infra port range; dot-only
Kafka topic naming.

**Problems faced → solutions**
1. *Host port 6379 already in use* by another local stack → published paymentflow
   infra on a dedicated range (Postgres 55432 / Redis 56379 / Kafka 59092), all
   `.env`-overridable. Kafka's advertised host listener moved in lockstep with the
   published port to avoid the advertised-listener redirect trap.
2. *Kafka failed storage-format validation* (`advertised.listeners cannot use 0.0.0.0`):
   the `apache/kafka:3.9.0` image rejected the explicit `0.0.0.0` bind form and a
   `kafka:9093` controller voter for single-node → switched to empty-host bind
   (`://:port`) and a `localhost:9093` controller voter; verified a clean boot.

**Next milestone:** M1 — shared modules (`common-dto`, `common-lib`): exception
hierarchy, standard error envelope, correlation-id filter, structured JSON logging.

---

### M1 — Shared Modules (`common-dto`, `common-lib`) ✅ (2026-07-17)

**Objectives:** Provide the cross-cutting foundation every service builds on — a
standard error contract, exception hierarchy, correlation-id propagation, and
auto-configured global exception handling — with zero code duplication and without
forcing the servlet stack onto reactive services.

**Files created — common-dto**
- `dto/error/ApiError.java` — immutable error envelope (stable `code`, `correlationId`, field errors)
- `dto/error/ApiFieldError.java` — field violation (no rejected value, by design)
- `dto/page/PageResponse.java` — generic pagination envelope
- Tests: `ApiErrorTest`, `PageResponseTest`

**Files created — common-lib**
- `error/ErrorCode.java` (interface) + `error/CommonErrorCode.java` (generic codes)
- `exception/PlatformException.java` + `ResourceNotFoundException`, `ConflictException`,
  `ValidationException`, `UnauthorizedException`, `ForbiddenException`, `BadRequestException`
- `correlation/CorrelationConstants.java`, `correlation/CorrelationIdFilter.java`
- `web/GlobalExceptionHandler.java` (`@RestControllerAdvice`)
- `autoconfigure/CorrelationIdAutoConfiguration.java`, `autoconfigure/GlobalExceptionHandlerAutoConfiguration.java`
- `resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
- Tests: `CorrelationIdFilterTest`, `GlobalExceptionHandlerTest` (+ `ExceptionTestController`),
  `CommonAutoConfigurationTest`

**Files modified:** `common-dto/build.gradle.kts`, `common-lib/build.gradle.kts` (dependencies).

**Endpoints added:** none (foundation library; exception handler is consumed by services from M2).

**DB / Kafka / Redis / Infra changes:** none.

**Testing completed:** 17 tests green (8 in common-dto, 9 in common-lib). Exception→ApiError
mapping verified end-to-end via standalone MockMvc (404/409/400-validation/400-malformed/500-no-leak);
correlation filter unit-tested (propagate + generate + MDC cleanup); auto-config verified to
activate for servlet apps and stay inactive for non-web apps.

**Important design decisions:** D11–D14 (see §9).

**Problems faced → solutions**
1. *`annotationProcessor` resolved with an empty version* — that configuration doesn't
   extend `implementation`, so the BOM didn't apply → added `annotationProcessor(platform(...))`.
2. *`@WebMvcTest` not found* — Spring Boot 4 split the MVC test slice out of
   `spring-boot-test-autoconfigure` (not pulled by `starter-test`) → rewrote the handler
   test with `MockMvcBuilders.standaloneSetup(...)` (pure `spring-test`): faster, fewer
   deps, and tests the exact same contract. Removed the now-unneeded test bootstrap class.

**Next milestone:** M2 — Identity Service (register/login, BCrypt, JWT access + refresh,
RBAC, Flyway migrations, Testcontainers integration tests).

---

### M2 — Identity Service ✅ (2026-07-17)

**Objectives:** First bootable Spring Boot application — authentication (BCrypt), RS256
JWT access + rotating refresh tokens, RBAC, Flyway-managed `identity` schema, reusing
the M1 foundation, with unit + Testcontainers integration tests.

**Features implemented**
- Register (USER role), login (BCrypt strength 12), token refresh with **rotation**, logout (revoke).
- **RS256** access tokens (id/email/roles claims) signed by an RSA key; **JWKS** endpoint.
- Opaque refresh tokens — only the SHA-256 hash stored; rotated on use; revocable.
- Resource-server security (validates own tokens); URL + method (`@PreAuthorize`) RBAC.
- 401/403/errors rendered as the shared `ApiError`; correlation ids via common-lib.
- Dev admin seeding under the `local` profile only.

**Endpoints added**
| Method | Path | Access |
|---|---|---|
| POST | `/api/v1/auth/register` | public |
| POST | `/api/v1/auth/login` | public |
| POST | `/api/v1/auth/refresh` | public (valid refresh token) |
| POST | `/api/v1/auth/logout` | public (valid refresh token) |
| GET | `/api/v1/users/me` | any authenticated |
| GET | `/api/v1/users` | ADMIN |
| GET | `/oauth2/jwks` | public |
| GET | `/actuator/health` | public |

**Database changes (schema `identity`, Flyway `V1__init_identity.sql`):** tables `users`,
`user_roles`, `refresh_tokens` with FKs, unique constraints (email, token_hash), indexes,
and optimistic-lock `version` on `users`.

**Files created:** ~30 — app + config (`SecurityConfig`, `JwtKeyConfiguration`,
`JwtProperties`, `DevDataInitializer`/`DevAdminProperties`), security (`JwtService`,
`RefreshTokenService` [service], `PemUtils`, `SecurityErrorWriter`, entry-point/denied
handlers), domain (`User`, `RefreshToken`, `Role`), repositories, DTOs, `UserMapper`,
services (`AuthService`, `UserService`), web (`AuthController`, `UserController`,
`JwksController`, `SecurityExceptionHandler`), identity exceptions, `application.yaml`
(+ `-local`), `V1` migration, and 3 test classes.

**Files modified:** `identity-service/build.gradle.kts`, `PROJECT_CONTEXT.md`.

**Test coverage (12 tests, all green):** `JwtServiceTest` (issuance/claims), `AuthServiceTest`
(register dup/normalize/encode, login success/failure — Mockito), `IdentityIntegrationTest`
(Testcontainers Postgres): register→login→/me, duplicate 409, 401 no-token, 403 non-admin,
admin list, refresh rotation (old token rejected), logout revokes.

**Verification:** `./gradlew build` green; service run locally (`SPRING_PROFILES_ACTIVE=local
java -jar …`) against compose Postgres — Flyway migrated, full flow + RBAC confirmed over HTTP.

**Important design decisions:** D15–D21 (see §9).

**Problems faced → solutions**
1. Testcontainers **2.x** renamed artifacts → use `org.testcontainers:testcontainers-postgresql` / `-junit-jupiter`.
2. Boot 4 **modular auto-config**: `@AutoConfigureMockMvc` moved to `spring-boot-webmvc-test`; Flyway needs `spring-boot-flyway` (plain `flyway-core` doesn't wire it).
3. Boot 4 defaults to **Jackson 3** → inject `tools.jackson.databind.ObjectMapper` (no Jackson-2 `ObjectMapper` bean).
4. `@PreAuthorize` denials returned **500** (thrown at the dispatcher, not the filter chain) → added `SecurityExceptionHandler` advice mapping `AccessDeniedException`→403.

**Next milestone:** M3 — Gateway Service (Spring Cloud Gateway, reactive): routing to
identity, edge JWT validation via JWKS, Redis rate-limiting, CORS, security headers,
correlation-id propagation. Completes the first end-to-end vertical slice.

---

### M3 — Gateway Service ✅ (2026-07-18)

**Objectives:** Stand up the platform's reactive edge — routing to identity-service,
JWT validation via JWKS, Redis-backed rate limiting, CORS, security headers, and
correlation-id propagation — completing the first full external-request → gateway →
identity vertical slice.

**Features implemented**
- Single declarative route (`identity-service`) matching `/api/v1/auth/**`,
  `/api/v1/users/**`, `/oauth2/jwks`, proxying unchanged to identity-service.
- Reactive OAuth2 resource server: RS256 JWT verified against identity's live JWKS
  (`NimbusReactiveJwtDecoder`), issuer-checked; `roles` claim mapped to `ROLE_*`
  authorities. Gateway **authenticates only** — RBAC stays in each downstream service
  (D23), matching the existing per-service zero-trust stance (D17).
- Redis-backed `RequestRateLimiter` (token bucket) on every proxied route; key
  resolver is per-authenticated-user (`user:<sub>`) or per-source-IP (`ip:<addr>`)
  for unauthenticated calls — critically, the brute-forceable `/api/v1/auth/**`
  endpoints (D24).
- CORS restricted to a configured origin allow-list (`http://localhost:3000` for the
  future Next.js console), credential-less, `X-Correlation-Id` exposed.
- Security headers via the Spring Security reactive DSL: `X-Frame-Options: DENY`,
  `X-Content-Type-Options: nosniff`, CSP, Permissions-Policy, Referrer-Policy, HSTS.
- `CorrelationIdWebFilter` (reactive, gateway-native — D25/D11): generates or
  preserves `X-Correlation-Id`/`X-Request-Id`, forwards both downstream, and echoes
  the correlation id back to the caller via `ServerHttpResponse.beforeCommit(...)`
  (dedupes against the same header identity-service's own filter also echoes back).
- `GatewayErrorWebExceptionHandler` (reactive, gateway-native — D25/D11) plus a
  dedicated `ServerAuthenticationEntryPoint`/`ServerAccessDeniedHandler`: every
  gateway-originated error (401/403/404-no-route/5xx-downstream) renders the same
  `ApiError` envelope as every other service, reusing `common-dto`'s `ApiError` and
  `common-lib`'s `CommonErrorCode` directly (both stack-agnostic; only the
  servlet-only filter/handler classes in common-lib stay unused here).

**Endpoints added:** none of its own (pure edge/proxy); `/actuator/health`,
`/actuator/info`, `/actuator/prometheus` on the gateway itself.

**Database changes:** none.

**Kafka topics:** none.

**Redis features added:** reactive connection (Lettuce, via
`spring-boot-starter-data-redis-reactive`) backing the gateway's token-bucket rate
limiter; no other Redis usage yet.

**Infra/Docker changes:** none (gateway runs against the existing compose Redis on
host port 56379; no compose file changes needed).

**Files created:** ~13 — app (`GatewayServiceApplication`), config
(`SecurityConfig`, `IdentityServiceProperties`, `GatewayCorsProperties`,
`RateLimiterConfig`), filter (`CorrelationIdWebFilter`), security
(`GatewayErrorResponseWriter`, `RestServerAuthenticationEntryPoint`,
`RestServerAccessDeniedHandler`), web (`GatewayErrorWebExceptionHandler`),
`application.yaml` (+ `-local`), and 2 test classes.

**Files modified:** `gateway-service/build.gradle.kts`, `PROJECT_CONTEXT.md`.

**Test coverage (11 tests, all green):** `CorrelationIdWebFilterTest` (unit: id
generation vs. preservation, response echo). `GatewayIntegrationTest`
(`@SpringBootTest` random port + Testcontainers Redis + a Reactor Netty stub
standing in for identity-service, its own JWKS/RSA key so JWT signature validation
is real, not mocked): public routing, 401 without/with-malformed token, valid-token
routing with Authorization + correlation-id forwarding asserted on the stub side,
404-ApiError for an unmapped path (authenticated — an unauthenticated call to an
unmapped path fails closed at 401 before routing is attempted, asserted
separately), security headers, CORS preflight, and a real Redis 429 once burst
capacity is exceeded.

**Verification:** `./gradlew build` green across all 10 modules; both services run
locally (`SPRING_PROFILES_ACTIVE=local`) against the compose Redis/Postgres —
register → login → gateway-authenticated `/api/v1/users/me` confirmed over real
HTTP with a real signed JWT verified against identity's live JWKS; CORS allow/deny
by origin confirmed; 60 concurrent requests against the real Redis limiter produced
genuine `429`s once burst capacity was exceeded; single (deduped) `X-Correlation-Id`
confirmed on both success and error responses.

**Important design decisions:** D22–D26 (see §9).

**Problems faced → solutions**
1. Spring Cloud Gateway 2025.1.0 renamed the reactive starter to
   `spring-cloud-starter-gateway-server-webflux` and moved route configuration under
   `spring.cloud.gateway.server.webflux.*` (was `spring.cloud.gateway.*`) — confirmed
   against the current reference docs before wiring routes; `KeyResolver` itself
   stayed at its original package despite the module rename.
2. Reactive Spring Security API drift vs. the servlet DSL used in identity-service:
   `ServerAccessDeniedHandler` lives in `...web.server.authorization`, not
   `...web.server.access`; `HstsSpec.includeSubdomains(boolean)` is lowercase-`d`
   (found via `javap` against the resolved jar rather than guessing).
3. `JWKSet.toJSONObject()` returns a plain `Map`, not a JSON-serializing type — the
   test's hand-rolled identity/JWKS stub was feeding `Map.toString()` (Java syntax,
   not JSON) to the decoder, which failed with "Invalid JSON object"; fixed by using
   `JWKSet.toString()` instead. (identity's real `JwksController` was never affected
   — Spring's Jackson message converter serializes the `Map` correctly there.)
4. A response header set *before* `chain.filter()` and one set via `.doFinally()`
   *after* it both failed to produce a single, correct `X-Correlation-Id`: the first
   left a duplicate (identity's own echoed header gets copied onto the gateway
   response by the proxy filter, additively); the second ran too late, since a
   streamed proxy response commits headers as soon as body-writing starts — well
   before the chain's `Mono<Void>` signals completion. Fixed with
   `ServerHttpResponse.beforeCommit(...)`, the purpose-built WebFlux hook for
   mutating headers at the correct instant regardless of when the stream starts.
5. An unauthenticated request to a genuinely unmapped path returns **401**, not
   404 — `anyExchange().authenticated()` runs before route matching is even
   attempted, so it fails closed without leaking whether the path exists. Correct,
   intentional behavior, not a bug; the 404-mapping test authenticates first so it
   actually reaches the "no route matched" branch it's meant to exercise.

**Next milestone:** M4 — Merchant Service (onboarding, API-key issuance, merchant
profile caching).

---

### M4 — Merchant Service ✅ (2026-07-18)

**Objectives:** Merchant onboarding tied to an identity-service user, self-service
API-key issuance and rotation, and Redis cache-aside merchant profile reads — routed
through the gateway alongside identity, extending the vertical slice to a second
downstream service.

**Features implemented**
- Onboarding (`POST /api/v1/merchants`): one merchant profile per identity user,
  enforced at the DB level (`unique (owner_user_id)`); issues the merchant's first
  API key in the same call.
- Ownership derived entirely from the JWT subject — no path/query parameter ever
  names a merchant id for self-service endpoints, so there is no id to guess (D28).
- API keys: opaque, SHA-256-hashed, `pf_`-prefixed, single active key per merchant,
  rotate-in-place (mirrors identity's refresh-token rotation, D16/D29); a partial
  unique index (`WHERE revoked_at IS NULL`) makes "at most one active key" a DB
  guarantee, not just an application-level one.
- Cache-aside merchant profile reads (`GET /me`) via Spring `@Cacheable`, Redis,
  10-minute TTL, JSON-serialized (D30); `PATCH /me` evicts the cache entry so
  updates are never served stale.
- ADMIN-only paginated listing (`GET /api/v1/merchants`), mirroring identity's
  `@PreAuthorize("hasRole('ADMIN')")` pattern exactly.
- merchant-service validates JWTs against identity's JWKS with no signing key of
  its own — identity remains the platform's sole issuer (D17 zero-trust, extended
  to a second service).
- Wired into the gateway: a second route (`/api/v1/merchants/**` → merchant-service);
  the gateway's per-route `RequestRateLimiter` filter was promoted to
  `default-filters` now that there are two routes, instead of repeating the block.

**Endpoints added**
| Method | Path | Access |
|---|---|---|
| POST | `/api/v1/merchants` | any authenticated |
| GET | `/api/v1/merchants/me` | any authenticated (own profile, cached) |
| PATCH | `/api/v1/merchants/me` | any authenticated (own profile, evicts cache) |
| POST | `/api/v1/merchants/me/api-key/rotate` | any authenticated (own key) |
| GET | `/api/v1/merchants` | ADMIN, paginated |

**Database changes (schema `merchant`, Flyway `V1__init_merchant.sql`):** tables
`merchants` (unique `owner_user_id`) and `api_keys` (FK to `merchants`, unique
`key_hash`, partial unique index on `merchant_id` where `revoked_at is null`).

**Kafka topics:** none.

**Redis features added:** cache-aside (`@Cacheable`/`@CacheEvict`) merchant-profile
cache, 10-minute TTL, JSON-serialized via `GenericJacksonJsonRedisSerializer`
(Jackson 3-aware, not the legacy Jackson-2-only serializer — D30).

**Infra/Docker changes:** none (runs against the existing compose Postgres/Redis).

**Files created:** ~20 — app (`MerchantServiceApplication`), domain (`Merchant`,
`ApiKey`), repositories, DTOs, mapper, exception (`MerchantAlreadyExistsException`),
services (`MerchantService`, `ApiKeyService`), config (`SecurityConfig`,
`IdentityServiceProperties`, `CacheConfig`), security (`SecurityErrorWriter`,
`RestAuthenticationEntryPoint`, `RestAccessDeniedHandler`), web
(`MerchantController`, `SecurityExceptionHandler`), `application.yaml`, `V1`
migration, and 3 test classes. Plus common-lib's new `OpaqueTokenGenerator` (D27).

**Files modified:** `merchant-service/build.gradle.kts`,
`identity-service/.../RefreshTokenService.java` (now delegates to
`OpaqueTokenGenerator` instead of its own private hash/generate methods — no
behavior change, regression-tested via the existing identity suite),
`gateway-service/src/main/resources/application.yaml` (merchant route,
`default-filters` refactor), `PROJECT_CONTEXT.md`.

**Test coverage (18 tests, all green):** common-lib's `OpaqueTokenGeneratorTest`
(3, unit). merchant-service's `ApiKeyServiceTest` + `MerchantServiceTest` (8,
Mockito unit). `MerchantIntegrationTest` (7, Testcontainers Postgres + Redis, JWTs
signed against a JDK-`HttpServer`-served test JWKS — no new test dependency):
onboard→get-mine round trip, duplicate-owner 409, 401 with no token, update busts
the cache (asserted by reading the *new* value right after, proving it wasn't
served stale), rotation revokes the old key and issues a distinct new one
(asserted directly against `ApiKeyRepository`), ADMIN-vs-USER on the list endpoint,
and a validation-failure 400.

**Verification:** `./gradlew build` green across all 10 modules; identity, gateway,
and merchant-service run together locally — register → login → onboard a merchant
→ get cached profile → update (cache-bust confirmed) → rotate key → admin list, all
through the gateway over real HTTP with a real Postgres/Redis; 401/403/409 all
confirmed at the edge.

**Important design decisions:** D27–D31 (see §9).

**Problems faced → solutions**
1. Boot 4's modular auto-config (same pattern as D20) split caching out too:
   `RedisCacheManagerBuilderCustomizer` lives in
   `org.springframework.boot.cache.autoconfigure` (module `spring-boot-cache`), not
   `org.springframework.boot.autoconfigure.data.redis` — found via `javap` against
   the resolved jar.
2. Rotating an API key deterministically hit
   `duplicate key value violates unique constraint "uq_api_keys_active_per_merchant"`
   — Hibernate's default flush order is inserts-then-updates *regardless of call
   order*, so the new key's `INSERT` reached Postgres before the old key's
   revoke-`UPDATE` was flushed, and the partial unique index (correctly) rejected
   two simultaneously-active rows for one merchant. Fixed with an explicit
   `saveAndFlush` on the revoke step before issuing the replacement key.
3. Verified `GenericJacksonJsonRedisSerializer` (no "2") takes the Jackson 3
   `tools.jackson.databind.ObjectMapper` directly, unlike the legacy
   `GenericJackson2JsonRedisSerializer` — confirmed via `javap` before wiring
   `CacheConfig`, avoiding a repeat of the D19 Jackson-2-assumption trap.
4. `PROJECT_CONTEXT.md`'s M3 status/decisions/changelog content had reverted to its
   pre-M3 state in the working tree by the time M4 started (M3's code/commit were
   unaffected). Restored per this file's own stated policy — "if code and this
   document disagree, fix whichever is wrong, never leave it stale" — alongside
   the M4 update rather than leaving the milestone log inconsistent with `git log`.

**Next milestone:** M5 — Payment Service (FSM, idempotency, transactional outbox,
Kafka publish). The core of the platform and the first service to actually consume
Kafka and a merchant's API key.

---

### M5 — Payment Service ✅ (2026-07-18)

**Objectives:** The platform's core — an explicit payment finite state machine,
idempotent mutation endpoints (Redis lock + Postgres record), a transactional outbox
publishing to Kafka, and the platform's first synchronous cross-service call
(merchant resolution via OpenFeign) — completing the vertical slice from external
request through to a real Kafka message.

**Features implemented**
- Payment FSM (`CREATED → AUTHORIZED → CAPTURED → REFUNDED`, plus `FAILED`,
  `VOIDED`, `PARTIALLY_REFUNDED`, exactly per §4): an explicit transition table on
  `PaymentStatus`, consulted by every mutation method on the `Payment` entity itself
  — no public setter for `status` exists, so bypassing the FSM isn't just
  discouraged, it's structurally impossible. Capture is all-or-nothing; refund
  supports partial amounts, accumulating to `REFUNDED` once fully refunded (D35).
- Idempotency (§5): `Idempotency-Key` required on every mutating endpoint (D34) →
  Redis `SETNX`-with-TTL lock (fast rejection of an in-flight duplicate, 409) →
  `idempotency_keys` table (durable replay record, scoped per merchant, fingerprint
  = SHA-256 of operation+body via the shared `OpaqueTokenGenerator`). A replayed
  request with a matching fingerprint returns the stored response unprocessed; a
  reused key with a *different* body is rejected (409). `TransactionTemplate` (not
  `@Transactional`) sequences lock → commit → unlock correctly (D33).
- Transactional outbox (D3): every state mutation writes an `outbox_events` row in
  the *same* transaction as the `Payment` change. `OutboxRelay`, a polling
  `@Scheduled` task (no CDC/Debezium in this stack), publishes unpublished rows to
  Kafka and marks them published; a row left unpublished on failure is retried next
  tick — at-least-once (D2), same as a duplicate-publish-on-crash is accepted.
- `EventEnvelope<T>` added to common-dto (D14's deferred abstraction, built now that
  a real producer exists): `eventId` (dedup key for consumers), `eventType`,
  `aggregateId`, `occurredAt`, `correlationId`, `payload`. The concrete
  `PaymentEventPayload` stays local to payment-service, not shared (D36) — extends
  schema-per-service (D4) to messaging contracts.
- Merchant resolution via OpenFeign (D32, confirmed with the user before
  implementing): payment-service calls merchant-service's existing
  `GET /api/v1/merchants/me`, forwarding the caller's own JWT — no new
  merchant-service endpoint or service credential needed. A 404 (no merchant yet)
  maps to a clear 400; connectivity/5xx maps to 503 (no retry/circuit-breaker yet —
  that's M8, deliberately not pulled forward).
- Ownership: every payment carries `merchantId`; cross-merchant access is masked as
  404, not 403 (doesn't confirm another merchant's payment exists).
- JWT validated against identity's JWKS, no signing key of its own (D17, extended to
  a third service) — same pattern as merchant-service.
- Kafka topic (`payment.events`) declared explicitly via a `NewTopic` bean — auto-create
  stays disabled on the broker (D10/M0).

**Endpoints added**
| Method | Path | Access |
|---|---|---|
| POST | `/api/v1/payments` | any authenticated (Idempotency-Key required) |
| POST | `/api/v1/payments/{id}/authorize` | owning merchant only (Idempotency-Key required) |
| POST | `/api/v1/payments/{id}/capture` | owning merchant only (Idempotency-Key required) |
| POST | `/api/v1/payments/{id}/refund` | owning merchant only (Idempotency-Key required) |
| POST | `/api/v1/payments/{id}/void` | owning merchant only (Idempotency-Key required) |
| GET | `/api/v1/payments/{id}` | owning merchant only |
| GET | `/api/v1/payments` | owning merchant, paginated |

**Database changes (schema `payment`, Flyway `V1__init_payment.sql`):** tables
`payments`, `idempotency_keys` (unique `merchant_id`+`idempotency_key`), and
`outbox_events` (partial index on unpublished rows).

**Kafka topics:** `payment.events` (3 partitions, replication factor 1) — the
platform's first real topic and first real producer.

**Redis features added:** `SETNX`-with-TTL distributed lock backing the idempotency
guard — the first real use of the "distributed locks" stack capability noted as
unused since M0.

**Infra/Docker changes:** none (runs against the existing compose Postgres/Redis/Kafka).

**Files created:** ~35 — domain (`Payment`, `PaymentStatus`, `IdempotencyRecord`,
`OutboxEvent`), repositories, exceptions, `event/` (`PaymentEventPayload`,
`PaymentEventPublisher`), `idempotency/IdempotencyService`,
`outbox/OutboxRelay`, `merchant/` (`MerchantClient`, `MerchantResolver`,
`MerchantSummary`, `FeignAuthorizationForwardingConfig`), config
(`SecurityConfig`, `IdentityServiceProperties`, `KafkaTopicConfig`,
`KafkaProducerConfig`, `TransactionTemplateConfig`), security (entry
point/denied handler/error writer), `PaymentService`, `PaymentController`,
`PaymentMapper`, DTOs, `application.yaml`, `V1` migration, and 6 test classes.
Plus common-dto's new `EventEnvelope<T>` (D14/D36).

**Files modified:** `gateway-service/src/main/resources/application.yaml`
(payment-service route), `merchant-service/.../CacheConfig.java` (D38 bug fix —
see Problems below), `merchant-service/.../MerchantIntegrationTest.java`
(regression test for that fix), `PROJECT_CONTEXT.md`.

**Test coverage (51 tests, all green):** common-dto's `EventEnvelopeTest` (4, unit).
payment-service — `PaymentStatusTest` + `PaymentTest` (21, unit: every legal FSM
transition, a wide sample of illegal ones, cumulative partial-refund tracking,
rejected mutations leave state untouched); `IdempotencyServiceTest` (7, Mockito:
lock acquisition/conflict, replay, fingerprint-mismatch rejection, lock released on
both success and failure); `PaymentServiceTest` (7, Mockito: orchestration,
idempotency-key requirement, ownership 404, event-type-per-operation);
`PaymentIntegrationTest` (12, Testcontainers Postgres+Redis + a JDK `HttpServer`
stub serving both identity's JWKS and merchant-service's `/me`, deriving a
deterministic per-subject merchant id — no Kafka needed for this class): full
lifecycle, partial-then-full refund, over-refund rejection, illegal-transition 409,
missing-Idempotency-Key 400, replay-without-duplicating, key-reuse-different-body
409, a genuine two-thread race on the same idempotency key (asserts exactly one
payment results, regardless of which side "wins"), cross-merchant 404-masking,
merchant-not-onboarded 400, no-token 401, validation 400.
`OutboxRelayIntegrationTest` (2, Testcontainers Postgres + Kafka, real broker):
publishes and marks unpublished rows, never republishes an already-published one.
Plus 1 new regression test in merchant-service closing the cache-hit coverage gap
that let D38's bug through M4 (see Lessons Learned, §16).

**Verification:** `./gradlew build` green across all 10 modules; all four services
(identity, gateway, merchant, payment) run together locally against the real
compose Postgres/Redis/Kafka — register → login → onboard merchant → create →
authorize → capture → partial-refund → refund, all through the gateway over real
HTTP; idempotency replay confirmed (identical second response, one payment row);
illegal-transition 409 confirmed; every lifecycle event confirmed landing on the
real `payment.events` topic via `kafka-console-consumer` with correct `eventType`,
`previousStatus`, and propagated `correlationId`.

**Important design decisions:** D32–D38 (see §9).

**Problems faced → solutions**
1. Boot 4's modular auto-config (same pattern as D20) struck again for Kafka:
   `KafkaProperties` lives at `org.springframework.boot.kafka.autoconfigure`
   (module `spring-boot-kafka`), pulled in via the new
   `org.springframework.boot:spring-boot-starter-kafka` — not the raw
   `spring-kafka` dependency. Boot's autoconfigured `KafkaTemplate` is also
   type-erased to `<Object,Object>`, which can't satisfy a `KafkaTemplate<String,String>`
   dependency → declared that bean explicitly (`KafkaProducerConfig`), still sourced
   from `spring.kafka.producer.*` properties via `KafkaProperties`.
2. Hibernate's schema validator rejected `currency char(3)` against a plain JPA
   `String` field (which maps to VARCHAR by default) — a `columnDefinition="char(3)"`
   override didn't resolve it either (a known Hibernate rough edge validating fixed-length
   CHAR columns). Switched to `varchar(3)` (D37) rather than keep fighting the validator.
3. Testcontainers' `KafkaContainer` wait-strategy didn't match `apache/kafka:3.9.0`'s
   log output out of the box → used `ConfluentKafkaContainer` for
   `OutboxRelayIntegrationTest`'s throwaway broker only; the real dev/prod stack
   (`docker-compose.infra.yml`) is unaffected and still runs `apache/kafka` (D9).
4. `OutboxRelayIntegrationTest` was flaky: each test method's fresh Kafka consumer
   group starts from `earliest` and saw *other* tests' messages (fixed by filtering
   on aggregate id, not asserting raw topic-wide counts); separately, the
   app-wide `@Scheduled` background relay tick raced the test's explicit
   `relay()` calls and occasionally double-published the same row before either
   side's commit landed — a real, accepted at-least-once outcome in production
   (D2), but not what this test was trying to exercise, so the background tick is
   pushed out to effectively never fire for this test class.
5. **Manual E2E testing surfaced a real bug in already-committed M4 code**: the
   *second* call to merchant-service's `GET /api/v1/merchants/me` (a genuine Redis
   cache hit, reached this milestone because payment-service's Feign client calls
   it repeatedly across the lifecycle) threw `ClassCastException: LinkedHashMap
   cannot be cast to MerchantResponse`. Root cause: `GenericJacksonJsonRedisSerializer`
   needs type metadata embedded in the cached JSON to reconstruct the concrete
   class on a cache hit (there's no target type to deserialize into ahead of
   time); the 1-arg constructor wrapping the app's shared `ObjectMapper` doesn't
   enable that by default, and M4's own test suite never exercised a real cache-hit
   round trip (its cache-busting test's second read always followed an eviction).
   Fixed in merchant-service's `CacheConfig` using
   `GenericJacksonJsonRedisSerializer.builder().enableDefaultTyping(validator)`
   with a validator scoped to `com.paymentflow.merchant` (D38), plus a new
   regression test closing the coverage gap.

**Next milestone:** M6 — Transaction Service (double-entry ledger; idempotent
consumer of `payment.events`; optimistic locking). The first real Kafka consumer —
`payment.events` finally gets a subscriber.

### M6 — Transaction Service ✅ (2026-07-18)

**Objectives:** The platform's first real Kafka consumer — subscribe to
`payment.events`, post a double-entry ledger for each payment lifecycle event,
idempotently (durable dedup, not just at-least-once delivery), and correctly
under concurrent write contention (optimistic locking with retry).

**Features implemented**
- Double-entry accounting model (D40): `AccountType` (`PLATFORM_CLEARING`
  debit-normal; `MERCHANT_PENDING`, `MERCHANT_SETTLED` credit-normal, both
  scoped to a merchant + currency). `Account.apply(direction, amountMinor)` is a
  pure, table-driven balance function keyed off `AccountType.isDebitNormal()` —
  no per-posting-method special-casing of which direction increases a balance.
- Ledger postings scoped to `Authorized` + `Captured` + `Refunded`/
  `PartiallyRefunded` (D39, confirmed with the user before implementing):
  `Authorized` recognizes a pending obligation (Debit `PLATFORM_CLEARING`,
  Credit `MERCHANT_PENDING`); `Captured` settles it (Debit `MERCHANT_PENDING`,
  Credit `MERCHANT_SETTLED`); a refund reverses funds back out (Debit
  `MERCHANT_SETTLED`, Credit `PLATFORM_CLEARING`); `Voided`/`Failed` reverse the
  pending obligation only if `previousStatus` was `AUTHORIZED` (nothing was ever
  posted if voided/failed straight from `Created`, so there's nothing to
  reverse). Every posting is a balanced two-leg `LedgerTransaction` (debit ==
  credit), using the event's own incremental amount, not a running total (D41).
- Idempotent consumption (D2, extended to a real consumer): `processed_events`
  (unique `event_id`) makes a redelivered event a durable no-op, checked inside
  the same transaction as the posting — not just relying on Kafka's
  at-least-once semantics or consumer-group offset tracking.
- Optimistic locking + retry (`Account.version`): every event touching a given
  currency's shared `PLATFORM_CLEARING` account can race with concurrent
  partitions/listener-concurrency; the whole (short, idempotent-on-retry)
  transaction is retried up to 10 times with jittered backoff on
  `OptimisticLockingFailureException`/`DataIntegrityViolationException` — not
  just the account update, since Postgres aborts the rest of a transaction
  after any constraint violation.
- `PaymentLedgerEventPayload` is transaction-service's own local mirror of the
  event shape (D36, extended to a second real consumer) — no compile-time
  dependency on payment-service's internal `PaymentEventPayload` class.
- No REST API, security, or OpenFeign client (D42) — the service's only inbound
  interface is the Kafka stream, matching the approved roadmap scope exactly.

**Endpoints added:** none (D42 — by design; see Problems/Decisions).

**Database changes (schema `transaction`, Flyway `V1__init_transaction.sql`):**
tables `accounts` (partial unique indexes: one `PLATFORM_CLEARING` account per
currency; one `MERCHANT_PENDING`/`MERCHANT_SETTLED` account per merchant+currency),
`ledger_transactions` (references `payment_id`, `event_id`, `event_type`),
`ledger_entries` (FK to `ledger_transactions` and `accounts`, `direction`,
`amount_minor`, `currency`), `processed_events` (unique `event_id`).

**Kafka topics:** none added — `transaction-service-payment.events` consumer
group subscribes to the existing `payment.events` topic (`auto-offset-reset:
earliest`, listener concurrency 3).

**Redis features added:** none (transaction-service uses no Redis).

**Infra/Docker changes:** none (runs against the existing compose
Postgres/Kafka; no Redis dependency).

**Files created:** ~20 — domain (`Account`, `AccountType`, `Direction`,
`LedgerTransaction`, `LedgerEntry`, `ProcessedEvent`), repositories
(`AccountRepository`, `LedgerTransactionRepository`, `LedgerEntryRepository`,
`ProcessedEventRepository`), `event/PaymentLedgerEventPayload`,
`listener/PaymentEventListener`, `service/LedgerService`, config
(`TransactionTemplateConfig`), `TransactionServiceApplication`, `V1` migration,
`application.yaml`, and 3 test classes (`AccountTest`, `LedgerServiceTest`,
`TransactionIntegrationTest`).

**Files modified:** `payment-service/.../event/PaymentEventPayload.java` (added
`eventAmountMinor`, D41), `payment-service/.../event/PaymentEventPublisher.java`
(signature now takes the event amount), `payment-service/.../service/PaymentService.java`
(refactored `mutate()` around a private `MutationOutcome` record so every
mutation path supplies its own event-specific amount), `payment-service/.../PaymentServiceTest.java`
(updated `verify(eventPublisher).publish(...)` call sites to the new 4-arg
signature), `PROJECT_CONTEXT.md`.

**Test coverage (19 tests, all green):** `AccountTest` (5, unit: debit/credit-normal
balance math for both account polarities, new-account zero balance).
`LedgerServiceTest` (10, Mockito: `Created` has no ledger impact but is still
recorded processed, already-processed events skip entirely, correct
debit/credit/account-type posting for authorize/capture/refund, void/fail
reversal only when previously authorized, void/fail from `Created` posts
nothing, retry-then-succeed on optimistic-lock conflict, retries exhausted
throws). `TransactionIntegrationTest` (4, Testcontainers Postgres +
`ConfluentKafkaContainer` real broker, explicit topic creation via `AdminClient`
in `@BeforeAll` to avoid relying on lazy auto-create timing): full lifecycle
posts correct entries and nets every account to zero once fully refunded,
redelivering the same event is an idempotent no-op, voiding after
authorization reverses back to zero, and a 10-thread concurrent-posting test
against one shared clearing account (isolated on its own currency to avoid
cross-test balance pollution) retries under contention and never loses an
update. Full suite confirmed stable across 3 repeated runs.

**Verification:** `./gradlew build` green across all 11 modules; all five
services (identity, gateway, merchant, payment, transaction) run together
locally against the real compose Postgres/Redis/Kafka — a full
register→login→onboard→create→authorize→capture→partial-refund→refund
lifecycle driven through the gateway via curl, followed by direct `psql`
queries against the real `transaction` schema: exactly 8 ledger entries across
4 balanced transactions with correct event types, directions, account types,
and amounts (20000/20000/8000/12000 — confirming `eventAmountMinor` carries
incremental deltas, not running totals); all three accounts (merchant pending,
merchant settled, platform clearing) netted to `0` after the fully-refunded
lifecycle; `accounts.version` showed correct optimistic-lock increments (1, 2,
2); `processed_events` count of 5 matched all events including the no-op
`PaymentCreated`. Also confirmed transaction-service's brand-new consumer
group gracefully dropped stale, pre-`eventAmountMinor`-shape messages left
over from M5's manual testing (logged and skipped, not a crash) before
processing fresh, correctly-shaped events without issue. All five services
then stopped cleanly, confirmed down via health-check probes on ports
8080–8084.

**Important design decisions:** D39–D42 (see §9).

**Problems faced → solutions**
1. `PaymentEventPayload` had no way to carry a refund's incremental amount
   (only the running `amountMinor`/`capturedAmountMinor`/`refundedAmountMinor`
   totals) — added `eventAmountMinor`, requiring a coordinated change across
   `PaymentEventPublisher`'s signature, `PaymentService.mutate()`'s new
   `MutationOutcome` record, and the test suite's `verify()` call sites (D41).
2. **The core bug, caught before it ever reached a running system**:
   `LedgerService.post()` originally read `debitAccount.getId()`/
   `creditAccount.getId()` to build `LedgerEntry` rows *before* the accounts
   were saved — for any brand-new account (every integration test's first
   posting), the id was still null, so the very first insert of any run
   violated `ledger_entries.account_id`'s not-null constraint. Fixed by
   reordering: apply the balance change and `save()` both accounts first
   (client-side `GenerationType.UUID` populates `getId()` immediately on
   `save()`), then build the `LedgerTransaction`/`LedgerEntry` rows referencing
   the now-populated ids.
3. `getOrCreateAccount` was saving a brand-new account once on creation and
   again after `post()` applied the balance — caught by
   `LedgerServiceTest`'s `times(2)` assertions failing with
   `TooManyActualInvocations`. Fixed by not saving inside
   `getOrCreateAccount`; `post()`'s later save (needed anyway, for the balance
   update) handles the insert for a new account too.
4. Missing `jakarta.validation-api` caused a `ClassNotFoundException` for
   `jakarta.validation.ConstraintViolationException` at startup — common-lib's
   `GlobalExceptionHandler` references that class whenever
   `spring-boot-starter-web` is on the classpath (D11), even though
   transaction-service does no request-body validation of its own. Added
   `spring-boot-starter-validation`.
5. `MAX_ATTEMPTS = 3` (a reasonable-sounding first guess) proved insufficient
   under the 10-thread concurrency test's contention on one shared clearing
   account — retries were exhausted before all 10 postings landed. Raised to
   10 with jittered backoff (base 20ms × attempt + random jitter), which
   comfortably absorbed the contention without unbounded blocking.
6. Multiple `TransactionIntegrationTest` methods shared the same "USD"
   `PLATFORM_CLEARING` singleton account, so the concurrency test's 10
   postings corrupted the other tests' balance assertions. Fixed by running
   the concurrency test on its own dedicated currency ("CHF"), isolating it
   from the other three tests' shared "USD" state.
7. Kafka topic auto-create is disabled platform-wide (D10) — relying on it
   implicitly for the test's throwaway broker risked the consumer subscribing
   before the topic existed. Added an explicit
   `AdminClient.createTopics(new NewTopic(...))` call in `@BeforeAll`.
8. During manual E2E: transaction-service's brand-new consumer group (`auto-offset-reset:
   earliest`) replayed stale, pre-`eventAmountMinor` messages left over from
   M5's own manual-testing session, producing a Jackson
   `MismatchedInputException` (`Cannot map 'null' into type 'long'`). Confirmed
   this was *not* a bug: `PaymentEventListener`'s pre-existing catch-log-drop
   behavior handled the malformed message correctly, and consumption of
   subsequent, correctly-shaped messages continued without disruption.

**Next milestone:** M7 — Audit + Notification + Analytics (event-consumer
services, webhooks, dead-letter queue).

---

### M7 — Audit + Notification + Analytics ✅ (2026-07-18)

**Objectives:** Give `payment.events` its remaining three consumers — an immutable
audit trail, webhook/email notification delivery with real retry-and-DLQ semantics,
and per-merchant reporting aggregates — completing the roadmap's Phase 1 fan-out
diagram (§4) in full for the first time.

**Features implemented**
- **merchant-service extension (not a redesign):** a nullable `webhook_url` column,
  a self-service `PATCH /api/v1/merchants/me/webhook` (HTTPS-only, cache-evicting,
  mirrors the existing profile-update endpoint exactly), and `MerchantResponse`/
  `MerchantMapper` updated to surface it.
- **payment-service extension:** `MerchantSummary` now carries `contactEmail` and
  `webhookUrl` (not just `id`); `MerchantResolver.resolveCallerMerchant()` returns
  the full summary once per request (no new Feign call — reuses the existing
  merchant-resolution round trip); `PaymentEventPayload` embeds both fields, so
  every event already contains everything a notification consumer needs (D43,
  confirmed with the user before implementing).
- **audit-service:** consumes `payment.events`, parses each message as a generic
  JSON tree (not a typed payload class, D44), and appends one immutable row per
  event to `audit_log` (unique `event_id` enforces dedup, D2) — a concurrent
  duplicate insert is caught and swallowed as a benign race, not retried, since
  there is nothing to redo for an already-recorded event.
- **notification-service:** for every event, always writes a simulated `email_log`
  row (D45 — no real SMTP/SES yet) to the merchant's `contactEmail`; if the
  merchant has a `webhookUrl` configured, durably records delivery intent
  (`webhook_deliveries`, `PENDING`) in the same short transaction, then attempts
  the first HTTP POST synchronously right after commit. A failure publishes the
  event id to an explicitly-declared `payment.events.retry` topic; a dedicated
  retry listener backs off (jittered exponential, mirroring `LedgerService`'s M6
  shape) and retries up to 5 total attempts before dead-lettering to
  `payment.events.dlq` (D46). No merchant webhook configured means no row and no
  attempt at all — not a failure.
- **analytics-service:** consumes `payment.events` and maintains one
  `merchant_payment_stats` row per (merchant, currency), incrementing
  created/authorized/captured/refunded/voided counters and accumulating
  captured/refunded amounts (using each event's incremental `eventAmountMinor`,
  same as M6's ledger). Every event for one merchant+currency contends on the same
  row, so the whole transaction is retried with optimistic-lock backoff, reusing
  `LedgerService`'s exact M6 pattern (D47).
- All three new services ship no REST API, no Spring Security, no OpenFeign client
  (D42, extended) — Kafka is their only inbound interface.

**Endpoints added**
| Method | Path | Access |
|---|---|---|
| PATCH | `/api/v1/merchants/me/webhook` | any authenticated (own profile; merchant-service) |

**Database changes:**
- `merchant` schema, `V2__add_webhook_url.sql`: nullable `webhook_url` on `merchants`.
- `audit` schema, `V1__init_audit.sql`: `audit_log` (unique `event_id`, `jsonb` payload).
- `notification` schema, `V1__init_notification.sql`: `processed_events`,
  `email_log` (unique `event_id`), `webhook_deliveries` (unique `event_id`,
  `jsonb` payload, `status`/`attempt_count`/`version`).
- `analytics` schema, `V1__init_analytics.sql`: `processed_events`,
  `merchant_payment_stats` (unique `merchant_id`+`currency`, `version`).

**Kafka topics:** `payment.events.retry` and `payment.events.dlq` (3 partitions,
replication 1 each) — declared explicitly via `NewTopic` beans in
notification-service (D10), the platform's first producer of topics other than
`payment.events` itself. `payment.events` gets three new consumer groups:
`audit-service-payment.events`, `notification-service-payment.events` (plus
`notification-service-payment.events.retry` on the retry topic),
`analytics-service-payment.events`.

**Redis features added:** none (none of the three new services use Redis).

**Infra/Docker changes:** none to compose itself; discovered during manual
verification that host port 8085 was already claimed by `docker-compose.infra.yml`'s
Kafka-UI container, so the three new services were assigned 8091/8092/8093 instead
of the sequentially-expected 8085–8087 (D48).

**Files created:** ~45 across three new services — audit-service (`AuditLogEntry`,
`AuditLogEntryRepository`, `AuditService`, `AuditEventListener`,
`AuditServiceApplication`, migration, `application.yaml`, 2 test classes);
notification-service (`ProcessedEvent`, `EmailLogEntry`, `DeliveryStatus`,
`WebhookDelivery`, their repositories, `PaymentNotificationEventPayload`,
`NotificationService`, `WebhookDeliveryService`, `NotificationEventListener`,
`WebhookRetryListener`, config `NotificationProperties`/`KafkaTopicConfig`/
`KafkaProducerConfig`/`TransactionTemplateConfig`/`WebhookClientConfig`,
`NotificationServiceApplication`, migration, `application.yaml`, 4 test classes);
analytics-service (`ProcessedEvent`, `MerchantPaymentStats`, their repositories,
`AnalyticsEventPayload`, `AnalyticsService`, `PaymentEventListener`, config
`TransactionTemplateConfig`, `AnalyticsServiceApplication`, migration,
`application.yaml`, 3 test classes). Plus merchant-service's `UpdateWebhookRequest`
DTO and `V2` migration.

**Files modified:** `merchant-service/.../domain/Merchant.java`,
`dto/MerchantResponse.java`, `mapper/MerchantMapper.java`,
`service/MerchantService.java`, `web/MerchantController.java`, and their tests;
`payment-service/.../merchant/MerchantSummary.java`, `MerchantResolver.java`,
`event/PaymentEventPayload.java`, `event/PaymentEventPublisher.java`,
`service/PaymentService.java`, and their tests (`PaymentServiceTest`,
`PaymentIntegrationTest`'s merchant-service stub JSON); `PROJECT_CONTEXT.md`.

**Test coverage (39 new tests, all green):** merchant-service — 4 new tests in
`MerchantServiceTest`/`MerchantIntegrationTest` (set/clear webhook, cache-bust,
HTTPS-only rejection). audit-service — `AuditServiceTest` (4, Mockito: new event
recorded verbatim, already-recorded skip, null-correlation-id handling,
concurrent-duplicate-insert swallowed) + `AuditIntegrationTest` (3, Testcontainers
Postgres+Kafka real broker: a real event recorded, redelivery is a no-op, a
malformed message is dropped without crashing the consumer). notification-service —
`NotificationServiceTest` (5, Mockito: skip-if-processed, email always logged, no
webhook configured means no delivery row/attempt, blank URL treated as absent, a
configured webhook creates a `PENDING` row and triggers delivery post-commit);
`WebhookDeliveryServiceTest` (3, a real JDK `HttpServer` stub: 2xx marks
delivered, non-2xx and unreachable-URL both record a failed attempt and publish to
the retry topic); `WebhookRetryListenerTest` (5, Mockito: malformed event id
dropped, unknown event id no-op, already-resolved delivery no-op, exhausted
attempts dead-letter instead of retrying, an attempt below the limit is retried);
`NotificationIntegrationTest` (5, Testcontainers Postgres+Kafka + the same
HttpServer-stub pattern: no-webhook event only logs email, a configured webhook
delivers on the first attempt, redelivery doesn't duplicate either row, a failing
webhook retries then eventually delivers once the sink recovers, a malformed
message is dropped without crashing the consumer). analytics-service —
`MerchantPaymentStatsTest` (5, unit: counter/amount math per event type);
`AnalyticsServiceTest` (9, Mockito: dedup skip, one increment per status,
existing-row reuse, retry-then-succeed, exhausted retries); `AnalyticsIntegrationTest`
(3, Testcontainers Postgres+Kafka real broker: full-lifecycle aggregate correctness,
redelivery no-op, a 10-thread concurrency test on one shared
merchant+currency row proving optimistic-lock retry never loses an update).

**Verification:** `./gradlew build` green across all 14 modules; every new/changed
test suite re-run 2–3 times with no flakiness. All 8 services run together locally
against the real compose Postgres/Redis/Kafka — a merchant configured its webhook
via `PATCH /api/v1/merchants/me/webhook` (rejected a plain-`http://` URL first,
confirming the HTTPS-only validation), then a full create→authorize→capture→
partial-refund→refund lifecycle was driven through the gateway: all 5 events
landed verbatim in `audit_log`; 5 simulated emails were logged to the merchant's
`contactEmail`; all 5 webhooks were delivered on the first attempt to a real
throwaway local HTTP sink, with the received request bodies confirming
`merchantContactEmail`/`merchantWebhookUrl` correctly embedded per event; the
`merchant_payment_stats` aggregate showed the exact expected counts and amounts.
A second merchant with a deliberately unreachable webhook URL was driven through
the real retry topic and correctly reached `DEAD_LETTERED` after 5 attempts,
confirmed both via `psql` (`webhook_deliveries.status`/`attempt_count`) and the
retry listener's dead-letter log line. All 8 services were then stopped cleanly
and confirmed down via health-check probes on ports 8080–8084/8091–8093.

**Important design decisions:** D43–D48 (see §9).

**Problems faced → solutions**
1. The AskUserQuestion-confirmed design (D43) required threading a fuller
   `MerchantSummary` (not just `id`) through `MerchantResolver` and
   `PaymentEventPublisher.publish(...)`'s signature — updated all four
   `PaymentService` call sites and the `PaymentServiceTest`/`PaymentIntegrationTest`
   stub JSON in lockstep, the same shape of change as M6's `eventAmountMinor` addition.
2. Discovered mid-manual-verification that host port 8085 (originally planned for
   audit-service, following the 8081–8084 sequence) was already claimed by
   `docker-compose.infra.yml`'s Kafka-UI container — audit-service failed to bind
   on startup. Reassigned all three new services to 8091/8092/8093 (D48) rather
   than renumber the long-established Kafka-UI port.
3. Manually testing real webhook delivery required an HTTPS URL (the merchant-service
   validation correctly enforces `https://`), but the throwaway local test sink
   was plain HTTP. Rather than standing up a self-signed local HTTPS listener
   (extra scope for a manual smoke check the automated `NotificationIntegrationTest`
   already covers end-to-end over real HTTP), the webhook URL was set directly via
   `psql` for this one verification step, with the merchant-service cache busted
   via the existing profile-update endpoint so the change was visible — a
   deliberate, scoped-down manual-testing shortcut, not a gap in the actual
   HTTPS-only validation (which the integration test suite does verify at the API
   layer).
4. Confirmed a genuine architectural question before implementing rather than
   guessing: notification-service (an async Kafka consumer) had no caller JWT to
   forward, so it couldn't reuse payment-service's existing
   OpenFeign-with-forwarded-JWT pattern (D32) to resolve a merchant's webhook URL
   synchronously. Event-carried delivery info (D43) sidesteps the problem
   entirely rather than inventing a new service-to-service auth mechanism.

**Next milestone:** M8 — Resilience4j (circuit breakers, retries, timeouts,
bulkheads) around payment-service's synchronous Feign call to merchant-service —
the one remaining unprotected point of synchronous coupling in the platform.


