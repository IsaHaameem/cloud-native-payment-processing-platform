/*
 * common-dto — immutable, transport-agnostic DTOs and (later) event schemas shared
 * across services. Deliberately framework-light: records + Jackson annotations only,
 * so it never drags web/persistence frameworks into consumers.
 */
plugins {
    id("paymentflow.java-conventions")
}

dependencies {
    api(platform(project(":platform-bom")))

    // Jackson annotations only (not databind) — keeps this module serialization-lib
    // agnostic while still allowing @JsonInclude on the wire contracts.
    api("com.fasterxml.jackson.core:jackson-annotations")

    testImplementation("com.fasterxml.jackson.core:jackson-databind")
    testImplementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
