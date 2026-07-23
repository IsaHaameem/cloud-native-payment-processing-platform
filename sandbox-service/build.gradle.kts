/*
 * sandbox-service (M17) — the platform's simulated acquirer and scenario engine
 * (§4.2, D103). Advises payment-service on authorization outcomes; never mutates a
 * payment, never writes the ledger, never publishes payment.* (D103's load-bearing
 * boundary). Deliberately lean in M17.1: web + JPA + Flyway only, matching
 * analytics-service's minimal-dependency discipline — Spring Security (for the
 * signed internal-context endpoint) and Kafka (for the deferred-outcome topic) are
 * added in M17.2 and M17.6 respectively, when a caller for each actually exists.
 */
plugins {
    id("paymentflow.java-conventions")
    alias(libs.plugins.spring.boot)
}

dependencies {
    implementation(platform(project(":platform-bom")))
    implementation(project(":common-lib"))

    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    // Concrete Micrometer registry backend + distributed tracing (M13 convention).
    runtimeOnly("io.micrometer:micrometer-registry-prometheus")
    implementation("org.springframework.boot:spring-boot-starter-opentelemetry")

    implementation("org.springframework.boot:spring-boot-flyway")
    runtimeOnly("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testImplementation("org.testcontainers:testcontainers")
    testImplementation(libs.awaitility)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
