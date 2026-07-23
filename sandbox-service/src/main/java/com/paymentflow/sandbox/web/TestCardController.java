package com.paymentflow.sandbox.web;

import com.paymentflow.sandbox.dto.TestCardResponse;
import com.paymentflow.sandbox.mapper.TestCardMapper;
import com.paymentflow.sandbox.service.TestCardService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Public, unauthenticated reference-data endpoint (§8.1) — the catalogue is identical
 * for every merchant, so it carries no merchant or mode context, unlike every other
 * sandbox endpoint. Also the single source the documentation (M17.8) renders from, so
 * the docs and a real call can never drift.
 */
@RestController
public class TestCardController {

    private final TestCardService testCardService;
    private final TestCardMapper testCardMapper;

    public TestCardController(TestCardService testCardService, TestCardMapper testCardMapper) {
        this.testCardService = testCardService;
        this.testCardMapper = testCardMapper;
    }

    @GetMapping("/v1/test/cards")
    public List<TestCardResponse> listCards() {
        return testCardService.listActive().stream().map(testCardMapper::toResponse).toList();
    }
}
