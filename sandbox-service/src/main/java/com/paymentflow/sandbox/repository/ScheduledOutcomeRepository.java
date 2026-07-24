package com.paymentflow.sandbox.repository;

import com.paymentflow.sandbox.domain.ScheduledOutcome;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface ScheduledOutcomeRepository extends JpaRepository<ScheduledOutcome, UUID> {

    List<ScheduledOutcome> findTop50ByDeliveredAtIsNullAndFireAtLessThanEqualOrderByFireAtAsc(Instant now);
}
