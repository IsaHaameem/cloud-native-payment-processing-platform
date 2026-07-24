package com.paymentflow.sandbox.repository;

import com.paymentflow.sandbox.domain.SimulationOverride;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface SimulationOverrideRepository extends JpaRepository<SimulationOverride, UUID> {

    Optional<SimulationOverride> findByMerchantIdAndModeAndRevokedAtIsNull(UUID merchantId, String mode);

    /** Soft-ends whatever override is currently active for (merchantId, mode), if any. */
    @Modifying
    @Query("update SimulationOverride o set o.revokedAt = :now "
            + "where o.merchantId = :merchantId and o.mode = :mode and o.revokedAt is null")
    int revokeActive(@Param("merchantId") UUID merchantId, @Param("mode") String mode, @Param("now") Instant now);

    /**
     * D127: a single atomic conditional UPDATE, not a read-modify-write — a
     * {@code remainingCount} of {@code null} (unbounded by count) never matches
     * {@code > 0} in SQL, so this is naturally a no-op for those rows rather than
     * needing a separate branch.
     */
    @Modifying
    @Query("update SimulationOverride o set o.remainingCount = o.remainingCount - 1 "
            + "where o.id = :id and o.remainingCount > 0")
    int decrementRemainingCount(@Param("id") UUID id);
}
