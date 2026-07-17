/*
 * notification-service — webhook delivery + email; retry topic + dead-letter queue.
 * Becomes a Spring Boot application in M7. Skeleton in M0.
 */
plugins {
    id("paymentflow.java-conventions")
}

dependencies {
    implementation(platform(project(":platform-bom")))
    implementation(project(":common-lib"))
}
