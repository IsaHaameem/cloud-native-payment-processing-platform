package com.paymentflow.agentic.checkout;

import java.time.Instant;
import java.util.List;

/**
 * The projection of a checkout that leaves this service — to the demo API, and to the model
 * as a tool result.
 *
 * <p>The amounts are here, and they are here on purpose: the model needs to be able to tell
 * the customer what they are about to pay. What it cannot do is send that number back as an
 * instruction — {@code RequestPaymentTool} takes a checkout id and nothing else, so a model
 * that quotes a different figure is merely wrong in text rather than wrong in money.
 */
public record CheckoutView(
        String id,
        String status,
        String currency,
        long subtotalMinor,
        long discountMinor,
        long totalMinor,
        List<Line> lines,
        String paymentId,
        Instant expiresAt) {

    /** One priced line, with the price captured when it was added rather than today's catalogue price. */
    public record Line(
            String productId,
            String sku,
            String name,
            int quantity,
            long unitPriceMinor,
            long lineTotalMinor) {
    }

    public static CheckoutView of(Checkout checkout) {
        List<Line> lines = checkout.getItems().stream()
                .map(item -> new Line(
                        item.getProductId().toString(),
                        item.getSku(),
                        item.getName(),
                        item.getQuantity(),
                        item.getUnitPriceMinor(),
                        item.getLineTotalMinor()))
                .toList();

        return new CheckoutView(
                checkout.getId().toString(),
                checkout.getStatus().name(),
                checkout.getCurrency(),
                checkout.getSubtotalMinor(),
                checkout.getDiscountMinor(),
                checkout.getTotalMinor(),
                lines,
                checkout.getPaymentId() == null ? null : checkout.getPaymentId().toString(),
                checkout.getExpiresAt());
    }
}
