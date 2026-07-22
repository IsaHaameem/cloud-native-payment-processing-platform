package com.paymentflow.merchant.service;

import com.paymentflow.merchant.config.ApiKeyProperties;
import com.paymentflow.merchant.domain.ApiKey;
import com.paymentflow.merchant.domain.ApiKeyType;
import com.paymentflow.merchant.domain.KeyMode;
import com.paymentflow.merchant.event.MerchantEventPublisher;
import com.paymentflow.merchant.repository.ApiKeyRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ApiKeyServiceTest {

    @Mock
    private ApiKeyRepository apiKeyRepository;
    @Mock
    private MerchantEventPublisher eventPublisher;
    // Deep stubs: meterRegistry.counter(...) must return a (mock) Counter, not null.
    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    private MeterRegistry meterRegistry;
    @Mock
    private StringRedisTemplate redisTemplate;

    private ApiKeyProperties apiKeyProperties;
    private ApiKeyService apiKeyService;

    @BeforeEach
    void setUp() {
        apiKeyProperties = new ApiKeyProperties(Duration.ofHours(24), Duration.ofSeconds(60));
        apiKeyService = new ApiKeyService(apiKeyRepository, apiKeyProperties, redisTemplate, eventPublisher, meterRegistry);
        // lenient: not every test in this class actually issues/saves a key (the
        // verify()-on-an-unknown/revoked-key tests never reach a save() call).
        lenient().when(apiKeyRepository.save(any(ApiKey.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void issueGeneratesCorrectlyFormattedKeyAndPersistsOnlyItsHash() {
        UUID merchantId = UUID.randomUUID();

        ApiKeyService.IssuedApiKey issued =
                apiKeyService.issue(merchantId, ApiKeyType.SECRET, KeyMode.TEST, "My key", List.of("payments:write"));

        assertThat(issued.rawValue()).startsWith("sk_test_");
        assertThat(issued.apiKey().getKeyPrefix()).isEqualTo(issued.rawValue().substring(0, 12));
        assertThat(issued.apiKey().getKeyHash()).isNotEqualTo(issued.rawValue());
        assertThat(issued.apiKey().getMerchantId()).isEqualTo(merchantId);
        assertThat(issued.apiKey().getScopes()).containsExactly("payments:write");
        assertThat(issued.apiKey().isActive(Instant.now())).isTrue();
    }

    @Test
    void issueDefaultsScopesByKeyType() {
        UUID merchantId = UUID.randomUUID();

        ApiKeyService.IssuedApiKey publishable =
                apiKeyService.issue(merchantId, ApiKeyType.PUBLISHABLE, KeyMode.TEST, null, null);
        ApiKeyService.IssuedApiKey secret = apiKeyService.issue(merchantId, ApiKeyType.SECRET, KeyMode.LIVE, null, null);

        assertThat(publishable.apiKey().getScopes()).containsExactly("payments:read");
        assertThat(secret.apiKey().getScopes()).containsExactly("*");
    }

    @Test
    void issueDefaultSetIssuesAllFourKeyTypes() {
        UUID merchantId = UUID.randomUUID();

        List<ApiKeyService.IssuedApiKey> issued = apiKeyService.issueDefaultSet(merchantId);

        assertThat(issued).hasSize(4);
        assertThat(issued.stream().map(k -> k.rawValue().substring(0, k.rawValue().indexOf('_'))))
                .containsExactlyInAnyOrder("pk", "pk", "sk", "sk");
    }

    @Test
    void verifyReturnsEmptyForAnUnknownKey() {
        when(apiKeyRepository.findByKeyHash(anyString())).thenReturn(Optional.empty());

        assertThat(apiKeyService.verify("sk_test_doesnotexist")).isEmpty();
    }

    @Test
    void verifyReturnsEmptyForARevokedKey() {
        ApiKey key = ApiKey.issue(UUID.randomUUID(), ApiKeyType.SECRET, KeyMode.TEST, "name", "sk_test_abc123",
                "hash", List.of("*"), null);
        key.revoke();
        when(apiKeyRepository.findByKeyHash(anyString())).thenReturn(Optional.of(key));

        assertThat(apiKeyService.verify("sk_test_abc123whatever")).isEmpty();
    }

    @Test
    void verifyReturnsTheKeyWhenActiveAndTouchesLastUsedOncePerThrottleWindow() {
        ApiKey key = ApiKey.issue(UUID.randomUUID(), ApiKeyType.SECRET, KeyMode.TEST, "name", "sk_test_abc123",
                "hash", List.of("*"), null);
        when(apiKeyRepository.findByKeyHash(anyString())).thenReturn(Optional.of(key));

        ValueOperations<String, String> valueOperations = mock();
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);

        Optional<ApiKey> result = apiKeyService.verify("sk_test_abc123whatever");

        assertThat(result).contains(key);
    }

    @Test
    void revokeMarksTheKeyRevokedAndEvictsTheGatewayVerifyCache() {
        UUID merchantId = UUID.randomUUID();
        ApiKey key = ApiKey.issue(merchantId, ApiKeyType.SECRET, KeyMode.TEST, "name", "sk_test_abc123",
                "somehash", List.of("*"), null);
        setId(key, UUID.randomUUID());
        when(apiKeyRepository.findByIdAndMerchantId(key.getId(), merchantId)).thenReturn(Optional.of(key));

        apiKeyService.revoke(merchantId, key.getId());

        assertThat(key.isActive(Instant.now())).isFalse();
        verify(redisTemplate).delete("apikey:v1:somehash");
        verify(eventPublisher).publishApiKeyEvent("ApiKeyRevoked", key);
    }

    @Test
    void rotateWithGraceGrantsTheOldKeyAGraceWindowAndIssuesAReplacement() {
        UUID merchantId = UUID.randomUUID();
        ApiKey existing = ApiKey.issue(merchantId, ApiKeyType.SECRET, KeyMode.LIVE, "Prod key", "sk_live_abc123",
                "somehash", List.of("payments:write"), null);
        setId(existing, UUID.randomUUID());
        when(apiKeyRepository.findByIdAndMerchantId(existing.getId(), merchantId)).thenReturn(Optional.of(existing));

        ApiKeyService.IssuedApiKey rotated = apiKeyService.rotateWithGrace(merchantId, existing.getId());

        assertThat(existing.getGraceExpiresAt()).isNotNull();
        assertThat(existing.isActive(Instant.now())).isTrue(); // still active during grace
        assertThat(rotated.rawValue()).startsWith("sk_live_");
        assertThat(rotated.apiKey().getScopes()).containsExactly("payments:write");

        ArgumentCaptor<String> eventTypeCaptor = ArgumentCaptor.forClass(String.class);
        verify(eventPublisher, times(2)).publishApiKeyEvent(eventTypeCaptor.capture(), any(ApiKey.class));
        assertThat(eventTypeCaptor.getAllValues()).containsExactly("ApiKeyRotated", "ApiKeyIssued");
    }

    /** Reflection helper: {@code ApiKey.getId()} is normally JPA-generated. */
    private static void setId(ApiKey key, UUID id) {
        try {
            var field = ApiKey.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(key, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
