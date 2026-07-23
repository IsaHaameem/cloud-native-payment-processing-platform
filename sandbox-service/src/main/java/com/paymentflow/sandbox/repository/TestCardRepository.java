package com.paymentflow.sandbox.repository;

import com.paymentflow.sandbox.domain.TestCard;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TestCardRepository extends JpaRepository<TestCard, String> {

    List<TestCard> findByActiveTrueOrderByTokenAsc();

    Optional<TestCard> findByTokenAndActiveTrue(String token);
}
