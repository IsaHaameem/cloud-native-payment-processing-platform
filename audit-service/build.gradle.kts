/*
 * audit-service — immutable audit trail; idempotent event sink consuming payment.events.
 *
 * D42 originally scoped this service to the Kafka stream alone. M19.5 added the merchant-
 * facing events API (GET /v1/events), with Spring Security wired for InternalContextFilter
 * only (D133's shape), projecting the stored trail into the canonical evt_ shape M18 defined.
 * Still no OpenFeign. The payload is still stored as an opaque JSON tree rather than a typed
 * class — audit's entire job is to record whatever event came through verbatim, so it has no
 * business reason to know the shape of any specific event type (see D44); the read API
 * projects that tree rather than requiring it to have been parsed on the way in.
 */
plugins {
    id("paymentflow.java-conventions")
    alias(libs.plugins.spring.boot)
}

dependencies {
    implementation(platform(project(":platform-bom")))
    implementation(project(":common-lib"))

    implementation("org.springframework.boot:spring-boot-starter-web")
    // M19.5: the merchant-facing Events API. Security for InternalContextFilter only —
    // no OAuth2 resource server (D133).
    implementation("org.springframework.boot:spring-boot-starter-security")
    // common-lib's GlobalExceptionHandler (auto-activated for any servlet app, D11) has
    // a ConstraintViolationException handler method that must resolve at class-load time.
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-kafka")
    // Concrete Micrometer registry backend + distributed tracing (M13).
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
