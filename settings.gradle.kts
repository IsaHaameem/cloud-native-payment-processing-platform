/*
 * Root Gradle settings for the Payment Orchestration Platform monorepo.
 *
 * Responsibilities:
 *   - Register the shared convention plugins (build-logic) as an included build.
 *   - Enable Foojay so Gradle can auto-provision the Java 25 toolchain regardless
 *     of the JDK installed on the developer's machine (reproducible builds).
 *   - Declare the module graph (shared libraries + microservices).
 *
 * The version catalog is auto-loaded from gradle/libs.versions.toml.
 */

pluginManagement {
    // Shared build logic (convention plugins) lives in ./build-logic.
    includeBuild("build-logic")
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    // Auto-downloads a matching JDK when the requested toolchain is not installed.
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.10.0"
}

dependencyResolutionManagement {
    // Fail fast if a subproject declares its own repositories — one source of truth.
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
}

rootProject.name = "payment-orchestration-platform"

// ── Shared modules ──────────────────────────────────────────────────────
include("platform-bom")
include("common-dto")
include("common-lib")

// ── Microservices ───────────────────────────────────────────────────────
include("gateway-service")
include("identity-service")
include("merchant-service")
include("payment-service")
include("transaction-service")
include("audit-service")
include("notification-service")
include("analytics-service")
include("sandbox-service")

// ── Agentic commerce extension (Project 3) ──────────────────────────────
// The AI Growth & Agentic Commerce layer: LLM runtime, typed tool registry, policy
// engine, agent action log, catalog, checkout, and the Razorpay provider client.
//
// A service, not a library, and deliberately the only module this extension adds. It
// sits ABOVE the payment platform and reaches it through the public /v1 API like any
// other consumer — nothing here is on the payment core's compile classpath, and the one
// inbound call (payment-service asking for a provider decision) crosses the same
// HMAC-signed internal-context boundary sandbox-service already uses.
//
// Deleting this line and the module directory returns the build to its pre-extension
// state; see project_3_context.md AD-8 for the full removal procedure.
include("agentic-commerce-service")

// ── Performance / load testing (M14) ────────────────────────────────────
include("load-tests")

// ── API contract tooling (M21) ──────────────────────────────────────────
// Merges the six per-service OpenAPI fragments into the published
// docs/openapi.yaml. Build tooling, like load-tests: run by the build, never
// deployed and never on a service's runtime classpath.
include("openapi-tools")

// ── SDK code generation (M22.1) ─────────────────────────────────────────
// The one generator that feeds both the Node and the Python SDK, reading
// docs/openapi.yaml. Build tooling like openapi-tools, and Java rather than
// TypeScript for the reason D136 gives: a contributor with neither Node nor
// Python installed must still be able to build this monorepo, and the freshness
// gate that stops a generated model drifting from the spec has to run in the
// build that owns the spec. `sdks:shared` — the SDK tree's own directory, not a
// flat top-level module, because sdks/node and sdks/python sit beside it and
// only this one is a Gradle project.
include("sdks:shared")

// ── Shared test scaffold (M21.7) ────────────────────────────────────────
// The base classes the six public-API services' contract tests extend. A module
// of its own rather than a testFixtures source set on common-lib, because
// common-lib's web dependencies are deliberately compileOnly (D11) so the
// reactive gateway is never handed a servlet stack — and a servlet-test
// scaffold is the last thing that module should start exporting. Test scope
// only; nothing links it into a running service.
include("test-support")
