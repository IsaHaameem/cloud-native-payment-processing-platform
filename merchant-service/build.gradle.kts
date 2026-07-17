/*
 * merchant-service — merchant onboarding, API-key issuance, merchant profile
 * caching (cache-aside + TTL). Becomes a Spring Boot application in M4. Skeleton in M0.
 */
plugins {
    id("paymentflow.java-conventions")
}

dependencies {
    implementation(platform(project(":platform-bom")))
    implementation(project(":common-lib"))
}
