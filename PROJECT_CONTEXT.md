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

- **Current milestone:** M3 (Gateway Service) — *pending approval*
- **Completed milestones:** M0 (repo bootstrap) ✅ · M1 (shared modules) ✅ · M2 (Identity Service) ✅
- **Pending milestones:** M3–M15

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

---

## 10. Risks
- Scope explosion across 8 services → mitigated by depth-first build order.
- Kafka/KRaft local resource use on dev machine → infra-only compose file for fast loop.
- AWS cost during Phase 5 → single small RDS/ElastiCache, teardown scripts, cost notes.

## 11. Known Issues
- None yet.

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
