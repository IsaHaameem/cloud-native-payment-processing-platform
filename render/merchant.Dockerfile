# GENERATED -- do not edit by hand. Source of truth: ../Dockerfile
#
# Render's Docker builder accepts a Dockerfile path and a context but has no way to pass
# `--build-arg`, and ../Dockerfile leaves SERVICE_MODULE and SERVICE_PORT without defaults
# (docker-compose.yml supplies them per service). A build from ../Dockerfile on Render would
# therefore run `./gradlew ":bootJar"` against an empty module name and fail. This file is
# ../Dockerfile with those two ARGs defaulted for merchant-service, and nothing else changed.
#
# Regenerate every file in this directory after any change to ../Dockerfile:
#
#   python render/generate.py
#
# render/verify.sh asserts these stay byte-identical to ../Dockerfile apart from this
# header and the two ARG lines.
#
#
# Shared multi-stage Dockerfile for every microservice in the monorepo (M9).
#
# One parameterized Dockerfile instead of eight near-identical copies. Every service
# in this platform builds and runs the same way (same Gradle multi-module monorepo,
# same Spring Boot packaging, same JRE, same non-root user, same healthcheck shape) —
# the only things that differ per service are which Gradle module to build and which
# port it listens on. Both are passed as build args from docker-compose.yml, one
# service block per module:
#
#   docker build --build-arg SERVICE_MODULE=payment-service --build-arg SERVICE_PORT=8083 .
#
# Stage 1 (builder) compiles the requested module with the real Gradle wrapper — the
# same toolchain (Java 25, Foojay-provisioned) and the same build a developer runs
# locally, so the image is built from exactly the same bytecode `./gradlew build`
# produces (reproducible builds). Only that module's own source (plus the two shared
# libraries every service depends on) is copied in — schema-per-service's "no
# cross-service coupling" extends to the build context too.
#
# Stage 2 (runtime) extracts the Spring Boot layered jar (dependencies /
# snapshot-dependencies / spring-boot-loader / application, copied in that order —
# least to most likely to change) onto a minimal JRE-only Alpine base. A pure
# application-code change only busts the final (application) image layer, not the
# three dependency layers underneath it.

ARG JAVA_VERSION=25

# ─────────────────────────────────────────────────────────────────────────
# Stage 1 — build the requested module with the real Gradle wrapper.
# ─────────────────────────────────────────────────────────────────────────
FROM eclipse-temurin:${JAVA_VERSION}-jdk-alpine AS builder
ARG SERVICE_MODULE=merchant-service
WORKDIR /workspace

# Build files first, in their own layer: source changes below never invalidate the
# Gradle-wrapper-download / dependency-resolution work this layer captures.
COPY gradlew ./
COPY gradle ./gradle
COPY settings.gradle.kts build.gradle.kts gradle.properties ./
COPY build-logic ./build-logic
COPY platform-bom/build.gradle.kts platform-bom/build.gradle.kts
COPY common-dto/build.gradle.kts common-dto/build.gradle.kts
COPY common-lib/build.gradle.kts common-lib/build.gradle.kts
COPY gateway-service/build.gradle.kts gateway-service/build.gradle.kts
COPY identity-service/build.gradle.kts identity-service/build.gradle.kts
COPY merchant-service/build.gradle.kts merchant-service/build.gradle.kts
COPY payment-service/build.gradle.kts payment-service/build.gradle.kts
COPY transaction-service/build.gradle.kts transaction-service/build.gradle.kts
COPY audit-service/build.gradle.kts audit-service/build.gradle.kts
COPY notification-service/build.gradle.kts notification-service/build.gradle.kts
COPY analytics-service/build.gradle.kts analytics-service/build.gradle.kts
COPY sandbox-service/build.gradle.kts sandbox-service/build.gradle.kts
# agentic-commerce-service (Project 3). Unlike the four build-file-only modules below, this
# one is a real deployable and its source is copied by the parameterised COPY further down
# when SERVICE_MODULE names it — this line exists for the same configuration-phase reason as
# every other service above: settings.gradle.kts names it, so Gradle configures it on every
# invocation whichever module is actually being built.
COPY agentic-commerce-service/build.gradle.kts agentic-commerce-service/build.gradle.kts
# load-tests (M14) is declared in settings.gradle.kts, so Gradle configures it
# on every invocation regardless of which module is actually being built —
# its build.gradle.kts must exist even though its src (excluded from this
# build context by .dockerignore) is never needed here.
COPY load-tests/build.gradle.kts load-tests/build.gradle.kts
# openapi-tools (M21.3) is in settings.gradle.kts for the same reason and needs the
# same treatment. It is additionally a `testImplementation` dependency of the six
# services that publish a /v1 tier, via the paymentflow.openapi-fragment convention
# plugin — but this stage builds `bootJar -x test`, so only the build file has to
# exist, never the source. Missing this line breaks the image build for every
# service with a Gradle "project directory does not contain a build file" failure,
# which is a configuration-phase error and therefore says nothing about the module
# actually being built.
COPY openapi-tools/build.gradle.kts openapi-tools/build.gradle.kts
# test-support (M21.7) — third module in a row to need this line, and the second to
# be discovered needing it by a red CI rather than by anyone remembering. Same
# reasoning as the two above: settings.gradle.kts names it, so Gradle configures it
# on every invocation; the six public-API services depend on it in *test* scope only,
# and this stage builds `bootJar -x test`, so the build file is all that is required.
# DockerBuildContextConsistencyTest now fails when a module is included without a
# matching line here, so there should not be a fourth discovery of this kind.
COPY test-support/build.gradle.kts test-support/build.gradle.kts
# sdks/shared (M22.1) — the SDK code generator. Same rule again, and the first module
# whose Gradle path is nested (`:sdks:shared`), so the path here is `sdks/shared/` rather
# than a single segment. Nothing in any image needs it: it generates TypeScript and Python
# from docs/openapi.yaml and no service depends on it in any scope.
COPY sdks/shared/build.gradle.kts sdks/shared/build.gradle.kts

