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

    // Version constraints for libraries NOT covered by the imported BOMs.
    constraints {
        // M21.1: springdoc-openapi, pinned by constraint rather than by importing
        // `springdoc-openapi-bom` — which is what the three lines above would suggest,
        // and which is wrong here.
        //
        // springdoc's BOM inherits from `spring-boot-starter-parent` in Maven, so it
        // re-exports the whole of Spring Boot's dependency management at *springdoc's*
        // chosen Boot version. Importing it moved this platform off 4.0.2 —
        // `spring-boot-jackson 4.0.2 -> 4.0.5`, `tools.jackson 3.0.4 -> 3.1.0`,
        // `jackson-databind 2.20.2 -> 2.21.1` — for every module in the monorepo, since
        // common-lib and common-dto depend on this platform too. Observed via
        // `dependencyInsight` ("By conflict resolution: between versions 4.0.5 and
        // 4.0.2"), not guessed.
        //
        // A BOM whose only real content is the coordinates of its own five artefacts is
        // not worth surrendering control of the platform version for. This constraint
        // pins exactly what is needed and leaves the Boot BOM above authoritative over
        // everything else, including the Jackson 2 that swagger-core resolves against
        // (Boot 4.0.2 manages both lines: Jackson 3 at 3.0.4 and Jackson 2 at 2.20.2).
        //
        // The constraint alone is not sufficient — springdoc's own *dependencies* still
        // name Boot artefacts at the version it was built against. That is why the
        // catalog pins 3.0.1 rather than the newest 3.0.3; see the note there.
        //
        // Only the starter needs a constraint: its POM pins springdoc-openapi-starter-common
        // to an exact equal version, so the two can never drift apart.
        api(libs.springdoc.starter.webmvc.api)
    }
}
