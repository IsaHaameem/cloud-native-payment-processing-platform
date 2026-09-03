package com.paymentflow.agentic.catalog;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Every query here is scoped by {@code (merchantId, mode)} without exception.
 *
 * <p>There is no unscoped finder — not even {@code findById} — and that is the point. A
 * repository that exposes one is a repository where a cross-tenant read is a single forgotten
 * parameter away, and this service hands its results to a model that will happily quote them
 * back. The scoping is structural rather than a convention every call site has to remember.
 */
public interface ProductRepository extends JpaRepository<Product, UUID> {

    Optional<Product> findByIdAndMerchantIdAndMode(UUID id, UUID merchantId, String mode);

    Optional<Product> findBySkuAndMerchantIdAndMode(String sku, UUID merchantId, String mode);

    List<Product> findByMerchantIdAndModeAndActiveTrueOrderByNameAsc(UUID merchantId, String mode, Pageable pageable);

    List<Product> findByMerchantIdAndModeAndCategoryAndActiveTrueOrderByNameAsc(
            UUID merchantId, String mode, String category, Pageable pageable);

    boolean existsByMerchantIdAndMode(UUID merchantId, String mode);

    long countByMerchantIdAndModeAndActiveTrue(UUID merchantId, String mode);

    long countByMerchantIdAndModeAndActiveTrueAndCategory(UUID merchantId, String mode, String category);

    /**
     * Case-insensitive substring match over name and description.
     *
     * <p>Deliberately not full-text search. The catalogue is demo-sized, {@code pg_trgm} is
     * not installed on this platform, and a tsvector column would need a trigger to stay in
     * step with the two columns it indexes — three moving parts to serve a query over tens of
     * rows. Recorded in V1's own comment so the first milestone with a real catalogue knows
     * the choice was made rather than missed.
     */
    @Query("""
            select p from Product p
            where p.merchantId = :merchantId
              and p.mode = :mode
              and p.active = true
              and (lower(p.name) like lower(concat('%', :term, '%'))
                   or lower(p.description) like lower(concat('%', :term, '%'))
                   or lower(p.category) like lower(concat('%', :term, '%')))
            order by p.name asc
            """)
    List<Product> search(@Param("merchantId") UUID merchantId, @Param("mode") String mode,
                         @Param("term") String term, Pageable pageable);

    /**
     * Other active products in the same category, excluding one. The whole of the
     * recommendation and cross-sell mechanism — see {@code CatalogService} for why it is
     * deliberately this and not a model.
     */
    @Query("""
            select p from Product p
            where p.merchantId = :merchantId
              and p.mode = :mode
              and p.active = true
              and p.category = :category
              and p.id <> :excludeProductId
              and p.inventoryCount > 0
            order by p.priceMinor asc
            """)
    List<Product> findRelated(@Param("merchantId") UUID merchantId, @Param("mode") String mode,
                              @Param("category") String category, @Param("excludeProductId") UUID excludeProductId,
                              Pageable pageable);
}
