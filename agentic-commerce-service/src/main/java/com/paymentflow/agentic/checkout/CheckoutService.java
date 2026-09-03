package com.paymentflow.agentic.checkout;

import com.paymentflow.agentic.catalog.CatalogService;
import com.paymentflow.agentic.catalog.Product;
import com.paymentflow.agentic.catalog.ProductRepository;
import com.paymentflow.agentic.config.AgenticProperties;
import com.paymentflow.agentic.error.AgenticErrorCode;
import com.paymentflow.agentic.error.AgenticException;
import com.paymentflow.common.exception.ResourceNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Owns the server-side truth about what is being bought and for how much.
 *
 * <p>Every method that could influence an amount validates against the catalogue first:
 * a product that does not belong to this merchant and mode is a 404, an inactive or
 * out-of-stock product is refused, and a currency that disagrees with the checkout is
 * refused. The alternative — trusting the caller and letting the payment fail later — would
 * move the failure from a place where it is explainable to one where it is expensive.
 *
 * <p>Expiry is evaluated on read rather than by a scheduled sweeper. A checkout that has
 * passed its expiry is transitioned and persisted the next time anyone looks at it, which
 * means the state a caller sees is always current without this service running a job whose
 * only purpose is to write a state nobody has asked about yet.
 */
@Service
public class CheckoutService {

    private final CheckoutRepository checkoutRepository;
    private final ProductRepository productRepository;
    private final CatalogService catalogService;
    private final AgenticProperties properties;
    private final Clock clock;

    public CheckoutService(CheckoutRepository checkoutRepository, ProductRepository productRepository,
                           CatalogService catalogService, AgenticProperties properties, Clock clock) {
        this.checkoutRepository = checkoutRepository;
        this.productRepository = productRepository;
        this.catalogService = catalogService;
        this.properties = properties;
        this.clock = clock;
    }

    /** One requested line, before it has been priced. Quantities come from the caller; prices never do. */
    public record LineRequest(UUID productId, int quantity) {
    }

    @Transactional
    public Checkout create(UUID merchantId, String mode, UUID conversationId, String sessionRef,
                           List<LineRequest> lines) {
        if (lines == null || lines.isEmpty()) {
            throw new AgenticException(AgenticErrorCode.CHECKOUT_EMPTY,
                    "A checkout must be created with at least one line item.");
        }
        if (lines.size() > properties.checkout().maxLineItems()) {
            throw new AgenticException(AgenticErrorCode.TOOL_ARGUMENTS_INVALID,
                    "A checkout may contain at most %d distinct products."
                            .formatted(properties.checkout().maxLineItems()));
        }

        // The first line fixes the currency, and every subsequent line must agree. Resolving
        // it from the catalogue rather than accepting it as a parameter removes a whole class
        // of mismatch: a caller cannot declare a currency the products do not have.
        Product first = catalogService.require(merchantId, mode, lines.getFirst().productId());
        Instant expiresAt = clock.instant().plus(Duration.ofMinutes(properties.checkout().ttlMinutes()));
        Checkout checkout = Checkout.create(merchantId, mode, conversationId, sessionRef, first.getCurrency(),
                expiresAt);

        for (LineRequest line : lines) {
            addValidatedLine(checkout, merchantId, mode, line.productId(), line.quantity());
        }
        return checkoutRepository.save(checkout);
    }

    /** Reads a checkout, expiring it first if its time has passed. */
    @Transactional
    public Checkout require(UUID merchantId, String mode, UUID checkoutId) {
        Checkout checkout = checkoutRepository.findByIdAndMerchantIdAndMode(checkoutId, merchantId, mode)
                .orElseThrow(() -> ResourceNotFoundException.of("Checkout", checkoutId));
        return expireIfDue(checkout);
    }

    @Transactional
    public Checkout addItem(UUID merchantId, String mode, UUID checkoutId, UUID productId, int quantity) {
        Checkout checkout = require(merchantId, mode, checkoutId);
        addValidatedLine(checkout, merchantId, mode, productId, quantity);
        return checkoutRepository.save(checkout);
    }

