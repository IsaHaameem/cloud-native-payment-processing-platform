package com.paymentflow.payment.merchant;

import feign.Request;
import org.springframework.context.annotation.Bean;

import java.util.concurrent.TimeUnit;

/**
 * Hard socket-level timeouts for the merchant-service Feign client (M8) — the real
 * defense against a hung call, backing up Resilience4j's {@code TimeLimiter}.
 * Cancelling a {@code CompletableFuture} (what {@code TimeLimiter} does when it times
 * out) does not interrupt an in-flight blocking socket read; without a socket-level
 * timeout of its own, an already-started HTTP call would keep running on its
 * {@code ThreadPoolBulkhead} thread even after the caller has already given up on it.
 * Both timeouts are kept comfortably under the app-level {@code TimeLimiter} budget
 * (see {@code application.yaml}) so the socket gives up first in the common case.
 *
 * <p>Deliberately <em>not</em> annotated {@code @Configuration}: it is registered only
 * via {@code @FeignClient(configuration = ...)} on {@link MerchantClient}. Spring Cloud
 * OpenFeign gives every client its own child context seeded with a default
 * {@code Request.Options} bean; if this class were also picked up by the app's normal
 * component scan, its bean would collide with (or unpredictably shadow) that default
 * across whichever Feign client resolved first.
 */
public class FeignClientConfig {

    @Bean
    public Request.Options merchantServiceRequestOptions(MerchantResilienceProperties properties) {
        return new Request.Options(
                properties.connectTimeoutMs(), TimeUnit.MILLISECONDS,
                properties.readTimeoutMs(), TimeUnit.MILLISECONDS,
                true);
    }
}
