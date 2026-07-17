/*
 * transaction-service — double-entry ledger; idempotent consumer of payment
 * events; optimistic locking. Becomes a Spring Boot application in M6. Skeleton in M0.
 */
plugins {
    id("paymentflow.java-conventions")
}

dependencies {
    implementation(platform(project(":platform-bom")))
    implementation(project(":common-lib"))
}