    @Transactional
    public Checkout setItemQuantity(UUID merchantId, String mode, UUID checkoutId, UUID productId, int quantity) {
        Checkout checkout = require(merchantId, mode, checkoutId);
        if (quantity > 0) {
            // Re-check availability at the new quantity: raising a line is as much a claim on
            // stock as adding one, and skipping the check here would make the guard depend on
            // which method the caller happened to use.
            Product product = catalogService.require(merchantId, mode, productId);
            requireAvailability(product, quantity);
        }
        checkout.setItemQuantity(productId, quantity);
        return checkoutRepository.save(checkout);
    }

    @Transactional
    public Checkout applyDiscount(UUID merchantId, String mode, UUID checkoutId, long discountMinor) {
        Checkout checkout = require(merchantId, mode, checkoutId);
        checkout.applyDiscount(discountMinor);
        return checkoutRepository.save(checkout);
    }

    @Transactional
    public Checkout cancel(UUID merchantId, String mode, UUID checkoutId) {
        Checkout checkout = require(merchantId, mode, checkoutId);
        checkout.cancel();
        return checkoutRepository.save(checkout);
    }

    // ── The payment path ────────────────────────────────────────────────────────────────

    /**
     * Reserves a quote for a payment attempt and returns it with its amount frozen.
     *
     * <p>This is the method that turns "the model asked to pay for checkout X" into a number.
     * Nothing downstream of it may substitute a different amount, and nothing upstream of it
     * was ever asked for one.
     */
    @Transactional
    public Checkout lockForPayment(UUID merchantId, String mode, UUID checkoutId) {
        Checkout checkout = require(merchantId, mode, checkoutId);
        checkout.lockForPayment(clock.instant());
        return checkoutRepository.save(checkout);
    }

    /** The attempt failed. The basket survives so the customer can try a different instrument. */
    @Transactional
    public void releaseLock(UUID checkoutId) {
        checkoutRepository.findById(checkoutId).ifPresent(checkout -> {
            checkout.releaseLock();
            checkoutRepository.save(checkout);
        });
    }

    /**
     * The payment succeeded. Stock is decremented here and only here — see {@code Product}'s
     * javadoc for why reservation happens at payment rather than at checkout creation, and
     * what that costs.
     */
    @Transactional
    public Checkout markPaid(UUID merchantId, String mode, UUID checkoutId, UUID paymentId,
                             String providerReference) {
        Checkout checkout = require(merchantId, mode, checkoutId);
        checkout.markPaid(paymentId, providerReference);
        for (CheckoutItem item : checkout.getItems()) {
            productRepository.findByIdAndMerchantIdAndMode(item.getProductId(), merchantId, mode)
                    .ifPresent(product -> product.reduceInventory(
                            Math.min(item.getQuantity(), product.getInventoryCount())));
        }
        return checkoutRepository.save(checkout);
    }

    @Transactional
    public void attachProviderReference(UUID checkoutId, String providerReference) {
        checkoutRepository.findById(checkoutId).ifPresent(checkout -> {
            checkout.attachProviderReference(providerReference);
            checkoutRepository.save(checkout);
        });
    }

    // ── Internals ───────────────────────────────────────────────────────────────────────

    private void addValidatedLine(Checkout checkout, UUID merchantId, String mode, UUID productId, int quantity) {
        Product product = catalogService.require(merchantId, mode, productId);
        requireAvailability(product, quantity);
        if (!product.getCurrency().equals(checkout.getCurrency())) {
            throw new AgenticException(AgenticErrorCode.CHECKOUT_CURRENCY_MISMATCH,
                    "This checkout is in %s and that product is priced in %s."
                            .formatted(checkout.getCurrency(), product.getCurrency()));
        }
        checkout.addItem(product.getId(), product.getSku(), product.getName(), quantity, product.getPriceMinor());
    }

    private static void requireAvailability(Product product, int quantity) {
        if (!product.hasAvailability(quantity)) {
            throw new AgenticException(AgenticErrorCode.INSUFFICIENT_INVENTORY,
                    "%s does not have %d unit(s) available.".formatted(product.getName(), quantity));
        }
    }

    private Checkout expireIfDue(Checkout checkout) {
        if (checkout.getStatus() == CheckoutStatus.OPEN && checkout.isExpired(clock.instant())) {
            checkout.expire();
            return checkoutRepository.save(checkout);
        }
        return checkout;
    }
}
