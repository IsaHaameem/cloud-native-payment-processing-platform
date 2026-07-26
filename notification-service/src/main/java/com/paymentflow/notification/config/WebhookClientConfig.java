package com.paymentflow.notification.config;

import com.paymentflow.notification.egress.EgressPolicy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.InetAddress;

/**
 * A short-timeout {@link RestClient} for outbound webhook delivery to arbitrary,
 * merchant-configured URLs — an unresponsive merchant endpoint must fail fast so it
 * doesn't tie up a Kafka consumer thread indefinitely (the first attempt runs inline,
 * post-commit, on the main listener's thread — see {@code NotificationService}).
 */
@Configuration
public class WebhookClientConfig {

    @Bean
    public RestClient webhookRestClient(NotificationProperties properties) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.webhookConnectTimeoutMs());
        requestFactory.setReadTimeout(properties.webhookReadTimeoutMs());
        return RestClient.builder().requestFactory(requestFactory).build();
    }

    /**
     * The DNS lookup {@code EgressPolicy} performs immediately before every connect
     * (M18.5). Injected as a bean rather than called statically so the hostile-URL table
     * can be tested exhaustively — including names that resolve to private addresses —
     * without a network, a DNS server, or an internet-dependent test.
     *
     * <p>{@code getAllByName}, not {@code getByName}: a hostile record can return one
     * public and one private address, and checking only the first would let the second
     * through.
     */
    @Bean
    public EgressPolicy.HostResolver hostResolver() {
        return InetAddress::getAllByName;
    }
}
