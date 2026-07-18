package com.paymentflow.notification.domain;

/** Lifecycle of one webhook delivery attempt sequence for a single event. */
public enum DeliveryStatus {
    PENDING,
    DELIVERED,
    DEAD_LETTERED
}
