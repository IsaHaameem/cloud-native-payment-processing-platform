package com.paymentflow.payment.merchant;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Synchronous call to merchant-service (OpenFeign — the tech stack's stated exception
 * to "async by default": a payment cannot proceed without knowing which merchant it
 * belongs to). Calls merchant-service directly, not through the gateway — this is
 * internal service-to-service traffic, not external client traffic.
 */
@FeignClient(name = "merchant-service", url = "${paymentflow.services.merchant.base-uri}",
        configuration = FeignClientConfig.class)
public interface MerchantClient {

    @GetMapping("/api/v1/merchants/me")
    MerchantSummary getMine();
}
