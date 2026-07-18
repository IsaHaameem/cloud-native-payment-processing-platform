package com.paymentflow.notification.service;

import com.paymentflow.notification.config.NotificationProperties;
import com.paymentflow.notification.domain.DeliveryStatus;
import com.paymentflow.notification.domain.WebhookDelivery;
import com.paymentflow.notification.repository.WebhookDeliveryRepository;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.client.RestClient;

import java.net.InetSocketAddress;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Uses a real, throwaway JDK {@link HttpServer} standing in for a merchant's webhook
 * endpoint (same pattern as MerchantIntegrationTest's JWKS stub, M4) rather than
 * deep-mocking {@link RestClient}'s fluent builder chain — exercises the real HTTP
 * client, not a mocked one.
 */
@ExtendWith(MockitoExtension.class)
class WebhookDeliveryServiceTest {

    @Mock
    private WebhookDeliveryRepository webhookDeliveryRepository;
    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;

    private HttpServer server;
    private WebhookDeliveryService webhookDeliveryService;
    private NotificationProperties properties;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        properties = new NotificationProperties("payment.events.retry", "group", 3,
                "payment.events.dlq", 3000, 5000);
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(1000);
        requestFactory.setReadTimeout(1000);
        RestClient restClient = RestClient.builder().requestFactory(requestFactory).build();
        webhookDeliveryService = new WebhookDeliveryService(webhookDeliveryRepository, restClient, kafkaTemplate, properties);
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    private WebhookDelivery pendingDelivery(String url) {
        return WebhookDelivery.pending(UUID.randomUUID(), UUID.randomUUID(), url, "{\"a\":1}");
    }

    @Test
    void a2xxResponseMarksTheDeliveryDelivered() {
        AtomicInteger callCount = new AtomicInteger();
        server.createContext("/hook", exchange -> {
            callCount.incrementAndGet();
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
        WebhookDelivery delivery = pendingDelivery("http://localhost:" + server.getAddress().getPort() + "/hook");
        when(webhookDeliveryRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        webhookDeliveryService.attemptDelivery(delivery);

        assertThat(callCount.get()).isEqualTo(1);
        assertThat(delivery.getStatus()).isEqualTo(DeliveryStatus.DELIVERED);
        verify(kafkaTemplate, never()).send(eq("payment.events.retry"), any());
    }

    @Test
    void aNonSuccessStatusRecordsAFailedAttemptAndPublishesToTheRetryTopic() {
        server.createContext("/hook", exchange -> {
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
        });
        server.start();
        WebhookDelivery delivery = pendingDelivery("http://localhost:" + server.getAddress().getPort() + "/hook");
        when(webhookDeliveryRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        webhookDeliveryService.attemptDelivery(delivery);

        assertThat(delivery.getStatus()).isEqualTo(DeliveryStatus.PENDING);
        assertThat(delivery.getAttemptCount()).isEqualTo(1);
        verify(kafkaTemplate).send("payment.events.retry", delivery.getEventId().toString());
    }

    @Test
    void anUnreachableUrlRecordsAFailedAttemptAndPublishesToTheRetryTopic() {
        // Nothing listening on this port — connection refused, not a timeout.
        WebhookDelivery delivery = pendingDelivery("http://localhost:1/hook");
        when(webhookDeliveryRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        webhookDeliveryService.attemptDelivery(delivery);

        assertThat(delivery.getStatus()).isEqualTo(DeliveryStatus.PENDING);
        assertThat(delivery.getAttemptCount()).isEqualTo(1);
        verify(kafkaTemplate).send("payment.events.retry", delivery.getEventId().toString());
    }
}
