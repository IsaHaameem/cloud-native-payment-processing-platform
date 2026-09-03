package com.paymentflow.agentic.platform;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * One entry from the platform's test-instrument catalogue.
 *
 * <p>Only {@code token} is load-bearing: it is the allow-list entry that decides whether an
 * instrument the model named may be used at all. The rest is carried because it is what makes
 * a demo explicable — a reviewer asking "why did that decline?" can be shown the catalogue
 * entry that says it always does.
 *
 * <p>The model is never handed this list. AD-12 dropped {@code list_test_cards} as a tool
 * deliberately: letting the model choose its own failure mode would undermine the credibility
 * of every failure the demo shows. The instrument is chosen in the UI and passed as data.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TestCardView(
        String token,
        String brand,
        String outcome,
        String declineCode,
        String errorCode,
        String captureBehaviour,
        String description) {
}
