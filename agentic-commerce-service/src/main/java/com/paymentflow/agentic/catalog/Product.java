package com.paymentflow.agentic.catalog;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * A sellable item in a merchant's catalogue.
 *
 * <p>{@code priceMinor} is an integer in the currency's minor unit, without exception. This
 * is the platform's rule and it matters more here than anywhere else in this service: the
 * price on this row is what a checkout captures, and the checkout total is the only number
 * that ever becomes a payment amount. A floating-point price here would put a rounding error
 * at the root of every money action the agent takes.
 *
 * <p>Inventory is a plain counter decremented when a checkout is paid, not reserved at
 * checkout creation. That is a deliberate simplification for a payments demonstration: real
 * reservation needs a hold with a timeout and a reconciliation job, none of which teaches
 * anything about payment orchestration. The consequence — two checkouts can each pass an
 * availability check for the last unit, and the second payment fails at checkout time — is
 * recorded as a known limitation rather than hidden behind a check that looks stronger than
 * it is.
 */
@Entity
@Table(name = "products")
public class Product {

    private static final String EMPTY_METADATA = "{}";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "merchant_id", nullable = false, updatable = false)
    private UUID merchantId;

    @Column(nullable = false, updatable = false, length = 4)
    private String mode;

    @Column(nullable = false, updatable = false, length = 64)
    private String sku;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(length = 2000)
    private String description;

    @Column(nullable = false, length = 64)
    private String category;

    @Column(name = "price_minor", nullable = false)
    private long priceMinor;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(name = "inventory_count", nullable = false)
    private int inventoryCount;

    @Column(nullable = false)
    private boolean active;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata", nullable = false, columnDefinition = "jsonb")
    private String metadata;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Product() {
        // Required by JPA.
    }

    private Product(UUID merchantId, String mode, String sku, String name, String description, String category,
                    long priceMinor, String currency, int inventoryCount, String metadata) {
        this.merchantId = merchantId;
        this.mode = mode;
        this.sku = sku;
        this.name = name;
        this.description = description;
        this.category = category;
        this.priceMinor = priceMinor;
        this.currency = currency;
        this.inventoryCount = inventoryCount;
        this.active = true;
        this.metadata = (metadata == null || metadata.isBlank()) ? EMPTY_METADATA : metadata;
    }

    public static Product create(UUID merchantId, String mode, String sku, String name, String description,
                                 String category, long priceMinor, String currency, int inventoryCount,
                                 String metadata) {
        return new Product(merchantId, mode, sku, name, description, category, priceMinor, currency,
                inventoryCount, metadata);
    }

    /** Whether this product can satisfy an order for {@code quantity} units right now. */
    public boolean hasAvailability(int quantity) {
        return active && inventoryCount >= quantity;
    }

    /**
     * Decrements stock. Called only when a checkout is actually paid, never when one is
     * created — see the class javadoc for why, and for what that costs.
     */
    public void reduceInventory(int quantity) {
        if (quantity <= 0 || quantity > inventoryCount) {
            throw new IllegalArgumentException("Cannot reduce inventory by " + quantity);
        }
        this.inventoryCount -= quantity;
    }

    public void updateInventory(int inventoryCount) {
        this.inventoryCount = Math.max(0, inventoryCount);
    }

    public void deactivate() {
        this.active = false;
    }

    public UUID getId() {
        return id;
    }

    public UUID getMerchantId() {
        return merchantId;
    }

    public String getMode() {
        return mode;
    }

    public String getSku() {
        return sku;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public String getCategory() {
        return category;
    }

    public long getPriceMinor() {
        return priceMinor;
    }

    public String getCurrency() {
        return currency;
    }

    public int getInventoryCount() {
        return inventoryCount;
    }

    public boolean isActive() {
        return active;
    }

    public String getMetadata() {
        return metadata;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
