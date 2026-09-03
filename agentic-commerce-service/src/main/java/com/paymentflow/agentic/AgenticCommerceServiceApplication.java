package com.paymentflow.agentic;

import com.paymentflow.agentic.config.AgenticProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * agentic-commerce-service — the AI Growth &amp; Agentic Commerce layer (Project 3).
 *
 * <p>This service sits <em>above</em> the payment platform and is an ordinary external
 * consumer of its public {@code /v1} API. It owns no financial state: no payment, no
 * refund, no ledger entry and no audit row is ever written from here. What it owns is the
 * reasoning that leads to a money action being <em>requested</em>, and the record of every
 * such request — including the ones that were refused.
 *
 * <p>The one inbound path from the platform is
 * {@code POST /internal/v1/providers/external/decisions}, which payment-service calls for an
 * acquirer verdict over the same HMAC-signed internal context sandbox-service has accepted
 * since M17. That endpoint is a provider adapter; it shares no code path with the agent.
 *
 * <p>Deleting this module returns the platform to its pre-extension behaviour. Nothing in
 * the payment core depends on this service at compile time, and at runtime only a payment
 * that opts in by carrying an {@code rzp_}-prefixed payment-method token ever reaches it.
 */
@SpringBootApplication
@EnableConfigurationProperties(AgenticProperties.class)
public class AgenticCommerceServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(AgenticCommerceServiceApplication.class, args);
    }
}
