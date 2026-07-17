/*
 * gateway-service — reactive edge (Spring Cloud Gateway): routing, JWT validation,
 * Redis rate-limiting, CORS, security headers, correlation-id propagation.
 * Becomes a Spring Boot application in M3. Skeleton only in M0.
 */
plugins {
    id("paymentflow.java-conventions")
}

dependencies {
    implementation(platform(project(":platform-bom")))
    implementation(project(":common-lib"))
}
