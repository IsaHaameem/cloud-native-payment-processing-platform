package com.paymentflow.agentic.catalog;

import com.paymentflow.common.exception.ResourceNotFoundException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Read and search over a merchant's catalogue, plus the recommendation the upsell tool uses.
 *
 * <p><b>Recommendation is deterministic, and deliberately so.</b> It is "other in-stock
 * products in the same category, cheapest first" — a database query, not a model. Two
 * reasons, and the second is the important one. First, a payments demonstration learns
 * nothing from a recommender. Second, and this is the boundary the whole service is built
 * around: if the model chose what to recommend <em>and</em> what to charge, there would be no
 * server-side fact left to check it against. The model may argue for a recommendation; it
 * cannot invent the product, the price, or the availability behind it.
 */
@Service
@Transactional(readOnly = true)
public class CatalogService {

    /** A tool result is prompt context, and prompt context is a budget. Twenty rows is already generous. */
    private static final int MAX_SEARCH_RESULTS = 20;
    private static final int MAX_RECOMMENDATIONS = 3;

    private final ProductRepository productRepository;

    public CatalogService(ProductRepository productRepository) {
        this.productRepository = productRepository;
    }

    /**
     * Search by free text, optionally narrowed to a category. A blank term lists the
     * catalogue rather than matching nothing — the model asking "what do you sell?" is a
     * legitimate first question and should not require it to guess a keyword.
     */
    public List<ProductView> search(UUID merchantId, String mode, String term, String category, int limit) {
        int capped = Math.clamp(limit, 1, MAX_SEARCH_RESULTS);
        PageRequest page = PageRequest.of(0, capped);

        List<Product> found;
        if (term != null && !term.isBlank()) {
            found = productRepository.search(merchantId, mode, term.trim(), page);
        } else if (category != null && !category.isBlank()) {
            found = productRepository.findByMerchantIdAndModeAndCategoryAndActiveTrueOrderByNameAsc(
                    merchantId, mode, category.trim(), page);
        } else {
            found = productRepository.findByMerchantIdAndModeAndActiveTrueOrderByNameAsc(merchantId, mode, page);
        }

        // The category filter is applied after a text search rather than inside it, so that a
        // term and a category compose instead of the query needing four variants.
        if (term != null && !term.isBlank() && category != null && !category.isBlank()) {
            found = found.stream().filter(p -> category.trim().equalsIgnoreCase(p.getCategory())).toList();
        }
        return found.stream().map(ProductView::of).toList();
    }

    public ProductView get(UUID merchantId, String mode, UUID productId) {
        return ProductView.of(require(merchantId, mode, productId));
    }

    /** A page of active products with its exact total, for the merchant-facing catalogue list (G-2). */
    public record Page(List<Product> products, long total) {
    }

    /**
     * The paginated, browsable list — active products only, optionally narrowed to a category,
     * name-ordered. Unlike {@link #search}, this is a real index with an exact count, because a
     * merchant reviewing their catalogue needs to know how much of it there is.
     */
    public Page listActive(UUID merchantId, String mode, String category, int page, int limit) {
        PageRequest pageRequest = PageRequest.of(page, limit);
        if (category != null && !category.isBlank()) {
            String trimmed = category.trim();
            return new Page(
                    productRepository.findByMerchantIdAndModeAndCategoryAndActiveTrueOrderByNameAsc(
                            merchantId, mode, trimmed, pageRequest),
                    productRepository.countByMerchantIdAndModeAndActiveTrueAndCategory(merchantId, mode, trimmed));
        }
        return new Page(
                productRepository.findByMerchantIdAndModeAndActiveTrueOrderByNameAsc(merchantId, mode, pageRequest),
                productRepository.countByMerchantIdAndModeAndActiveTrue(merchantId, mode));
    }

    /**
     * The entity, for callers inside this service that need more than the public projection —
     * checkout pricing needs the real price, not the view of it. Still merchant- and
     * mode-scoped; a missing product is a 404, never a null the caller might dereference.
     */
    public Product require(UUID merchantId, String mode, UUID productId) {
        return productRepository.findByIdAndMerchantIdAndMode(productId, merchantId, mode)
                .orElseThrow(() -> ResourceNotFoundException.of("Product", productId));
    }

    /** Other in-stock products in the same category. The whole of the cross-sell mechanism. */
    public List<ProductView> recommend(UUID merchantId, String mode, UUID productId, int limit) {
        Product anchor = require(merchantId, mode, productId);
        int capped = Math.clamp(limit, 1, MAX_RECOMMENDATIONS);
        return productRepository
                .findRelated(merchantId, mode, anchor.getCategory(), anchor.getId(), PageRequest.of(0, capped))
                .stream()
                .map(ProductView::of)
                .toList();
    }
}
