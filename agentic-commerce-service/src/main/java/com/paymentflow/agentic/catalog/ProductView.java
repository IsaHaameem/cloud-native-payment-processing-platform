package com.paymentflow.agentic.catalog;

/**
 * The projection of a product that leaves this service — to the demo API, and to the model
 * as a tool result.
 *
 * <p>It is a narrower shape than the entity on purpose. {@code merchantId} and {@code mode}
 * are absent because they are context the caller already is, not data it should be handed;
 * {@code metadata} is absent because it is merchant-controlled free-form text with no
 * defined meaning, and putting arbitrary merchant text into a model's context window is how
 * a catalogue becomes a prompt-injection surface. What the model needs to reason about a
 * purchase is here; nothing else is.
 *
 * @param available whether stock exists right now, rather than how much — a precise count is
 *                  operational data the buyer has no use for, and quoting it invites the
 *                  model to reason about scarcity it cannot verify at payment time.
 */
public record ProductView(
        String id,
        String sku,
        String name,
        String description,
        String category,
        long priceMinor,
        String currency,
        boolean available) {

    public static ProductView of(Product product) {
        return new ProductView(
                product.getId().toString(),
                product.getSku(),
                product.getName(),
                product.getDescription(),
                product.getCategory(),
                product.getPriceMinor(),
                product.getCurrency(),
                product.hasAvailability(1));
    }
}
