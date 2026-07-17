/*
 * common-dto — immutable, transport-agnostic DTOs and Kafka event schemas shared
 * across services. Deliberately dependency-light (records + validation only) so it
 * never drags framework code into consumers. Contents arrive in M1.
 */
plugins {
    id("paymentflow.java-conventions")
}

dependencies {
    api(platform(project(":platform-bom")))
}
