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

- **Current milestone:** M11 (Terraform Infrastructure) — *pending approval*
- **Completed milestones:** M0 (repo bootstrap) ✅ · M1 (shared modules) ✅ · M2 (Identity Service) ✅ · M3 (Gateway Service) ✅ · M4 (Merchant Service) ✅ · M5 (Payment Service) ✅ · M6 (Transaction Service) ✅ · M7 (Audit + Notification + Analytics) ✅ · M8 (Resilience4j) ✅ · M9 (Containerization) ✅ · M10 (CI/CD) ✅
- **Pending milestones:** M11–M15

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
| D49 | `MerchantResolver`'s Retry→CircuitBreaker→TimeLimiter→ThreadPoolBulkhead chain is composed programmatically in Java against the Spring-managed Resilience4j registries (each component's own `CompletionStage` decorator), not via `@CircuitBreaker`/`@Retry`/`@TimeLimiter`/`@Bulkhead` annotations | the annotation-driven AOP style Resilience4j's Spring Boot starter also supports | `ThreadPoolBulkhead` only ever returns a `CompletionStage`, never a plain `Callable`/`Future` — combining it with `@TimeLimiter`/`@CircuitBreaker`/`@Retry`'s *annotation* form correctly requires getting their AOP `@Order` aspect-ordering exactly right (undocumented/easy to get backwards) and pulls in `spring-boot-starter-aop`; explicit Java composition makes the intended nesting (outermost Retry, innermost Bulkhead) directly readable in one place and needs no new dependency. The registries are still Spring-managed beans, so Micrometer/Actuator metrics binding (D-requirement, see below) works identically either way |
| D50 | Exponential-backoff-with-jitter for the `merchantService` retry instance is configured via a programmatic `RetryConfigCustomizer` bean (`MerchantResilienceConfig`) reading externalized `paymentflow.resilience.merchant-service.retry-*` properties, not plain `resilience4j.retry.instances.*` YAML | find a way to express it in YAML alone | Resilience4j's YAML-bound `RetryProperties` only supports `enableExponentialBackoff` XOR `enableRandomizedWait` as mutually exclusive flags — there is no plain-YAML path to `IntervalFunction.ofExponentialRandomBackoff(...)` (both combined), which is what "exponential backoff with jitter" actually means; the `RetryConfigCustomizer` extension point is Resilience4j's own supported way to reach APIs YAML can't express, while keeping the actual numbers externalized rather than hardcoded |
| D51 | CircuitBreaker/Retry `recordExceptions`/`retryExceptions` for `merchantService` are an explicit whitelist (`feign.RetryableException`, `feign.FeignException$FeignServerException`, `java.util.concurrent.TimeoutException`); `ignoreExceptions` explicitly lists `MerchantNotOnboardedException`, `feign.FeignException$FeignClientException`, **and** `io.github.resilience4j.bulkhead.BulkheadFullException` | leave `BulkheadFullException` off both lists, since a bulkhead rejection isn't a merchant-service health signal | Resilience4j's actual semantics (confirmed via a real bug caught while manually verifying this milestone, not a hypothetical): once `recordExceptions` is non-empty, *any* exception that matches neither list is counted as a **success**, not as "uncounted." Leaving `BulkheadFullException` off both lists would have made sustained bulkhead saturation look like a 100%-healthy merchant-service to the circuit breaker — exactly backwards. Explicitly ignoring it restores the intended "bulkhead rejections reflect this instance's own saturation, not downstream health" semantics as true neutrality, not accidental false-success |
| D52 | `ThreadPoolBulkhead` dispatches the Feign call onto its own dedicated thread pool, not the calling Servlet thread, and `MerchantResolver` explicitly captures the caller's `RequestAttributes` before dispatch and re-binds (then clears) them on the bulkhead thread around the call | let `FeignAuthorizationForwardingConfig`'s interceptor read `RequestContextHolder` on whatever thread happens to run the call | `RequestContextHolder` is a plain (non-inheritable) `ThreadLocal` — without this, moving the call to a different thread pool (the entire point of `ThreadPoolBulkhead`, isolating a hung downstream from the app's main request threads) would silently stop forwarding the caller's JWT, breaking merchant resolution for every payment operation. Found and fixed during implementation before it ever reached a running system, the same way M6's account-save-ordering bug was — not discovered via production behavior |
| D53 | One parameterized, shared `Dockerfile` at the repo root (`ARG SERVICE_MODULE`, `ARG SERVICE_PORT`), built once per service via `docker-compose.yml`'s per-service `build.args` | eight near-identical per-service Dockerfiles | M9's own "no duplicated code" requirement applies to Dockerfiles as much as to Java — every service in this monorepo builds and packages identically (same Gradle multi-module graph, same Spring Boot layered-jar packaging, same JRE, same non-root user, same healthcheck shape); the only real per-service inputs are which Gradle module to build and which port to expose, both cleanly expressed as build args |
| D54 | The builder stage runs the real Gradle wrapper (`./gradlew :<module>:bootJar`) against the actual monorepo — copying in build files first (their own Docker layer) and then only the requested module's `src/` plus `common-dto`/`common-lib` — rather than building jars on the host and `COPY`-ing a prebuilt artifact into the image | build outside Docker and `COPY` a prebuilt jar in | The image is built from exactly the same Gradle/Java-25 toolchain invocation a developer runs locally (reproducible builds, requirement #3) with no separate host-side build step to keep in sync; schema-per-service's "no cross-service coupling" extends to the Docker build context too, since no sibling service's source is ever copied in to build one image |
| D55 | Runtime images extract the Spring Boot layered jar (`java -Djarmode=tools ... extract --layers --launcher`) onto `eclipse-temurin:25-jre-alpine`, copied in as four separate `COPY --from=builder` layers (dependencies → spring-boot-loader → snapshot-dependencies → application, least- to most-often-changing) | ship the plain uber-jar (`java -jar app.jar`) in a single `COPY` | A pure application-code change only busts the final (application) layer during a later rebuild, not the three dependency layers underneath it (requirement #2's "optimize image size using layered builds"); confirmed working end-to-end with a real local extract-and-run before committing to the approach, not assumed from documentation alone |
| D56 | `docker-compose.yml` (M9) holds only the eight application services, not a second copy of Postgres/Redis/Kafka/Kafka-UI, and is designed to always run merged with the existing `docker-compose.infra.yml` via multiple `-f` flags (`docker compose -f docker-compose.infra.yml -f docker-compose.yml up -d`) | duplicate the infra service definitions into one all-in-one compose file | Realizes the split M0 already forecast ("Application services get their own compose file in a later phase") without duplicating infra config; critically, `depends_on: condition: service_healthy` only resolves across service definitions known to the *same* Compose model, so merging via `-f` (not two independently-run projects) is what makes postgres/redis/kafka health-gating the app services actually work, not just an aesthetic file-organization choice |
| D57 | CI (`.github/workflows/ci.yml`) caches Gradle via `gradle/actions/setup-gradle@v4`, not a hand-rolled `actions/cache` step keyed on hashed lockfiles | a manual `actions/cache` block over `~/.gradle/caches` and the wrapper distribution | `gradle/actions/setup-gradle` is the current, actively-maintained mechanism for this in Actions — it caches dependencies, the wrapper distribution, and build-cache entries together, adds a build summary/provenance, and is what the Gradle project itself now recommends over the older `gradle-build-action`/manual-cache pattern. Deliberately not combined with `actions/setup-java`'s own `cache: gradle` option, which would just double-cache the same artifacts |
| D58 | The `docker-build` job's matrix caps concurrency at `max-parallel: 4` rather than leaving it unbounded (8 legs at once) | let all 8 matrix legs run in parallel, one per service | M9 found that building all 8 images concurrently exhausts a *single shared* Docker Desktop VM's memory (7 Gradle daemons, one machine). GitHub-hosted runners are isolated VMs per matrix leg, so that exact failure mode doesn't reproduce identically here — but the underlying lesson (never let unbounded concurrent image builds outrun the resources actually available) still applies, this time against the account's concurrent-job quota rather than local VM memory; capping at 4 is the conservative, quota-conscious choice that honors the lesson without needing to actually re-trigger the failure in CI to justify it |
| D59 | Docker images are built and GHCR-tagged (`ghcr.io/<owner>/<service>:latest` and `:<sha>`) with `push: false, load: true` — built and verified locally on the runner, never pushed to any registry | actually push to GHCR now, or skip building/tagging images at all until a later milestone | The user's explicit scope for M10 is "design so it can later push to GHCR without major restructuring... do NOT implement deployment yet." Tagging in the final GHCR-qualified shape now means enabling push later is exactly two changes (add `packages: write` to `permissions:`, flip `push: false`→`true` alongside the already-written, currently-commented-out `docker/login-action` step) — no restructuring of the job, matrix, or tagging logic |
| D60 | The `docker-build` job declares `needs: build-and-test`, so a failing Gradle build/test skips all 8 Docker builds entirely | run Gradle and Docker builds as independent, parallel jobs | "Fail immediately if any test fails" (explicit M10 requirement) extends naturally to not spending Docker-build minutes validating images built from code that doesn't even pass its own test suite — mirrors this project's whole milestone-gated philosophy (verify before proceeding) applied to a single CI run's own internal ordering |
| D61 | Each matrix leg ends with a real automated assertion (`docker inspect` checked against the non-root user, the exposed port, and the presence of a `HEALTHCHECK`) rather than treating "the image built" as sufficient verification | trust `docker build`'s exit code alone | A Dockerfile edit that silently dropped `USER`, `EXPOSE`, or `HEALTHCHECK` (all established in M9, D53–D55) would still `docker build` successfully — this is the automated, CI-native equivalent of the same "verify, don't assume" discipline M9's manual verification already applied by hand; deliberately does *not* boot the app against real Postgres/Kafka in CI (that would need service containers/matrix-wide infra plumbing well beyond "build Docker images for every service" — deferred as YAGNI until a real need for in-pipeline integration testing exists, same reasoning as D14/D31/D42) |

---

## 10. Risks
- Scope explosion across 8 services → mitigated by depth-first build order.
- Kafka/KRaft local resource use on dev machine → infra-only compose file for fast loop.
- AWS cost during Phase 5 → single small RDS/ElastiCache, teardown scripts, cost notes.

## 11. Known Issues
- Gateway does not yet honor `X-Forwarded-*`/`Forwarded` headers (Spring Cloud Gateway 2025.x disables this by default unless `spring.cloud.gateway.server.webflux.trusted-proxies` is set). Irrelevant with no reverse proxy in front locally; must be configured in M12 once the gateway sits behind an ALB, or HSTS/scheme-dependent behavior will see the wrong (plaintext) scheme.
- Gateway-local log lines do not carry `correlationId`/`requestId` via MDC (WebFlux isn't thread-bound per request); the header still propagates correctly across the wire. Full reactive log correlation is deferred to M13 (see D26).
- No merchant-API-key-based auth path exists for payment creation (see D32) — only JWT-via-gateway, matching §4. Deferred until a real server-to-server caller for it exists.
- ~~No circuit breaker/retry/fallback around payment-service's Feign call to merchant-service~~ — resolved in M8 (D49–D52).
- Concurrent duplicate requests sharing an `Idempotency-Key` fail fast (409) rather than blocking briefly and replaying once the first completes. Simpler and deterministic; a documented simplification, not a bug.
- transaction-service has no query API for ledger/account balances (see D42) — verifying ledger state today requires a direct `psql` query against the `transaction` schema. Deferred until a real consumer for that data exists.
- Every event touching a given currency's shared `PLATFORM_CLEARING` account contends on the same row under concurrent load; the retry-with-jittered-backoff loop (`MAX_ATTEMPTS = 10`) handles this correctly today, but a high-throughput production system would eventually want sharded clearing accounts or a queue-per-account model instead of optimistic-lock retries. Not a concern at this platform's scale.
- notification-service's "email" channel is simulated/logged only (D45) — no real SMTP/SES provider is wired up, so nothing is actually emailed. Deferred until a provider is chosen.
- notification-service's webhook delivery has no HMAC/signature scheme yet — a receiving merchant endpoint can't cryptographically verify a webhook actually came from this platform. Real payment platforms sign webhook bodies; deferred as out of scope for this milestone's "webhook delivery + retry + DLQ" line item.
- audit-service/analytics-service (like transaction-service, D42) have no query API for their data yet — verifying audit/aggregate state today requires a direct `psql` query. Deferred until a real consumer for that data exists (candidate: M15's merchant console).
- Concurrent duplicate webhook-delivery attempts for the same event are not possible by construction today (only the main listener's inline attempt and the dedicated retry listener ever touch a `webhook_deliveries` row, never both at once) — `WebhookDelivery`'s `@Version` is defensive only, not load-bearing yet.
- No concrete Micrometer registry implementation (e.g. `micrometer-registry-prometheus`) is wired into any service yet — Resilience4j's meters are correctly bound to Micrometer's meter-registration SPI (proven via `MerchantResilienceIntegrationTest` reading `MeterRegistry` directly, since Spring Boot Test supplies its own `SimpleMeterRegistry`), but the real running app has no concrete registry backing it, so `/actuator/metrics/resilience4j.*` returns nothing meaningful today — every recorded metric is silently discarded by Boot's default empty `CompositeMeterRegistry`. This is exactly M13's job ("Ensure they will later integrate with Prometheus in M13"), not pulled forward.
- TimeLimiter's `cancelRunningFuture(true)` cancels the `CompletableFuture` the caller is waiting on, but does not interrupt the underlying blocking Feign HTTP call already in flight on its `ThreadPoolBulkhead` thread — the real socket read keeps running in the background until it completes or the Feign-level `read-timeout-ms` fires on its own. The caller still gets a fail-fast response either way (that's what TimeLimiter is for); this only means "abandoned" calls linger briefly on the bulkhead's own small pool, not the application's main threads. Feign's own socket timeouts (`paymentflow.resilience.merchant-service.read-timeout-ms`) are kept comfortably under TimeLimiter's budget specifically so this window stays short.
- Container images run on `eclipse-temurin:*-alpine` (musl libc), so Reactor Netty (gateway-service) falls back to its pure-Java NIO transport instead of the native epoll transport available on glibc hosts. Functionally identical, slightly less throughput under very high concurrency — irrelevant at this platform's local-dev/demo scale; worth a plain (non-Alpine) base image only if a future load-testing milestone (M14) shows it matters.
- Building all 8 Docker images with Compose's default parallel-build behavior on this dev machine (16 CPUs, ~11.5GB allocated to the Docker Desktop VM) overloads the daemon — each build spins up its own single-use Gradle daemon doing a full multi-module resolve+compile, and 7 of those running concurrently exhausts the VM's memory and crashes BuildKit's gRPC connection. Building sequentially (or with `COMPOSE_PARALLEL_LIMIT` set low) works reliably; not a problem in CI (M10), which typically builds and pushes one image per job/runner rather than all 8 in one machine at once.
- CI (M10) builds and tags all 8 Docker images (`ghcr.io/<owner>/<service>:latest`/`:<sha>`) but never pushes them anywhere (`push: false`) — there is no GHCR (or any other registry) publishing yet, and therefore nothing for a future ECS task definition (M12) to pull. This is intentional scope discipline (the user explicitly deferred both registry-push and deployment), not an oversight — enabling push needs exactly the two changes called out inline in `ci.yml`'s comments.
- CI does not boot any service against real Postgres/Kafka/Redis in-pipeline — it builds and structurally verifies each image (non-root user, exposed port, healthcheck present) but doesn't run a containerized integration test the way the M9 manual verification did locally. Testcontainers-based tests inside `./gradlew clean build` already cover real-infra behavior per service; a full docker-compose-driven E2E smoke test *in CI* is a reasonable future addition but isn't part of this milestone's explicit scope ("build Docker images for every service," not "re-run M9's manual E2E in CI").
- No README.md exists yet (M15's job) to actually hold the CI badge — the ready-to-paste badge markdown is recorded in this file's M10 Deployment Status section below instead of being placed into a file that doesn't exist yet.

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
- **payment-service (M8 addition):** the Resilience4j wrapper around the merchant-service Feign call verified live — merchant-service was stopped mid-session and `POST /api/v1/payments` degraded gracefully (503, no hang); a burst of 10 requests against the real stopped service showed retries genuinely running on the first two (~800–950ms each) before the circuit opened and every subsequent request failed fast (~70–90ms, confirmed via elapsed-time logging); after restarting merchant-service and waiting past `waitDurationInOpenState`, requests began succeeding again (201) as the circuit passed through automatic HALF_OPEN recovery back to CLOSED. Timeout fail-fast and bulkhead-rejection-under-concurrency are both covered by `MerchantResolverTest`/`MerchantResilienceIntegrationTest` rather than re-demonstrated manually (reproducing precise concurrent-saturation timing against a real, unmodified merchant-service process isn't practical without code changes to that service).
- **All 8 services (M9 — full containerization):** every service builds into its own multi-stage Docker image (`eclipse-temurin:25-jdk-alpine` builder → `eclipse-temurin:25-jre-alpine` runtime, layered-jar extraction, non-root user, `HEALTHCHECK` against its own `/actuator/health`) and the entire platform — 4 infra containers + 8 application containers, 12 total — was brought up together via `docker compose -f docker-compose.infra.yml -f docker-compose.yml up -d`, reaching `healthy` in the correct dependency order with zero manual intervention: postgres/redis/kafka healthy → identity-service healthy → merchant-service/payment-service healthy (parallel) → gateway-service healthy → transaction/audit/notification/analytics-service healthy (parallel with identity, since they only need postgres+kafka). Every port matches the pre-M9 scheme exactly (8080–8084, 8091–8093, 55432/56379/59092/8085). A full register→login→onboard-merchant→create→authorize→capture→refund lifecycle was driven entirely through the containerized gateway over real HTTP, with every consumer verified via direct `psql` against the running containers: transaction-service posted the correct 6 balanced ledger entries; audit-service recorded all 4 lifecycle events verbatim; notification-service logged all 4 simulated emails (no webhook configured for this test merchant, so correctly zero webhook-delivery rows — not a bug, per D46); analytics-service's aggregate showed the exact expected counts/amounts. All 12 containers stopped cleanly afterward.
- **CI pipeline (M10):** `.github/workflows/ci.yml` — validated with `actionlint` (zero warnings/errors) and a `js-yaml` parse (structurally valid) before committing. `build-and-test` job's exact command (`./gradlew clean build --no-daemon --stacktrace`) re-run locally to confirm it's still green post-M10 (no Java/Gradle files changed by this milestone — only the workflow file was added). `docker-build` job's build-arg/tag scheme reproduced locally for one service (`analytics-service`, tagged `ghcr.io/isahaameem/analytics-service:latest`/`:testsha` exactly as the workflow would) and its "Verify image" `docker inspect` assertions (non-root user `paymentflow:paymentflow`, exposed port `8093/tcp`, a present `HEALTHCHECK`) run by hand against that real image — all three passed. Not yet verified inside actual GitHub Actions infrastructure (this milestone doesn't push to GitHub — see the M10 changelog's Verification steps for exactly what "verify everything instead of assuming" meant here without a live workflow run to observe).
  - Ready-to-paste CI badge for M15's README: `[![CI](https://github.com/IsaHaameem/cloud-native-payment-processing-platform/actions/workflows/ci.yml/badge.svg)](https://github.com/IsaHaameem/cloud-native-payment-processing-platform/actions/workflows/ci.yml)`
- Other services: skeletons only. AWS: not yet started (Phase 4/5, M11–M12).

## 16. Lessons Learned
- A cache-aside bug (D38) shipped in M4 and passed M4's own test suite because that suite's only two `/me` reads were separated by a cache eviction — it never exercised a genuine cache *hit* round trip. Manual, real end-to-end testing across services (not just each service's own test suite in isolation) caught what unit/integration tests scoped to a single service could not: a bug that only manifests when a second service (payment-service, via Feign) calls the first one repeatedly in the pattern real traffic actually produces. Worth remembering for M6+: a fresh service's manual E2E pass is also a regression check on everything it calls.
- M8 repeated the same lesson from a different angle: `resilience4jMetricsAreExposedThroughMicrometer` passed cleanly in the automated suite, yet the *real* running service had zero resilience4j meters reachable through `/actuator/metrics` — because Spring Boot Test quietly supplies its own `SimpleMeterRegistry`, a safety net the production app doesn't have without a concrete registry dependency (deferred to M13). A green automated test proved the *wiring* was correct; only running the actual jar and hitting the actual endpoint revealed the *deployment* gap. Neither kind of check substitutes for the other.
- Introducing a dedicated thread pool for an existing synchronous call (`ThreadPoolBulkhead`, D52) silently broke JWT forwarding, because `RequestContextHolder` is thread-bound. This is a general pattern worth remembering for any future work that moves a call off the calling thread (async processing, a new executor, reactive adapters): anything reading Spring's request-scoped `ThreadLocal` context needs to be explicitly re-propagated, and it will not fail loudly — it just quietly stops working (here, every merchant resolution would have started failing as "not onboarded" for every merchant, indistinguishable from a real onboarding gap without deliberately checking the actual header the downstream service received).
- M9 validated the Docker packaging approach empirically at every step instead of trusting documentation alone: before writing the real Dockerfile, a bootJar was extracted and run locally with `java -Djarmode=tools ... extract --layers --launcher` + `JarLauncher` to confirm the exact layer directory names and entrypoint class; before building all 8 images, one (`audit-service`) was built and run standalone against the real compose network to confirm Postgres migration, Kafka consumer-group join, and the Docker `HEALTHCHECK` itself all genuinely worked end-to-end. Both checks caught nothing wrong this time, but they converted "should work per the reference docs" into "confirmed working here," which is the standard this project holds every other milestone to as well.
- A resource ceiling invisible from inside any single Dockerfile: building all 8 images via Compose's default parallelism (one BuildKit container per service, each running its own full-heap Gradle daemon) exhausted the Docker Desktop VM's allotted memory and killed the daemon's gRPC connection mid-build. The fix (build sequentially / cap `COMPOSE_PARALLEL_LIMIT`) is a local-machine-only concern — a reminder that "the build works" and "the build works at the concurrency your CI runner will actually use" are different claims, worth keeping in mind when M10 designs the GitHub Actions build matrix.
- M10 confirmed that "verify everything instead of assuming" still applies even to CI configuration itself, which can't be run end-to-end without pushing to GitHub (explicitly not done this milestone). The honest substitute wasn't to skip verification — it was to decompose the workflow into independently-checkable pieces and verify each one for real: `actionlint` (a real static analyzer, not just eyeballing YAML) for syntax/schema correctness; re-running the exact `./gradlew clean build` command line locally; and reproducing the exact `docker build` args/tags/`docker inspect` assertions from the "Docker build" job by hand against a real image. None of that proves the workflow runs correctly *inside* GitHub's infrastructure specifically (network egress, runner image quirks, secrets context) — that residual gap is named explicitly in Deployment Status rather than glossed over, since a milestone that can't push shouldn't quietly imply full verification when it only achieved partial verification.
- Discovered a subtle self-inflicted false alarm while stress-testing the final regression build: piping a background script's commands together (`gradlew ... ; echo ... ; grep -c "FAILED" logfile`) meant the *last* command's exit code became the whole script's reported exit code — and `grep -c` legitimately exits 1 when it finds zero matches (the desired, successful outcome here), not just when something goes wrong. The build had actually succeeded (`BUILD SUCCESSFUL`, confirmed by reading the captured `GRADLE_EXIT=0` from the real command), but the harness's completion summary reported "failed." Worth remembering generally: a composite script's exit code reflects its last command, not necessarily the thing you actually care about — check the real signal (here, the explicitly captured `GRADLE_EXIT`) before trusting a wrapper's aggregate result.

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

---

### M8 — Resilience4j ✅ (2026-07-18)

**Objectives:** Wrap the platform's one remaining unprotected synchronous
cross-service call — payment-service's OpenFeign call to merchant-service (D32)
— in production-grade Resilience4j: circuit breaker, retry with
exponential-backoff-and-jitter, a time limiter, and a bulkhead, with
Micrometer-bound metrics and every existing API contract preserved unchanged.

**Features implemented**
- `MerchantResolver.resolveCallerMerchant()` now composes Retry → CircuitBreaker
  → TimeLimiter → ThreadPoolBulkhead programmatically against the
  Spring-managed Resilience4j registries (D49) — each layer decorates the next
  via its own `CompletionStage` API, since `ThreadPoolBulkhead` only ever
  returns a `CompletionStage`, never a `Callable`/`Future`.
- CircuitBreaker (`merchantService` instance): count-based sliding window (20),
  `minimumNumberOfCalls=10` before the failure rate is even evaluated,
  `failureRateThreshold=50%`, plus `slowCallDurationThreshold=2s` /
  `slowCallRateThreshold=50%` so a consistently-slow-but-not-timing-out
  merchant-service also degrades the health signal, not just outright
  exceptions. `waitDurationInOpenState=10s` with
  `automaticTransitionFromOpenToHalfOpenEnabled=true` and
  `permittedNumberOfCallsInHalfOpenState=5` — OPEN → HALF_OPEN happens on its
  own timer, no incoming call needed to trigger the check.
- Retry (`merchantService` instance): `maxAttempts=3`, exponential backoff with
  jitter via a programmatic `RetryConfigCustomizer`
  (`IntervalFunction.ofExponentialRandomBackoff`, D50) — plain
  `resilience4j.retry.*` YAML can only express exponential-backoff *or*
  randomized-wait, not both combined, which is what "backoff with jitter"
  actually means.
- TimeLimiter (`merchantService` instance): `timeoutDuration=2s`,
  `cancelRunningFuture=true` — bounds worst-case latency regardless of what
  merchant-service or the network is doing. Backed by real Feign socket
  timeouts (`connect-timeout-ms=1000`, `read-timeout-ms=1500`, both under the
  TimeLimiter's 2s budget) via a per-client `Request.Options` bean
  (`FeignClientConfig`, wired through `@FeignClient(configuration = ...)` so it
  doesn't collide with Spring Cloud OpenFeign's own default `Request.Options`
  bean for every *other* Feign client that might exist later).
- ThreadPoolBulkhead (`merchantService` instance): `coreThreadPoolSize=5`,
  `maxThreadPoolSize=10`, `queueCapacity=10` — the actual Feign call runs on
  this small, dedicated pool, not payment-service's main Servlet
  request-handling threads, so a hung merchant-service can only ever saturate
  this pool, never exhaust the application's ability to serve any other
  request.
- Exception classification is an explicit whitelist, not a blanket catch-all
  (D51): `recordExceptions`/`retryExceptions` = `feign.RetryableException`,
  `feign.FeignException$FeignServerException`,
  `java.util.concurrent.TimeoutException` (genuinely transient/downstream
  signals); `ignoreExceptions` = `MerchantNotOnboardedException`,
  `feign.FeignException$FeignClientException`, **and**
  `io.github.resilience4j.bulkhead.BulkheadFullException` — the last one
  found necessary via testing, not assumed (see Problems below).
- Every fallback path converges on the existing `MerchantServiceUnavailableException`
  (503, `ApiError`, correlation id preserved via the existing
  `GlobalExceptionHandler` — no new exception type or error code needed);
  `MerchantNotOnboardedException` (400) passes through completely untouched by
  retry/circuit-breaker/bulkhead, exactly as "never retry/hide a genuine client
  error" requires.
- Request attributes are explicitly captured on the calling Servlet thread and
  re-bound (then cleared) on the `ThreadPoolBulkhead`'s thread around the
  actual call (D52) — without this, moving the call off the calling thread
  (the entire point of a thread-pool bulkhead) would silently break
  `FeignAuthorizationForwardingConfig`'s JWT-forwarding interceptor, since
  `RequestContextHolder` is a plain, non-inheritable `ThreadLocal`.
- `/actuator/metrics` added to payment-service's actuator exposure so
  Resilience4j's Micrometer-bound meters (`resilience4j.circuitbreaker.*`,
  `.retry.*`, `.bulkhead.*`, `.timelimiter.*`) are browsable now, ahead of M13
  wiring a real Prometheus scrape target.
- Every existing API contract is unchanged: `MerchantResolver.resolveCallerMerchant()`
  keeps its exact synchronous signature and return type; `PaymentService` and
  every controller/DTO are untouched.

**Endpoints added:** none (internal resilience wrapper only).

**Database changes:** none.

**Kafka topics:** none.

**Redis features added:** none.

**Infra/Docker changes:** none.

**Files created:** `payment-service/.../merchant/MerchantResilienceProperties.java`
(Feign timeout + retry-backoff externalized config), `MerchantResilienceConfig.java`
(`RetryConfigCustomizer` bean + a small dedicated `ScheduledExecutorService`
bean — resilience4j-spring-boot3's own
`ContextAwareScheduledThreadPoolAutoConfiguration` did not activate in this
app's context, see Problems), `FeignClientConfig.java` (per-client
`Request.Options`, deliberately *not* `@Configuration`-annotated — see
Problems), `MerchantResolverTest.java` (8 tests, no Spring context — built
against real Resilience4j registries with only `MerchantClient` mocked),
`MerchantResilienceIntegrationTest.java` (7 tests, full `@SpringBootTest` +
Testcontainers + a controllable JDK `HttpServer` merchant-service stub).

**Files modified:** `payment-service/.../merchant/MerchantResolver.java`
(rewritten around the Resilience4j decorator chain), `MerchantClient.java`
(`configuration = FeignClientConfig.class`), `application.yaml`
(`resilience4j.*` config blocks, `paymentflow.resilience.merchant-service.*`,
actuator `metrics` exposure), `payment-service/build.gradle.kts`
(`resilience4j-spring-boot3`, `resilience4j-micrometer`),
`gradle/libs.versions.toml` + `platform-bom/build.gradle.kts`
(`resilience4j-bom`), `PROJECT_CONTEXT.md`.

**Test coverage (15 new tests, all green):** `MerchantResolverTest` (8, no
Spring context, real Resilience4j registries + a mocked `MerchantClient`):
not-onboarded passes through without retry/circuit impact (both a single call
and 10 repeated calls never opening the circuit), a transient failure is
retried then succeeds, retries-exhausted surfaces as 503, the circuit opens
after the failure threshold and fails fast without calling the client again,
the circuit transitions OPEN → HALF_OPEN → CLOSED once the downstream
recovers, the bulkhead rejects a call beyond its capacity, the time limiter
fails fast on a slow downstream (bounded-elapsed-time assertion).
`MerchantResilienceIntegrationTest` (7, Testcontainers Postgres/Redis + a
controllable JDK `HttpServer` stub, full HTTP requests through
`POST /api/v1/payments`): a healthy call still correctly forwards the caller's
JWT despite running on the bulkhead thread, merchant-service down eventually
surfaces as 503, a too-slow merchant-service fails fast rather than hanging
the request thread (bounded-elapsed-time assertion), a transient failure is
retried and the request eventually succeeds, repeated failures open the
circuit (confirmed via `CircuitBreakerRegistry` state, not just HTTP status)
and it recovers through HALF_OPEN back to CLOSED, concurrent calls beyond the
bulkhead's capacity are rejected, and Resilience4j meters are found in the
injected `MeterRegistry`. All of payment-service's pre-existing 51 tests
(`PaymentServiceTest`, `PaymentIntegrationTest`, `PaymentTest`,
`PaymentStatusTest`, `IdempotencyServiceTest`, `OutboxRelayIntegrationTest`)
continued passing unchanged. Full suite re-run 3 times with no flakiness.

**Verification:** `./gradlew build` green across all 14 modules. Manual
end-to-end verification against the real running platform (identity, gateway,
merchant, payment services, real compose Postgres/Redis/Kafka): a baseline
payment succeeded normally with the resilience wrapper active (no behavior
change on the happy path); merchant-service was then killed mid-session — a
single request degraded gracefully to 503 in ~780ms (genuinely reflecting
retry attempts, not an instant failure); a burst of 10 requests showed the
first two taking ~800–950ms each (still retrying) before the circuit opened
and every subsequent request failed in ~70–90ms (fail-fast while OPEN,
confirmed via elapsed-time logging on each request); merchant-service was
restarted, and after waiting past `waitDurationInOpenState`, requests began
succeeding again as the circuit passed through automatic HALF_OPEN recovery
back to CLOSED, settling into consistent 201s. All four services were then
stopped cleanly and confirmed down via health-check probes.

**Important design decisions:** D49–D52 (see §9).

**Problems faced → solutions**
1. `ThreadPoolBulkhead.executeSupplier(...)` returns a `CompletionStage<T>`,
   not a `Callable`/`Future` as initially assumed — verified via `javap`
   against the resolved jar (this codebase's established practice, per D20's
   and prior milestones' precedent) rather than guessing, then rebuilt the
   whole composition around each component's `CompletionStage` decorator
   (`TimeLimiter.decorateCompletionStage`, `CircuitBreaker.decorateCompletionStage`,
   `Retry.decorateCompletionStage`) instead of the initially-assumed
   `Callable`-based chain.
2. `RetryConfigCustomizer` lives at
   `io.github.resilience4j.common.retry.configuration.RetryConfigCustomizer`
   (the `resilience4j-framework-common` module), not the guessed
   `io.github.resilience4j.retry.configure` package — again found via `javap`
   listing the actual jar contents before writing the import.
3. resilience4j-spring-boot3's own
   `ContextAwareScheduledThreadPoolAutoConfiguration` (needed by
   `TimeLimiter`/`Retry`'s `CompletionStage` decorators, which require a
   `ScheduledExecutorService`) did not activate in this application's context
   — `NoSuchBeanDefinitionException` surfaced only in full-`@SpringBootTest`
   integration tests, not `compileJava`. Declared a small, explicit
   `ScheduledExecutorService` bean instead of chasing why the auto-configuration
   didn't fire, consistent with this project's general preference for explicit
   beans over relying on auto-configuration magic that doesn't behave as
   documented.
4. **A real bug found during testing, not assumed**: `BulkheadFullException`
   was initially left off both `recordExceptions` and `ignoreExceptions` for
   the circuit breaker, on the theory that "not listed" would mean "not
   counted." Resilience4j's actual behavior is that once `recordExceptions` is
   non-empty, *any* unmatched exception is counted as a **success** — meaning
   sustained bulkhead saturation would have looked like a perfectly healthy
   merchant-service to the circuit breaker. Caught via
   `MerchantResilienceIntegrationTest`'s circuit-open test showing `CLOSED`
   with a rising "success" counter and `callCount=0` (the stub was never even
   reached) — diagnosed by logging the exact exception class reaching
   `MerchantResolver`'s catch block. Fixed by adding `BulkheadFullException`
   to `ignoreExceptions` explicitly (D51).
5. Test-isolation bug in `MerchantResilienceIntegrationTest`: the "slow
   merchant-service" test's background Feign call keeps running for its full
   simulated duration even after the caller gives up (TimeLimiter cancels the
   `CompletableFuture` it's waiting on, not the underlying blocking socket
   read — a real, documented Resilience4j/`CompletableFuture` characteristic,
   not a bug in this codebase). Since the test class's single-slot bulkhead is
   shared across every test method in the same Spring context, that lingering
   background call kept occupying the bulkhead thread into the *next* test,
   which then saw a false `BulkheadFullException` instead of its own intended
   scenario. Fixed by draining the lingering call (a bounded `Thread.sleep`)
   at the end of the slow-service test, and by resetting the
   `CircuitBreaker`'s state in `@BeforeEach` so one test's OPEN circuit can
   never leak into the next.
6. **A second real bug found while manually verifying the real running app**:
   `resilience4jMetricsAreExposedThroughMicrometer` passed in the automated
   suite, yet `/actuator/metrics` on the actual running service showed zero
   resilience4j meters. Root cause: no concrete Micrometer registry
   implementation is a dependency anywhere in the platform yet (only
   `micrometer-core`, the instrumentation API, via `spring-boot-starter-actuator`)
   — Boot's default `CompositeMeterRegistry` has no children and silently
   discards every recorded metric, while Spring Boot Test quietly supplies its
   own `SimpleMeterRegistry` that masked this in the automated test.
   Considered adding `micrometer-registry-simple` as a stopgap, but
   `SimpleMeterRegistry` ships inside `micrometer-core` itself — there is no
   separate `micrometer-registry-simple` artifact to depend on — and a
   permanent throwaway in-memory registry would just be discarded once M13
   adds `micrometer-registry-prometheus` anyway. Left as a documented Known
   Issue instead of pulling M13's scope forward: the Micrometer *binding* is
   proven correct (by the automated test and by Resilience4j's registries
   being genuinely Spring-managed beans); the *metrics backend* is
   deliberately M13's job.
7. Found and fixed *before* it ever reached a running system (D52): moving the
   Feign call onto `ThreadPoolBulkhead`'s own thread broke JWT forwarding,
   since `FeignAuthorizationForwardingConfig`'s interceptor reads
   `RequestContextHolder` — a thread-bound `ThreadLocal` the new thread simply
   doesn't see. Fixed by capturing the caller's `RequestAttributes` before
   dispatch and explicitly re-binding (then clearing) them on the bulkhead
   thread around the actual call.

**Next milestone:** M9 — Containerization (per-service multi-stage
Dockerfiles, healthchecks, layered jars).

---

### M9 — Containerization ✅ (2026-07-19)

**Objectives:** Package every one of the 8 microservices into a production-grade
Docker image and stand up the entire platform — infra + all 8 services, 12
containers total — via Docker Compose with correct health-gated startup
ordering, completing Phase 2 of the roadmap.

**Features implemented**
- One shared, parameterized multi-stage `Dockerfile` at the repo root (D53)
  instead of eight near-identical copies. Build stage: `eclipse-temurin:25-jdk-alpine`
  runs the real Gradle wrapper against the actual monorepo — build files
  (wrapper, settings, `build-logic`, every module's `build.gradle.kts`) copied
  in first as their own Docker layer, then only the requested module's `src/`
  plus `common-dto`/`common-lib` (its real compile-time dependencies, D54).
  `./gradlew :<module>:bootJar -x test` produces the jar; `java -Djarmode=tools
  -jar app.jar extract --layers --launcher` explodes it into
  `dependencies`/`snapshot-dependencies`/`spring-boot-loader`/`application`
  layer directories.
- Runtime stage: `eclipse-temurin:25-jre-alpine` (JRE only — no compiler, no
  Gradle, nothing beyond what's needed to run the jar), the four layers
  `COPY --from=builder` in least- to most-often-changing order (D55), a
  dedicated non-root `paymentflow` user/group, `EXPOSE`/`HEALTHCHECK` driven by
  build args so the same Dockerfile serves every service's own port. Entrypoint
  is the exec-form `java org.springframework.boot.loader.launch.JarLauncher`
  (Spring Boot 4's post-3.3 loader package) so `SIGTERM` reaches the JVM
  directly for a clean shutdown of DB connections/Kafka consumers.
- `HEALTHCHECK` uses BusyBox's built-in `wget` (already present on Alpine, no
  extra package installed) against `/actuator/health`, which every one of the 8
  services already exposes unauthenticated (identity/merchant/payment's
  `SecurityConfig`; the four Kafka-only consumers ship no Spring Security at
  all, D42) — one probe shape covers all 8 images.
- `docker-compose.yml` (D56): the 8 application services only, not a second
  copy of Postgres/Redis/Kafka/Kafka-UI — realizing the split M0's own
  `docker-compose.infra.yml` header comment already forecast. Always run
  merged with the infra file via multiple `-f` flags, which is what makes
  `depends_on: condition: service_healthy` resolve across both files as one
  Compose model: postgres/redis/kafka healthy → identity-service → merchant-
  service/payment-service (parallel) → gateway-service; transaction/audit/
  notification/analytics-service depend only on postgres+kafka (parallel with
  identity-service, since none of the four call it). `payment-service`
  deliberately does **not** `depends_on` merchant-service — M8's whole
  Resilience4j chain exists so payment-service tolerates merchant-service
  being down/slow at runtime, and coupling container startup order to it would
  undercut that.
- Every container-network-dependent Spring property is overridden purely via
  environment variables in `docker-compose.yml` — nothing baked into any image
  (requirement #7): `SPRING_DATASOURCE_*` → `postgres:5432`,
  `SPRING_DATA_REDIS_*` → `redis:6379`, `SPRING_KAFKA_BOOTSTRAP_SERVERS` →
  `kafka:19092` (the internal listener, not the host-published one),
  `PAYMENTFLOW_SERVICES_IDENTITY_JWKS_URI`/`_BASE_URI`,
  `PAYMENTFLOW_SERVICES_MERCHANT_BASE_URI`, `PAYMENTFLOW_SERVICES_PAYMENT_BASE_URI`
  → each service's container DNS name. Relies on nothing more exotic than
  Spring Boot's standard relaxed environment-variable binding, which applies
  uniformly to any property key, not just autoconfigured ones.
- Every existing host port is preserved exactly (8080–8084, 8091–8093,
  55432/56379/59092/8085) — requirement #12, zero renumbering.

**Endpoints added:** none (infrastructure-only milestone).

**Database / Kafka / Redis changes:** none.

**Infra/Docker changes:** `Dockerfile` (new, repo root), `docker-compose.yml`
(new, repo root — application services only). `docker-compose.infra.yml`
untouched.

**Testing completed:** `./gradlew clean build` green across all 14 modules —
every existing test suite (all ~230+ tests across every milestone) re-executed
from a clean state, zero failures, confirming M9 introduced no Java-source
regressions (none were expected; M9 touches no application code). No new
automated tests were added — this milestone's verification surface is Docker
build/runtime behavior, not application logic, so it's covered by the manual
verification below (mirrors the project's existing pattern of scoping
automated tests to what unit/integration tests can actually exercise, and
manual E2E to what genuinely requires a running system — same reasoning as
D42's "no query API without a real consumer").

**Verification steps:**
1. Built and ran a single image (`audit-service`) standalone against the real
   compose network before building the rest, to validate the Dockerfile design
   end-to-end: confirmed Postgres Flyway migration ran, all 3 Kafka consumer
   partitions were assigned, and Docker's own `HEALTHCHECK` transitioned to
   `healthy` against the real `/actuator/health` endpoint.
2. Built all 8 images (see Problems #1 for the concurrency issue hit and fixed
   along the way).
3. Brought up all 12 containers together
   (`docker compose -f docker-compose.infra.yml -f docker-compose.yml up -d`)
   — confirmed the exact dependency-ordered startup sequence in the Compose
   output (postgres/kafka/redis healthy → identity-service healthy →
   merchant-service/payment-service healthy → gateway-service healthy, with
   transaction/audit/notification/analytics-service healthy in parallel) and
   `docker compose ps` showing 12/12 `healthy`.
4. Drove a full register → login → onboard-merchant → create → authorize →
   capture → refund payment lifecycle entirely through the containerized
   gateway (`localhost:8080`) over real HTTP.
5. Queried each consumer's schema directly via `docker exec ... psql` against
   the running Postgres container: transaction-service posted 6 correctly
   balanced ledger entries (Authorized debit/credit, Captured debit/credit,
   Refunded debit/credit, all 15000 minor units); audit-service recorded all 4
   lifecycle events (`PaymentCreated`/`Authorized`/`Captured`/`Refunded`)
   verbatim; notification-service logged 4 simulated emails (0 webhook-delivery
   rows, correct since this test merchant never configured a `webhookUrl`);
   analytics-service's `merchant_payment_stats` row showed
   created=1/authorized=1/captured=1/refunded=1, 15000 captured, 15000 refunded.
6. `./gradlew clean build` run again (see Testing above) to confirm the whole
   monorepo remains green.
7. All 12 containers stopped cleanly via
   `docker compose -f docker-compose.infra.yml -f docker-compose.yml down`.

**Important design decisions:** D53–D56 (see §9).

**Problems faced → solutions**
1. **A real, dev-machine-specific issue found during verification, not
   assumed**: building all 8 images via Compose's default parallel build
   behavior crashed the Docker daemon mid-build (`failed to receive status:
   rpc error: ... EOF`) — each image build spins up its own single-use Gradle
   daemon doing a full multi-module resolve+compile, and 7 of those running
   concurrently exceeded the Docker Desktop VM's ~11.5GB memory allocation
   (`gradle.properties`' `-Xmx2g` per daemon × 7 ≈ 14GB). The daemon briefly
   became fully unresponsive (`500` on `/_ping`) and needed a short recovery
   window. Fixed by building the remaining images one at a time instead of
   relying on Compose/BuildKit's default bake concurrency — slower, but
   reliable; noted as a Known Issue relevant to M10's CI build-matrix design
   (a single CI runner building all 8 in parallel would hit the same ceiling).
2. Confirmed the Spring Boot layered-jar extraction command and its exact
   layer directory names (`dependencies`/`snapshot-dependencies`/
   `spring-boot-loader`/`application`) and the post-3.3 loader entrypoint class
   (`org.springframework.boot.loader.launch.JarLauncher`) by actually running
   `java -Djarmode=tools -jar <existing-bootJar> help extract` and doing a real
   local extract-and-run *before* writing the Dockerfile, rather than trusting
   remembered documentation — this project's established `javap`-before-guessing
   practice (D20, M8's D49/D50) applied to Docker packaging instead of Java APIs.
3. `BusyBox` `wget`'s exit code alone doesn't distinguish "got a response" from
   "got a 200 with `{"status":"DOWN"}`" — `HEALTHCHECK` pipes the response body
   through `grep -q '"status":"UP"'` rather than relying on `wget`'s exit code
   in isolation, so a degraded-but-responding app (e.g. DB connection lost)
   correctly fails the healthcheck instead of reporting healthy.

**Next milestone:** M10 — CI/CD (GitHub Actions: test + build + image; branch
protection).

---

### M10 — CI/CD ✅ (2026-07-19)

**Objectives:** Stand up a production-grade GitHub Actions pipeline that runs
the full Gradle test suite on every push/PR (failing fast on any test
failure), builds a Docker image for every one of the 8 services using M9's
existing Dockerfile unchanged, tags them GHCR-shaped, and is structured so
that enabling an actual registry push later needs no restructuring —
explicitly without implementing any push or deployment yet.

**Features implemented**
- Single workflow, `.github/workflows/ci.yml`, triggered on every `push`
  (no branch filter), every `pull_request`, and `workflow_dispatch` (manual
  runs). A `concurrency` group cancels a superseded run on the same
  branch/PR rather than queuing behind it.
- **`build-and-test` job:** checkout → `actions/setup-java@v4` (Temurin 25) →
  `gradle/actions/setup-gradle@v4` (D57) → `./gradlew clean build --no-daemon
  --stacktrace`. Any test failure fails this job immediately (Gradle's own
  non-zero exit code), which — via `docker-build`'s `needs:` — skips all 8
  Docker builds entirely rather than wasting minutes validating images from
  code that doesn't pass its own tests (D60). Test reports
  (`**/build/reports/tests/**`, `**/build/test-results/**`) are uploaded as a
  14-day-retention artifact on every run (`if: always()`), so a failure is
  inspectable from the Actions UI without re-running anything locally.
- **`docker-build` job:** one matrix leg per service (8 legs: gateway/
  identity/merchant/payment/transaction/audit/notification/analytics-service,
  each with its own `port`), capped at `max-parallel: 4` (D58) rather than
  left unbounded. Each leg: `docker/setup-buildx-action` → computes a
  lowercased `ghcr.io/<owner>` prefix (GHCR names must be lowercase;
  `github.repository_owner` isn't guaranteed to be) → builds via
  `docker/build-push-action@v6` reusing M9's Dockerfile and build-arg
  contract completely unchanged (`SERVICE_MODULE`, `SERVICE_PORT`), tagged
  both `:latest` and `:<git-sha>`, with `push: false` / `load: true` (D59)
  and BuildKit layer caching via the GitHub Actions cache backend
  (`cache-from`/`cache-to: type=gha`, scoped per service so one service's
  cache doesn't evict another's).
- A real automated verification step per leg (D61): `docker inspect` asserts
  the built image actually has the non-root `paymentflow:paymentflow` user,
  the correct `EXPOSED` port, and a present `HEALTHCHECK` — all three
  established in M9 (D53–D55) — so a Dockerfile regression that silently
  dropped any of them fails CI, not just "did `docker build` exit 0."
- The exact two changes needed to enable GHCR push later are written
  in-place as comments at the point they'd apply: a commented-out
  `docker/login-action@v3` step (GHCR, `github.actor`/`GITHUB_TOKEN`) and a
  note next to `push: false` to flip it to `true`; `permissions:` currently
  only grants `contents: read`, with a comment showing where `packages:
  write` gets added.
- CI badge markdown prepared for M15's not-yet-existing README (see
  Deployment Status) rather than creating the README file itself, which
  stays scoped to M15 per the roadmap.

**Endpoints added:** none (CI/tooling-only milestone).

**Database / Kafka / Redis changes:** none.

**Infra/Docker changes:** `.github/workflows/ci.yml` (new). `Dockerfile` and
`docker-compose.yml` (M9) are unmodified — CI consumes them exactly as they
already existed, proving out D53's "one shared Dockerfile" design decision
from a second, independent caller (a CI matrix) rather than just the local
Compose file that originally justified it.

**Testing completed:** No new Java tests (this milestone adds no application
code). Verification instead focused on the workflow itself, since it can't be
run inside real GitHub Actions without pushing (explicitly not done this
milestone — see Verification steps). `./gradlew clean build` re-run locally
from clean, zero failures, confirming the monorepo remains exactly as green
as it was at the end of M9.

**Verification steps:**
1. YAML validity: parsed with `js-yaml` (structurally valid) and checked with
   `actionlint` v1.7.12 (a real static analyzer for GitHub Actions workflows
   — catches expression-syntax errors, unknown action inputs, shellcheck
   issues in `run:` blocks, invalid matrix references, etc.) — zero
   warnings, zero errors.
2. `build-and-test` job's exact command line
   (`./gradlew clean build --no-daemon --stacktrace`) run locally: `BUILD
   SUCCESSFUL`, all 14 modules, zero failures.
3. `docker-build` job's build reproduced by hand for one service
   (`analytics-service`): `docker build --build-arg SERVICE_MODULE=analytics-service
   --build-arg SERVICE_PORT=8093 -t ghcr.io/isahaameem/analytics-service:latest
   -t ghcr.io/isahaameem/analytics-service:testsha .` — succeeded, using
   BuildKit's local cache from M9's earlier builds.
4. The "Verify image" step's exact `docker inspect` assertions run by hand
   against that real image: user `paymentflow:paymentflow` ✓, exposed port
   `8093/tcp` ✓, non-nil `HEALTHCHECK` ✓ — all three passed, confirming the
   verification step's logic is correct against a real image, not just
   syntactically plausible.
5. Test images/tags from step 3 removed afterward (`docker rmi`), leaving no
   residue from the verification exercise.
6. **Not yet verified:** an actual run inside GitHub's own Actions
   infrastructure (network conditions, the real `ubuntu-latest` image,
   GITHUB_TOKEN-scoped permissions) — this milestone doesn't push to GitHub,
   so that residual gap is named explicitly here and in Known Issues rather
   than implied as covered.

**Important design decisions:** D57–D61 (see §9).

**Problems faced → solutions**
1. No Python interpreter reachable from this shell for the originally-planned
   `yaml.safe_load` validation approach (`python3`/`python` both resolved to
   a Windows Store install shim rather than a real interpreter) — pivoted to
   `npx -y js-yaml` (Node is installed and already used elsewhere in this
   environment) for a structural parse, then downloaded the real
   `actionlint` binary directly from its GitHub release for genuine
   GitHub-Actions-schema-aware validation, rather than settling for "the
   YAML parses" as sufficient proof of "no syntax issues."
2. GHCR image repository names must be lowercase, but
   `github.repository_owner` reflects the account's actual casing
   (`IsaHaameem`), which would have produced an invalid tag. Added a small
   shell step computing a lowercased prefix into `$GITHUB_ENV` once, reused
   by every subsequent tag reference, rather than lowercasing inline at each
   of the (several) places a tag string appears.
3. A composite verification script's misleading exit code (see Lessons
   Learned): the final regression build's wrapper script reported "failed"
   because its *last* command (`grep -c "FAILED" logfile`) exits 1 on zero
   matches — the correct, successful outcome — not because the actual
   `./gradlew clean build` (whose real exit code was separately captured as
   `GRADLE_EXIT=0`) had failed. Diagnosed by reading the captured exit code
   directly rather than trusting the wrapper's own aggregate result.

**Next milestone:** M11 — Terraform Infrastructure (VPC, ECR, RDS,
ElastiCache, Kafka, ALB, Secrets Manager, IAM, remote state).


