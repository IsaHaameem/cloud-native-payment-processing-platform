package com.paymentflow.notification.egress;

import java.net.InetAddress;
import java.util.List;

/**
 * The result of checking a destination before connecting to it (M18.5). Carries the
 * resolved addresses on success so the caller can pin the connection to exactly what was
 * validated — returning only "allowed" would leave a DNS-rebinding window between the
 * check and the connect.
 */
public record EgressDecision(boolean allowed, String reason, List<InetAddress> resolvedAddresses) {

    public EgressDecision {
        resolvedAddresses = (resolvedAddresses == null) ? List.of() : List.copyOf(resolvedAddresses);
    }

    public static EgressDecision allow(List<InetAddress> resolvedAddresses) {
        return new EgressDecision(true, null, resolvedAddresses);
    }

    public static EgressDecision deny(String reason) {
        return new EgressDecision(false, reason, List.of());
    }
}
