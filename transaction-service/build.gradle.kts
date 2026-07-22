/*
 * transaction-service — double-entry ledger; idempotent consumer of payment.events;
 * optimistic locking on account balances. The platform's first real Kafka consumer.
 *
 * Deliberately no REST API, no Spring Security, no OpenFeign: the roadmap scopes this
 * service to exactly "double-entry ledger, idempotent consumer, optimistic locking" —
 * its only inbound interface is the event stream. spring-boot-starter-web is still
 * needed for actuator's HTTP transport (health, matching every other service, for M9's
 * container healthchecks).
 */
plugins {
    id("paymentflow.java-conventions")
    alias(libs.plugins.spring.boot)
}

dependencies {
    implementation(platform(project(":platform-bom")))
    implementation(project(":common-lib"))

    implementation("org.springframework.boot:spring-boot-starter-web")
    // common-lib's GlobalExceptionHandler (auto-activated for any servlet app, D11) has
    // a ConstraintViolationException handler method — that class must resolve at
    // class-load time even though this service does no request-body validation itself.
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
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.springframework.kafka:spring-kafka-test")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testImplementation("org.testcontainers:testcontainers-kafka")
    testImplementation("org.testcontainers:testcontainers")
    testImplementation(libs.awaitility)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
