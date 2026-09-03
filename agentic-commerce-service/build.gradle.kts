/*
 * agentic-commerce-service (Project 3) — the AI Growth & Agentic Commerce layer.
 *
 * An extension service that sits ABOVE the payment platform, never inside it. It owns the
 * LLM runtime, the typed tool registry, the policy engine, the agent action log, the
 * catalog and checkout, and the Razorpay provider client and its credentials.
 *
 * Deliberately absent, and each absence is a decision:
 *
 *   - `paymentflow.openapi-fragment` is NOT applied. This service's API is a hackathon
 *     surface, not part of the published /v1 contract, so it stays out of
 *     docs/openapi.yaml and out of the three contract gates that guard it (AD-8).
 *     springdoc is not a dependency for the same reason.
 *   - No Kafka. This service consumes no topic and produces none; it observes the platform
 *     through the public /v1 API it is entitled to call, like any other API consumer.
 *   - No OAuth2 resource server. Like sandbox-service, its only signed inbound caller is
 *     another service, and common-lib's InternalContextFilter is the whole of that story.
 *
 * Spring's RestClient (already on the classpath via starter-web) is the HTTP client for
 * all three outbound integrations — the platform, Anthropic and Razorpay. No new HTTP
 * dependency was introduced for any of them.
 */
plugins {
    id("paymentflow.java-conventions")
    alias(libs.plugins.spring.boot)
}

dependencies {
    implementation(platform(project(":platform-bom")))
    implementation(project(":common-lib"))

    implementation("org.springframework.boot:spring-boot-starter-web")
    // Security is here for exactly one reason: common-lib's InternalContextFilter, which
    // authenticates payment-service's signed provider-decision call (D100). Same posture
    // as sandbox-service — no JWT is ever accepted by this service.
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")

    // Resilience around the three outbound hops. Used programmatically, not via AOP
    // annotations — D49's reasoning applies unchanged: it sidesteps spring-boot-starter-aop
    // and any @Order aspect-ordering configuration while keeping the registries
    // Spring-managed and Micrometer-bound.
    implementation("io.github.resilience4j:resilience4j-spring-boot3")
    implementation("io.github.resilience4j:resilience4j-micrometer")

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
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
