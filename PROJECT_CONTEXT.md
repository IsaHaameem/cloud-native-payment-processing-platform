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

- **Current milestone:** M5 (Payment Service) — *pending approval*
- **Completed milestones:** M0 (repo bootstrap) ✅ · M1 (shared modules) ✅ · M2 (Identity Service) ✅ · M3 (Gateway Service) ✅ · M4 (Merchant Service) ✅
- **Pending milestones:** M5–M15

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

---

## 10. Risks
- Scope explosion across 8 services → mitigated by depth-first build order.
- Kafka/KRaft local resource use on dev machine → infra-only compose file for fast loop.
- AWS cost during Phase 5 → single small RDS/ElastiCache, teardown scripts, cost notes.

## 11. Known Issues
- Gateway does not yet honor `X-Forwarded-*`/`Forwarded` headers (Spring Cloud Gateway 2025.x disables this by default unless `spring.cloud.gateway.server.webflux.trusted-proxies` is set). Irrelevant with no reverse proxy in front locally; must be configured in M12 once the gateway sits behind an ALB, or HSTS/scheme-dependent behavior will see the wrong (plaintext) scheme.
- Gateway-local log lines do not carry `correlationId`/`requestId` via MDC (WebFlux isn't thread-bound per request); the header still propagates correctly across the wire. Full reactive log correlation is deferred to M13 (see D26).
- No cross-service API-key validation contract exists yet for merchant-service (see D31) — payment-service (M5) will need one; its shape is deliberately not guessed ahead of that real consumer.

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
- **gateway-service:** builds, all tests pass, verified running locally on port 8080 against the compose Redis, proxying to identity-service and merchant-service — full register→login→gateway-authenticated-request flow exercised over HTTP, including a real Redis-backed 429 under concurrent load.
- **merchant-service:** builds, all tests pass, verified running locally on port 8082 against the compose Postgres/Redis (Flyway migrated the `merchant` schema) — onboarding, cached profile reads, cache-busting updates, API-key rotation, and ADMIN-only listing all exercised over HTTP through the gateway.
- Other services: skeletons only. AWS: not yet started.

## 16. Lessons Learned
- TBD.

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


