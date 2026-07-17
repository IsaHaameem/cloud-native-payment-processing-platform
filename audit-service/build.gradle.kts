/*
 * audit-service — immutable audit trail; event sink consuming domain events.
 * Becomes a Spring Boot application in M7. Skeleton in M0.
 */
plugins {
    id("paymentflow.java-conventions")
}

dependencies {
    implementation(platform(project(":platform-bom")))
    implementation(project(":common-lib"))
}
