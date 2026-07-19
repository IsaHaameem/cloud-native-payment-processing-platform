package com.paymentflow.payment.idempotency;

import com.paymentflow.payment.domain.IdempotencyRecord;
import com.paymentflow.payment.exception.IdempotencyKeyInFlightException;
import com.paymentflow.payment.exception.IdempotencyKeyReusedException;
import com.paymentflow.payment.repository.IdempotencyRecordRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IdempotencyServiceTest {

    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private ValueOperations<String, String> valueOperations;
    @Mock
    private IdempotencyRecordRepository repository;

    private final ObjectMapper objectMapper = JsonMapper.builder().build();
    private final UUID merchantId = UUID.randomUUID();

    private IdempotencyService idempotencyService;

    private record TestResponse(String value) {
    }

    @BeforeEach
    void setUp() {
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        idempotencyService = new IdempotencyService(redisTemplate, repository, objectMapper, new SimpleMeterRegistry());
    }

    @Test
    void guardedRunsOperationWhenLockAcquiredAndNoExistingRecord() {
        when(valueOperations.setIfAbsent(any(), any(), any())).thenReturn(true);
        when(repository.findByMerchantIdAndIdempotencyKey(merchantId, "key-1")).thenReturn(Optional.empty());

        TestResponse result = idempotencyService.guarded(merchantId, "key-1", "fp", TestResponse.class,
                () -> new TestResponse("computed"));

        assertThat(result.value()).isEqualTo("computed");
        verify(redisTemplate).delete(anyString());
    }

    @Test
    void guardedThrowsWhenLockIsHeldByAnotherRequest() {
        when(valueOperations.setIfAbsent(any(), any(), any())).thenReturn(false);

        assertThatThrownBy(() -> idempotencyService.guarded(merchantId, "key-2", "fp", TestResponse.class,
                () -> new TestResponse("x")))
                .isInstanceOf(IdempotencyKeyInFlightException.class);
    }

    @Test
    void guardedReplaysTheStoredResponseWhenFingerprintMatches() {
        when(valueOperations.setIfAbsent(any(), any(), any())).thenReturn(true);
        String storedBody = objectMapper.writeValueAsString(new TestResponse("stored"));
        IdempotencyRecord record = IdempotencyRecord.of(merchantId, "key-3", "fp", 200, storedBody);
        when(repository.findByMerchantIdAndIdempotencyKey(merchantId, "key-3")).thenReturn(Optional.of(record));

        TestResponse result = idempotencyService.guarded(merchantId, "key-3", "fp", TestResponse.class,
                () -> new TestResponse("should-not-run"));

        assertThat(result.value()).isEqualTo("stored");
    }

    @Test
    void guardedThrowsWhenFingerprintDiffersFromTheStoredRecord() {
        when(valueOperations.setIfAbsent(any(), any(), any())).thenReturn(true);
        String storedBody = objectMapper.writeValueAsString(new TestResponse("stored"));
        IdempotencyRecord record = IdempotencyRecord.of(merchantId, "key-4", "original-fp", 200, storedBody);
        when(repository.findByMerchantIdAndIdempotencyKey(merchantId, "key-4")).thenReturn(Optional.of(record));

        assertThatThrownBy(() -> idempotencyService.guarded(merchantId, "key-4", "different-fp", TestResponse.class,
                () -> new TestResponse("x")))
                .isInstanceOf(IdempotencyKeyReusedException.class);
    }

    @Test
    void lockIsReleasedEvenWhenTheOperationThrows() {
        when(valueOperations.setIfAbsent(any(), any(), any())).thenReturn(true);
        when(repository.findByMerchantIdAndIdempotencyKey(any(), any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> idempotencyService.guarded(merchantId, "key-5", "fp", TestResponse.class, () -> {
            throw new RuntimeException("boom");
        })).isInstanceOf(RuntimeException.class);

        verify(redisTemplate).delete(anyString());
    }

    @Test
    void fingerprintIsDeterministicForTheSameOperationAndRequest() {
        TestResponse request = new TestResponse("same");

        String first = idempotencyService.fingerprint("op", request);
        String second = idempotencyService.fingerprint("op", request);

        assertThat(first).isEqualTo(second);
    }

    @Test
    void recordPersistsASerializedResponse() {
        idempotencyService.record(merchantId, "key-6", "fp", 200, new TestResponse("x"));

        verify(repository).save(argThat(record -> record.getMerchantId().equals(merchantId)
                && record.getIdempotencyKey().equals("key-6")
                && record.getResponseStatus() == 200));
    }
}
