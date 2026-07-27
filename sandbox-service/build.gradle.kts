/*
 * sandbox-service (M17) — the platform's simulated acquirer and scenario engine
 * (§4.2, D103). Advises payment-service on authorization outcomes; never mutates a
 * payment, never writes the ledger, never publishes payment.* (D103's load-bearing
 * boundary). M17.1 was web + JPA + Flyway only; M17.2 adds Spring Security purely for
 * common-lib's InternalContextFilter (D100) on the internal decision endpoint — no
 * OAuth2 resource server, since this service never accepts a JWT. M17.6 adds Kafka —
 * this service's first producer role — for the deferred-outcome topic
 * (`sandbox.scheduled.events`), now that payment-service exists as a real consumer.
 */
plugins {
    id("paymentflow.java-conventions")
    alias(libs.plugins.spring.boot)
}

dependencies {
    implementation(platform(project(":platform-bom")))
    implementation(project(":common-lib"))

    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    // M21.2: the OpenAPI 3.1 description of this service's public /v1 surface. The -api
    // starter and NOT -ui, for the reason M21.1 recorded on payment-service: the document
    // is an artefact other things consume (M21.3's merge task, M22's SDK generators,
    // M25's docs site), and the interactive console is the portal's job (M23/M24),
    // rendered against the merged spec rather than six per-service Swagger pages behind a
    // gateway that routes none of them.
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-api")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-kafka")
    // Concrete Micrometer registry backend + distributed tracing (M13 convention).
    runtimeOnly("io.micrometer:micrometer-registry-prometheus")
    implementation("org.springframework.boot:spring-boot-starter-opentelemetry")

    implementation("org.springframework.boot:spring-boot-flyway")
    runtimeOnly("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.springframework.kafka:spring-kafka-test")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testImplementation("org.testcontainers:testcontainers-kafka")
    testImplementation("org.testcontainers:testcontainers")
    testImplementation(libs.awaitility)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
