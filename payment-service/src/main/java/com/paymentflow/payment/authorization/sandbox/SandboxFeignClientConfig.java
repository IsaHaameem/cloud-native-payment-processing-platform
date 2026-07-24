package com.paymentflow.payment.authorization.sandbox;

import feign.Request;
import org.springframework.context.annotation.Bean;

import java.util.concurrent.TimeUnit;

/**
 * Hard socket-level timeouts for the sandbox-service Feign client — mirrors
 * {@code FeignClientConfig}'s rationale for merchant-service exactly (M8), just
 * budgeted for a call that can legitimately run for seconds, not milliseconds:
 * {@code pm_card_slow} injects ~5s of latency by design (§8.1), and the schema-enforced
 * ceiling on any test card's latency is 10s. The defaults in {@code application.yaml}
 * keep both this socket read timeout and the {@code sandboxService} TimeLimiter's
 * budget comfortably above that 5s figure, with the socket giving up first.
 *
 * <p>Deliberately <em>not</em> annotated {@code @Configuration} — same reasoning as
 * {@code FeignClientConfig}: registered only via {@code @FeignClient(configuration =
 * ...)} on {@link SandboxClient} so its {@code Request.Options} bean can't collide with
 * another Feign client's default.
 */
public class SandboxFeignClientConfig {

    @Bean
    public Request.Options sandboxServiceRequestOptions(SandboxResilienceProperties properties) {
        return new Request.Options(
                properties.connectTimeoutMs(), TimeUnit.MILLISECONDS,
                properties.readTimeoutMs(), TimeUnit.MILLISECONDS,
                true);
    }
}
