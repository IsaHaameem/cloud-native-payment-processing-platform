package com.paymentflow.sandbox.service;

import com.paymentflow.sandbox.domain.TestCard;
import com.paymentflow.sandbox.repository.TestCardRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * Read access to the test-card catalogue (§8.1). The single source both the public
 * catalogue endpoint (M17.1) and the decision engine's card-lookup step (M17.2) go
 * through, so "what a token means" is never looked up two different ways.
 */
@Service
public class TestCardService {

    private final TestCardRepository testCardRepository;

    public TestCardService(TestCardRepository testCardRepository) {
        this.testCardRepository = testCardRepository;
    }

    public List<TestCard> listActive() {
        return testCardRepository.findByActiveTrueOrderByTokenAsc();
    }

    /** The active card for a token, or empty if the token is unknown or has been retired. */
    public Optional<TestCard> findActive(String token) {
        if (token == null) {
            return Optional.empty();
        }
        return testCardRepository.findByTokenAndActiveTrue(token);
    }
}
