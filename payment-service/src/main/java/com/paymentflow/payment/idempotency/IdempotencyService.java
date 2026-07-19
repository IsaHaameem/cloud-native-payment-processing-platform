package com.paymentflow.payment.idempotency;

import com.paymentflow.common.security.OpaqueTokenGenerator;
import com.paymentflow.payment.domain.IdempotencyRecord;
import com.paymentflow.payment.exception.IdempotencyKeyInFlightException;
import com.paymentflow.payment.exception.IdempotencyKeyReusedException;
import com.paymentflow.payment.repository.IdempotencyRecordRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import tools.jackson.databind.ObjectMapper;

/**
 * Idempotent-request guard (§5): {@code Idempotency-Key} → Redis lock (fast rejection
 * of an in-flight duplicate) → {@code idempotency_keys} table (durable replay record).
 *
 * <p>{@link #guarded} deliberately holds the Redis lock across the <em>entire</em>
 * caller-supplied operation, including its database commit — not just the method
 * body of a {@code @Transactional} method. A lock released inside a
 * {@code @Transactional} method's own {@code finally} block would release it
 * <em>before</em> the surrounding proxy commits, letting a concurrent duplicate
 * squeeze through the gap between "lock released" and "record durably visible". The
 * operation passed to {@code guarded} must therefore be a call into a
 * <em>different</em> Spring bean's transactional method (or use
 * {@code TransactionTemplate} directly) so its commit completes before this method's
 * {@code finally} runs.
 */
@Service
public class IdempotencyService {

    private static final Duration LOCK_TTL = Duration.ofSeconds(10);
    private static final String LOCK_KEY_PREFIX = "idempotency:lock:";

    private final StringRedisTemplate redisTemplate;
    private final IdempotencyRecordRepository idempotencyRecordRepository;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    public IdempotencyService(StringRedisTemplate redisTemplate,
                              IdempotencyRecordRepository idempotencyRecordRepository,
                              ObjectMapper objectMapper, MeterRegistry meterRegistry) {
        this.redisTemplate = redisTemplate;
        this.idempotencyRecordRepository = idempotencyRecordRepository;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
    }

    /** SHA-256 fingerprint of an operation name plus its request body, to detect key reuse with a different request. */
    public String fingerprint(String operation, Object request) {
        return OpaqueTokenGenerator.sha256Hex(operation + "|" + objectMapper.writeValueAsString(request));
    }

    /**
     * Runs {@code operation} under the idempotency guard for {@code idempotencyKey}:
     * acquires the Redis lock (or throws if another request holds it), replays a prior
     * matching response if one exists, otherwise runs {@code operation} and returns its
     * result. Always releases the lock, including on failure.
     */
    public <T> T guarded(UUID merchantId, String idempotencyKey, String fingerprint,
                        Class<T> responseType, Supplier<T> operation) {
        acquireLockOrThrow(merchantId, idempotencyKey);
        try {
            Optional<T> replay = findReplay(merchantId, idempotencyKey, fingerprint, responseType);
            if (replay.isPresent()) {
                return replay.get();
            }
            return operation.get();
        } finally {
            releaseLock(merchantId, idempotencyKey);
        }
    }

    /** Persists the response under this idempotency key. Call from within the same transaction as the operation it records. */
    public void record(UUID merchantId, String idempotencyKey, String fingerprint, int responseStatus, Object response) {
        String body = objectMapper.writeValueAsString(response);
        idempotencyRecordRepository.save(IdempotencyRecord.of(merchantId, idempotencyKey, fingerprint, responseStatus, body));
    }

    private <T> Optional<T> findReplay(UUID merchantId, String idempotencyKey, String fingerprint, Class<T> responseType) {
        return idempotencyRecordRepository.findByMerchantIdAndIdempotencyKey(merchantId, idempotencyKey)
                .map(record -> {
                    if (!record.getRequestFingerprint().equals(fingerprint)) {
                        meterRegistry.counter("idempotency_key_outcomes_total", "outcome", "reused_conflict").increment();
                        throw new IdempotencyKeyReusedException(idempotencyKey);
                    }
                    // A genuine replay: the caller retried the exact same request (network
                    // retry, at-least-once client) rather than issuing a new one — worth its
                    // own metric as a real signal of retry behavior actually happening.
                    meterRegistry.counter("idempotency_key_outcomes_total", "outcome", "replayed").increment();
                    return objectMapper.readValue(record.getResponseBody(), responseType);
                });
    }

    private void acquireLockOrThrow(UUID merchantId, String idempotencyKey) {
        Boolean acquired = redisTemplate.opsForValue()
                .setIfAbsent(lockKey(merchantId, idempotencyKey), "1", LOCK_TTL);
        if (!Boolean.TRUE.equals(acquired)) {
            meterRegistry.counter("idempotency_key_outcomes_total", "outcome", "in_flight_conflict").increment();
            throw new IdempotencyKeyInFlightException(idempotencyKey);
        }
    }

    private void releaseLock(UUID merchantId, String idempotencyKey) {
        redisTemplate.delete(lockKey(merchantId, idempotencyKey));
    }

    private static String lockKey(UUID merchantId, String idempotencyKey) {
        return LOCK_KEY_PREFIX + merchantId + ":" + idempotencyKey;
    }
}
