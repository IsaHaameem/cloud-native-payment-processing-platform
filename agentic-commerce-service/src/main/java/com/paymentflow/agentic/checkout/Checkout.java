package com.paymentflow.agentic.checkout;

import com.paymentflow.agentic.error.AgenticErrorCode;
import com.paymentflow.agentic.error.AgenticException;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * A server-owned, priced, itemised quote. <b>The single most important class in this
 * service.</b>
 *
 * <p>Its total is the only number that ever becomes a payment amount, and it is computed
 * here from this checkout's own items and the prices captured on them — never supplied by a
 * caller, and never, under any circumstance, by the model. The model may name a checkout id;
 * everything financial about that checkout is resolved from this row.
 *
 * <p>Three properties make that guarantee hold rather than merely be intended:
 *
 * <ol>
 *   <li><b>There is no setter for any amount.</b> {@code subtotalMinor}, {@code discountMinor}
 *       and {@code totalMinor} change only through {@link #recalculate()}, which derives them
 *       from the items. The schema re-asserts it: {@code chk_checkouts_total_is_derived}
 *       makes a row whose total disagrees with its own parts unrepresentable.</li>
 *   <li><b>Every state change goes through a guarded method</b> that enforces
 *       {@link CheckoutStatus}'s transition table, exactly as {@code Payment} does.</li>
 *   <li><b>{@code @Version}.</b> Two concurrent attempts to pay one quote conflict rather
 *       than interleave — the same optimistic locking the payment aggregate itself uses.</li>
 * </ol>
 */
@Entity
@Table(name = "checkouts")
public class Checkout {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "merchant_id", nullable = false, updatable = false)
    private UUID merchantId;

    @Column(nullable = false, updatable = false, length = 4)
    private String mode;

    @Column(name = "conversation_id", updatable = false)
    private UUID conversationId;

    @Column(name = "session_ref", nullable = false, updatable = false, length = 128)
    private String sessionRef;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private CheckoutStatus status;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(name = "subtotal_minor", nullable = false)
    private long subtotalMinor;

    @Column(name = "discount_minor", nullable = false)
    private long discountMinor;

    @Column(name = "total_minor", nullable = false)
    private long totalMinor;

    @Column(name = "payment_id")
    private UUID paymentId;

    @Column(name = "provider_reference", length = 128)
    private String providerReference;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @OneToMany(mappedBy = "checkout", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @OrderBy("createdAt asc")
    private List<CheckoutItem> items = new ArrayList<>();

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected Checkout() {
        // Required by JPA.
    }

    private Checkout(UUID merchantId, String mode, UUID conversationId, String sessionRef, String currency,
                     Instant expiresAt) {
        this.merchantId = merchantId;
        this.mode = mode;
        this.conversationId = conversationId;
        this.sessionRef = sessionRef;
        this.currency = currency;
        this.expiresAt = expiresAt;
        this.status = CheckoutStatus.OPEN;
        this.subtotalMinor = 0;
        this.discountMinor = 0;
        this.totalMinor = 0;
    }

    public static Checkout create(UUID merchantId, String mode, UUID conversationId, String sessionRef,
                                  String currency, Instant expiresAt) {
        return new Checkout(merchantId, mode, conversationId, sessionRef, currency, expiresAt);
    }

    // ── Items ───────────────────────────────────────────────────────────────────────────

    /**
     * Adds a line, or raises the quantity of one already present.
     *
     * <p>{@code unitPriceMinor} is captured from the catalogue at this moment and never
     * re-read. A later price change must not silently re-price a quote the customer has
     * already been shown — a quote whose total can drift underneath it is not a quote.
     */
    public void addItem(UUID productId, String sku, String name, int quantity, long unitPriceMinor) {
        requireMutable();
        Optional<CheckoutItem> existing = findItem(productId);
        if (existing.isPresent()) {
            existing.get().increaseQuantity(quantity);
        } else {
            items.add(CheckoutItem.create(this, productId, sku, name, quantity, unitPriceMinor));
        }
        recalculate();
    }

    /** Sets an exact quantity, removing the line when the quantity reaches zero. */
    public void setItemQuantity(UUID productId, int quantity) {
        requireMutable();
        CheckoutItem item = findItem(productId)
                .orElseThrow(() -> new AgenticException(AgenticErrorCode.TOOL_ARGUMENTS_INVALID,
                        "This checkout has no line for that product."));
        if (quantity <= 0) {
            items.remove(item);
        } else {
            item.setQuantity(quantity);
        }
        recalculate();
    }

    public void removeItem(UUID productId) {
        setItemQuantity(productId, 0);
    }

    public Optional<CheckoutItem> findItem(UUID productId) {
        return items.stream().filter(item -> item.getProductId().equals(productId)).findFirst();
    }

    /**
     * Applies a fixed discount in minor units, clamped to the subtotal so the total can never
     * go negative. Clamping rather than throwing because a discount larger than the basket is
     * a legitimate promotional case, not an error — and a negative total is not something the
     * platform could represent anyway.
     */
    public void applyDiscount(long discountMinor) {
        requireMutable();
        this.discountMinor = Math.clamp(discountMinor, 0, subtotalMinor);
        recalculate();
    }

    /**
     * Recomputes the money. The only writer of the three amount fields, which is what makes
     * "the total is derived" a property of the class rather than a claim about it.
     */
    private void recalculate() {
        long subtotal = items.stream().mapToLong(CheckoutItem::getLineTotalMinor).sum();
        this.subtotalMinor = subtotal;
        // Re-clamp: removing an item can drop the subtotal below a discount already applied,
        // which would otherwise leave the schema's discount-within-subtotal constraint violated
        // and surface as an opaque write failure instead of an obviously-correct total.
        this.discountMinor = Math.clamp(this.discountMinor, 0, subtotal);
        this.totalMinor = subtotal - this.discountMinor;
    }

    // ── Lifecycle ───────────────────────────────────────────────────────────────────────

    /** Reserves this quote for a payment attempt. Fails if it is not open, empty, or expired. */
    public void lockForPayment(Instant now) {
        requireNotExpired(now);
        if (status == CheckoutStatus.PAID) {
            throw new AgenticException(AgenticErrorCode.CHECKOUT_ALREADY_PAID);
        }
        if (items.isEmpty()) {
            throw new AgenticException(AgenticErrorCode.CHECKOUT_EMPTY);
        }
        transitionTo(CheckoutStatus.LOCKED);
    }

    /** The payment attempt failed. The basket survives so the customer can try another instrument. */
    public void releaseLock() {
        if (status == CheckoutStatus.LOCKED) {
            transitionTo(CheckoutStatus.OPEN);
        }
    }

    public void markPaid(UUID paymentId, String providerReference) {
        transitionTo(CheckoutStatus.PAID);
        this.paymentId = paymentId;
        this.providerReference = providerReference;
    }

    public void cancel() {
        transitionTo(CheckoutStatus.CANCELLED);
    }

    public void expire() {
        transitionTo(CheckoutStatus.EXPIRED);
    }

    /** Records the provider's own reference without changing state — set when an order is created. */
    public void attachProviderReference(String providerReference) {
        this.providerReference = providerReference;
    }

    public boolean isExpired(Instant now) {
        return now.isAfter(expiresAt);
    }

    private void transitionTo(CheckoutStatus target) {
        if (!status.canTransitionTo(target)) {
            throw new AgenticException(AgenticErrorCode.CHECKOUT_NOT_OPEN,
                    "A checkout in state %s cannot move to %s.".formatted(status, target));
        }
        this.status = target;
    }

    private void requireMutable() {
        if (status == CheckoutStatus.PAID) {
            throw new AgenticException(AgenticErrorCode.CHECKOUT_ALREADY_PAID);
        }
        if (!status.isMutable()) {
            throw new AgenticException(AgenticErrorCode.CHECKOUT_NOT_OPEN,
                    "A checkout in state %s cannot be modified.".formatted(status));
        }
    }

    private void requireNotExpired(Instant now) {
        if (isExpired(now)) {
            throw new AgenticException(AgenticErrorCode.CHECKOUT_EXPIRED);
        }
    }

    // ── Accessors ───────────────────────────────────────────────────────────────────────

    public UUID getId() {
        return id;
    }

    public UUID getMerchantId() {
        return merchantId;
    }

    public String getMode() {
        return mode;
    }

    public UUID getConversationId() {
        return conversationId;
    }

    public String getSessionRef() {
        return sessionRef;
    }

    public CheckoutStatus getStatus() {
        return status;
    }

    public String getCurrency() {
        return currency;
    }

    public long getSubtotalMinor() {
        return subtotalMinor;
    }

    public long getDiscountMinor() {
        return discountMinor;
    }

    public long getTotalMinor() {
        return totalMinor;
    }

    public UUID getPaymentId() {
        return paymentId;
    }

    public String getProviderReference() {
        return providerReference;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public List<CheckoutItem> getItems() {
        return List.copyOf(items);
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public long getVersion() {
        return version;
    }
}
