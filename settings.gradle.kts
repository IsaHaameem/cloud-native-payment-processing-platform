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

// ── Performance / load testing (M14) ────────────────────────────────────
include("load-tests")
