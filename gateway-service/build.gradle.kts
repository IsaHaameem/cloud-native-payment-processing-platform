/*
 * gateway-service — reactive edge (Spring Cloud Gateway): routing, JWT validation,
 * Redis rate-limiting, CORS, security headers, correlation-id propagation.
 *
 * Reactive (WebFlux/Netty) only — never add spring-boot-starter-web here. common-lib's
 * servlet-only auto-configuration (correlation filter, GlobalExceptionHandler) correctly
 * stays inactive in this module by design (see common-lib D11); the gateway ships its
 * own reactive equivalents instead.
 */
plugins {
    id("paymentflow.java-conventions")
    alias(libs.plugins.spring.boot)
}

dependencies {
    implementation(platform(project(":platform-bom")))
    implementation(project(":common-lib"))

    implementation("org.springframework.cloud:spring-cloud-starter-gateway-server-webflux")
    implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")
    implementation("org.springframework.boot:spring-boot-starter-data-redis-reactive")
    implementation("org.springframework.boot:spring-boot-starter-actuator")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("io.projectreactor:reactor-test")
    // Testcontainers 2.x: core module keeps its unprefixed coordinates.
    testImplementation("org.testcontainers:testcontainers")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
