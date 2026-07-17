/*
 * payment-service — core orchestration: payment state machine, create/authorize/
 * capture/refund, idempotency keys, Saga orchestration, transactional outbox,
 * Kafka publishing. Becomes a Spring Boot application in M5. Skeleton in M0.
 */
plugins {
    id("paymentflow.java-conventions")
}

dependencies {
    implementation(platform(project(":platform-bom")))
    implementation(project(":common-lib"))
}
