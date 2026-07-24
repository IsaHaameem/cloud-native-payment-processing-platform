package com.paymentflow.payment.authorization.sandbox;

import com.paymentflow.common.security.InternalContextHeaders;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;

/**
 * Synchronous call to sandbox-service's internal decision endpoint (D103) — the only
 * Feign client {@link SandboxAuthorizationAdvisor} ever calls. Calls sandbox-service
 * directly, not through the gateway (D103's own endpoint is never gateway-routed).
 *
 * <p>The internal-context headers are supplied explicitly per call, not via a shared
 * {@code RequestInterceptor}/{@code ThreadLocal}: unlike {@code MerchantClient}, this
 * call carries no caller JWT to forward, so there is no request-scoped state that needs
 * propagating onto the resilience chain's own bulkhead thread (contrast
 * {@code MerchantResolver}'s {@code RequestAttributes} hand-off, itself needed only
 * because of that forwarding).
 */
@FeignClient(name = "sandbox-service", url = "${paymentflow.services.sandbox.base-uri}",
        configuration = SandboxFeignClientConfig.class)
public interface SandboxClient {

    @PostMapping("/internal/v1/sandbox/decisions")
    SandboxDecisionResponse decide(
            @RequestHeader(InternalContextHeaders.MERCHANT_ID) String merchantId,
            @RequestHeader(InternalContextHeaders.MODE) String mode,
            @RequestHeader(InternalContextHeaders.KEY_ID) String keyId,
            @RequestHeader(InternalContextHeaders.SCOPES) String scopes,
            @RequestHeader(InternalContextHeaders.ISSUED_AT) String issuedAt,
            @RequestHeader(InternalContextHeaders.SIGNATURE) String signature,
            @RequestBody SandboxDecisionRequest request);
}
