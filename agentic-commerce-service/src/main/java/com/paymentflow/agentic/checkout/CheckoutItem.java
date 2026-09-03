package com.paymentflow.agentic.checkout;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * One priced line on a checkout.
 *
 * <p>{@code unitPriceMinor} is a copy of the catalogue price taken when the line was created,
 * not a reference to the product. That duplication is the point: it is what lets a quote be
 * a promise. {@code lineTotalMinor} is likewise stored rather than computed on read, so the
 * schema can assert it ({@code chk_checkout_items_line_total_is_derived}) — a stored
 * derivation that the database checks is stronger than a computed one nothing verifies.
 *
 * <p>Quantity is bounded at 100 in the schema. Not a business rule so much as a blast
 * radius: an agent that mis-parses "a couple of kilos" as 2000 should hit a wall well before
 * it composes a payment that would fail the policy cap anyway.
 */
@Entity
@Table(name = "checkout_items")
public class CheckoutItem {

    /** Mirrors {@code chk_checkout_items_quantity_bounded}; enforced here so the failure is a clean error. */
    static final int MAX_QUANTITY = 100;

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "checkout_id", nullable = false, updatable = false)
    private Checkout checkout;

    @Column(name = "product_id", nullable = false, updatable = false)
    private UUID productId;

    @Column(nullable = false, updatable = false, length = 64)
    private String sku;

    @Column(nullable = false, updatable = false, length = 200)
    private String name;

    @Column(nullable = false)
    private int quantity;

    @Column(name = "unit_price_minor", nullable = false, updatable = false)
    private long unitPriceMinor;

    @Column(name = "line_total_minor", nullable = false)
    private long lineTotalMinor;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected CheckoutItem() {
        // Required by JPA.
    }

    private CheckoutItem(Checkout checkout, UUID productId, String sku, String name, int quantity,
                         long unitPriceMinor) {
        this.checkout = checkout;
        this.productId = productId;
        this.sku = sku;
        this.name = name;
        this.unitPriceMinor = unitPriceMinor;
        setQuantity(quantity);
    }

    static CheckoutItem create(Checkout checkout, UUID productId, String sku, String name, int quantity,
                               long unitPriceMinor) {
        return new CheckoutItem(checkout, productId, sku, name, quantity, unitPriceMinor);
    }

    void setQuantity(int quantity) {
        if (quantity <= 0 || quantity > MAX_QUANTITY) {
            throw new IllegalArgumentException(
                    "Quantity must be between 1 and " + MAX_QUANTITY + " but was " + quantity);
        }
        this.quantity = quantity;
        this.lineTotalMinor = Math.multiplyExact(unitPriceMinor, quantity);
    }

    void increaseQuantity(int by) {
        setQuantity(this.quantity + by);
    }

    public UUID getId() {
        return id;
    }

    public UUID getProductId() {
        return productId;
    }

    public String getSku() {
        return sku;
    }

    public String getName() {
        return name;
    }

    public int getQuantity() {
        return quantity;
    }

    public long getUnitPriceMinor() {
        return unitPriceMinor;
    }

    public long getLineTotalMinor() {
        return lineTotalMinor;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
