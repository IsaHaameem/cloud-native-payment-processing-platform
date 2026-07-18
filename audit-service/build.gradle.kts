/*
 * audit-service — immutable audit trail; idempotent event sink consuming payment.events.
 *
 * Deliberately no REST API, no Spring Security, no OpenFeign (same scope discipline as
 * transaction-service, D42): its only inbound interface is the Kafka stream. Payload is
 * stored as an opaque JSON tree rather than a typed payload class — audit's entire job is
 * to record whatever event came through verbatim, so it has no business reason to know
 * the shape of any specific event type (see D44).
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
    // a ConstraintViolationException handler method that must resolve at class-load time.
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-kafka")

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
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