RUN chmod +x gradlew

# Real application source: only the requested module's, plus common-dto/common-lib
# (every service's actual compile-time dependency, per settings.gradle.kts). No
# sibling service's source is ever needed to build this one.
COPY common-dto/src common-dto/src
COPY common-lib/src common-lib/src
COPY ${SERVICE_MODULE}/src ${SERVICE_MODULE}/src

# BuildKit cache mount for the Gradle user home (~/.gradle): the resolved
# dependency graph and wrapper distribution persist across builds and are shared
# by all eight service builds (the mount id derives from the target path, so every
# `docker build`/`docker compose build` leg reuses the same cache). Without it,
# every image build re-downloaded the entire dependency graph from scratch, 8×
# redundantly and with zero tolerance for a transient registry/network hiccup —
# the root cause of the repeated build failures during M15 E2E validation. Default
# sharing=shared is safe: Gradle coordinates concurrent access with its own
# cross-process cache locks. Requires BuildKit (Buildx in CI, Compose v2 locally).
# auto-download=false is scoped to this one invocation only (not gradle.properties,
# which stays Foojay-enabled for local dev/CI): this builder stage's own JDK already
# is the exact toolchain version required (ARG JAVA_VERSION, matching the hardcoded
# toolchain in paymentflow.java-conventions.gradle.kts), so Foojay is never actually
# needed here — disabling it turns "shouldn't reach the network" into "structurally
# can't," the same determinism goal as the cache mount above.
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew ":${SERVICE_MODULE}:bootJar" --no-daemon -x test \
    -Porg.gradle.java.installations.auto-download=false \
    && mkdir -p /workspace/staging \
    && cp $(find "${SERVICE_MODULE}/build/libs" -maxdepth 1 -name '*.jar' ! -name '*-plain.jar') \
        /workspace/staging/app.jar \
    && java -Djarmode=tools -jar /workspace/staging/app.jar extract \
        --layers --launcher --destination /workspace/extracted

# ─────────────────────────────────────────────────────────────────────────
# Stage 2 — minimal JRE-only runtime image.
# ─────────────────────────────────────────────────────────────────────────
FROM eclipse-temurin:${JAVA_VERSION}-jre-alpine AS runtime
ARG SERVICE_PORT=8082
ENV SERVICE_PORT=${SERVICE_PORT}

# Non-root: no shell login needed, no writes anywhere but the app's own working dir.
RUN addgroup -S paymentflow && adduser -S paymentflow -G paymentflow
WORKDIR /app

COPY --from=builder /workspace/extracted/dependencies/ ./
COPY --from=builder /workspace/extracted/spring-boot-loader/ ./
COPY --from=builder /workspace/extracted/snapshot-dependencies/ ./
COPY --from=builder /workspace/extracted/application/ ./

RUN chown -R paymentflow:paymentflow /app
USER paymentflow:paymentflow

EXPOSE ${SERVICE_PORT}

# Every service in the platform exposes /actuator/health/** unauthenticated (identity/
# merchant/payment's SecurityConfig explicitly permits it; the four Kafka-only
# consumers ship no Spring Security at all, D42) — so one probe shape covers all
# eight. wget is BusyBox-provided on Alpine already; no extra package install needed
# (minimizes attack surface — nothing beyond the JRE and the app itself ships in
# this image).
HEALTHCHECK --interval=10s --timeout=5s --start-period=45s --retries=6 \
    CMD wget -q -O- "http://localhost:${SERVICE_PORT}/actuator/health" | grep -q '"status":"UP"' || exit 1

# Exec form (not shell form): SIGTERM reaches the JVM directly for a clean shutdown
# of open DB connections / Kafka consumers, instead of being swallowed by a shell.
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
