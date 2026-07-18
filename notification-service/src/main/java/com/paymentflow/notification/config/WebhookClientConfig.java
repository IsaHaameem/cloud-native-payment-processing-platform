package com.paymentflow.notification.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

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
}
