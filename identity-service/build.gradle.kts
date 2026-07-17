/*
 * identity-service — users, authentication, BCrypt, JWT issue/refresh, RBAC.
 * Becomes a Spring Boot application in M2 (first business service). Skeleton in M0.
 */
plugins {
    id("paymentflow.java-conventions")
}

dependencies {
    implementation(platform(project(":platform-bom")))
    implementation(project(":common-lib"))
}
