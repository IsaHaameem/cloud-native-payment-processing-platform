/*
 * analytics-service — per-merchant/currency payment aggregates for reporting; idempotent
 * consumer of payment.events with optimistic-lock retry on the shared aggregate row
 * (mirrors transaction-service's LedgerService pattern, M6).
 *
 * Deliberately no REST API, no Spring Security, no OpenFeign (same scope discipline as
 * transaction-service/audit-service, D42): its only inbound interface is the Kafka
 * stream. A read API for the aggregates is deferred until a real consumer needs one
 * (same YAGNI call as D31/D42).
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
