/*
 * payment-service — core orchestration: payment state machine, create/authorize/
 * capture/refund/void, idempotency keys (Redis lock + Postgres record), transactional
 * outbox, Kafka publishing. First bootable service in the platform to produce Kafka
 * events and make a synchronous cross-service call (merchant-service, via OpenFeign,
 * per the tech stack's "sync calls only where consistency requires it" rule).
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
    implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.springframework.boot:spring-boot-starter-kafka")
    implementation("org.springframework.cloud:spring-cloud-starter-openfeign")
    // Resilience4j around the one synchronous cross-service call (merchant-service, D32).
    // Used programmatically (Retry/CircuitBreaker/TimeLimiter/ThreadPoolBulkhead
    // decorator composition in MerchantResolver), not via AOP annotations — see D49:
    // this sidesteps needing spring-boot-starter-aop and any @Order aspect-ordering
    // configuration entirely. Registries are still Spring-managed beans, so Actuator/
    // Micrometer metrics binding (M8's requirement) works identically either way.
    implementation("io.github.resilience4j:resilience4j-spring-boot3")
    implementation("io.github.resilience4j:resilience4j-micrometer")
    // Concrete Micrometer registry backend (M13) — finally gives M8's
    // resilience4j.* meters (and everything else) somewhere real to land.
    runtimeOnly("io.micrometer:micrometer-registry-prometheus")
    implementation("org.springframework.boot:spring-boot-starter-opentelemetry")

    implementation("org.springframework.boot:spring-boot-flyway")
    runtimeOnly("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.springframework.kafka:spring-kafka-test")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testImplementation("org.testcontainers:testcontainers-kafka")
    testImplementation("org.testcontainers:testcontainers")
    testImplementation(libs.awaitility)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
