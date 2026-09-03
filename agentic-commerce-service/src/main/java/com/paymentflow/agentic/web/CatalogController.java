package com.paymentflow.agentic.web;

import com.paymentflow.agentic.catalog.CatalogService;
import com.paymentflow.agentic.catalog.ProductView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * The merchant-facing read view of the catalogue (G-2).
 *
 * <pre>
 *   GET /api/agentic/catalog/products?category=&page=&limit=   paginated, active products
 *   GET /api/agentic/catalog/products?query=                   capped text search (no paging)
 *   GET /api/agentic/catalog/products/{id}                     one product
 *   GET /api/agentic/catalog/categories                        distinct categories, for a filter
 * </pre>
 *
 * <h2>What this is not</h2>
 *
 * <p>Read-only. No create/update/delete: the catalogue the agent sells from is seeded
 * infrastructure for the demonstration (see {@code CatalogSeeder}); a merchant-facing product
 * CRUD is a feature in its own right, out of scope for this layer.
 *
 * <p>Every response is a {@link ProductView}, never the {@code Product} entity — {@code merchantId}
 * and {@code mode} are context the caller already is, {@code metadata} has no place in a
 * projection, and availability is a boolean not a stock count. Merchant and mode come from
 * {@link AgenticCallerContext}.
 */
@RestController
@RequestMapping("/api/agentic/catalog")
public class CatalogController {

    private final CatalogService catalogService;
    private final AgenticCallerContext callerContext;

    public CatalogController(CatalogService catalogService, AgenticCallerContext callerContext) {
        this.catalogService = catalogService;
        this.callerContext = callerContext;
    }

    @GetMapping("/products")
    public PageResponse<ProductView> list(@RequestParam(required = false) String query,
                                          @RequestParam(required = false) String category,
                                          @RequestParam(required = false) Integer page,
                                          @RequestParam(required = false) Integer limit) {
        AgenticCallerContext.Caller caller = callerContext.resolve();
        int clampedLimit = PageResponse.clampLimit(limit);

        // A free-text query reuses the existing capped tool search (max 20, no paging). It is a
        // "find me something" affordance, not a browsable index, and treating it as one keeps a
        // count query off a LIKE scan.
        if (query != null && !query.isBlank()) {
            List<ProductView> hits = catalogService.search(caller.merchantId(), caller.mode(),
                    query, category, clampedLimit);
            return new PageResponse<>(hits, 0, clampedLimit, hits.size(), false);
        }

        int clampedPage = PageResponse.clampPage(page);
        CatalogService.Page result = catalogService.listActive(caller.merchantId(), caller.mode(),
                category, clampedPage, clampedLimit);
        return PageResponse.of(result.products(), clampedPage, clampedLimit, result.total(), ProductView::of);
    }

    @GetMapping("/products/{id}")
    public ProductView get(@PathVariable UUID id) {
        AgenticCallerContext.Caller caller = callerContext.resolve();
        return catalogService.get(caller.merchantId(), caller.mode(), id);
    }

    @GetMapping("/categories")
    public List<String> categories() {
        AgenticCallerContext.Caller caller = callerContext.resolve();
        return catalogService.search(caller.merchantId(), caller.mode(), null, null, 100).stream()
                .map(ProductView::category)
                .filter(Objects::nonNull)
                .distinct()
                .sorted()
                .toList();
    }
}
