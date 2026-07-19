/*
 * load-tests — Gatling performance suite (M14). Not part of the platform-bom
 * dependency graph (D-precedent: this module is a black-box HTTP client
 * against the already-running platform, not a Spring Boot service) — it only
 * needs an HTTP client and Gatling's own DSL, deliberately not common-dto/
 * common-lib, so simulations exercise the real public API contract exactly
 * as an external caller would, not an internal shortcut.
 */
plugins {
    java
    id("io.gatling.gradle") version "3.15.1.1"
}

java {
    toolchain {
        // Same JDK as every other module — Gatling officially supports JDK 11-25.
        languageVersion = JavaLanguageVersion.of(25)
    }
}
