/*
 * platform-bom — the single dependency-alignment point for the whole platform.
 *
 * A `java-platform` module that re-exports the Spring Boot and Spring Cloud BOMs.
 * Every service will do:  implementation(platform(project(":platform-bom")))
 * so all modules resolve identical, compatible dependency versions with zero
 * per-module version declarations.
 */
plugins {
    `java-platform`
}

javaPlatform {
    // Allow importing other BOMs (platforms) from within this platform.
    allowDependencies()
}

dependencies {
    api(platform(libs.spring.boot.bom))
    api(platform(libs.spring.cloud.bom))
    api(platform(libs.resilience4j.bom))

    // Version constraints for libraries NOT covered by the imported BOMs will be
    // added here as they are introduced in later milestones (e.g. testcontainers
    // extras, springdoc). Kept empty in M0 by design.
}
