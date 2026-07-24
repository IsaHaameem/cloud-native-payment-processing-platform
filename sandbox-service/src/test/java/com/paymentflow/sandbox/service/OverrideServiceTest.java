package com.paymentflow.sandbox.service;

import com.paymentflow.common.exception.BadRequestException;
import com.paymentflow.common.exception.ForbiddenException;
import com.paymentflow.sandbox.domain.SimulationOverride;
import com.paymentflow.sandbox.domain.SimulationScenario;
import com.paymentflow.sandbox.repository.SimulationOverrideRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OverrideServiceTest {

    @Mock
    private SimulationOverrideRepository repository;

    private OverrideService overrideService;

    private final UUID merchantId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        overrideService = new OverrideService(repository);
        lenient().when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void liveModeIsRejectedBeforeAnyValidationOrPersistence() {
        assertThatThrownBy(() -> overrideService.create(merchantId, "live", SimulationScenario.FORCE_DECLINE,
                "card_declined", null, null, 5, null))
                .isInstanceOf(ForbiddenException.class);

        verify(repository, never()).revokeActive(any(), any(), any());
        verify(repository, never()).save(any());
    }

    @Test
    void forceDeclineWithoutADeclineCodeIsRejected() {
        assertThatThrownBy(() -> overrideService.create(merchantId, "test", SimulationScenario.FORCE_DECLINE,
                null, null, null, 5, null))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void forceErrorWithoutAnErrorCodeIsRejected() {
        assertThatThrownBy(() -> overrideService.create(merchantId, "test", SimulationScenario.FORCE_ERROR,
                null, null, null, 5, null))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void injectLatencyWithoutALatencyValueIsRejected() {
        assertThatThrownBy(() -> overrideService.create(merchantId, "test", SimulationScenario.INJECT_LATENCY,
                null, null, null, 5, null))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void latencyAboveThePlatformCeilingIsRejected() {
        assertThatThrownBy(() -> overrideService.create(merchantId, "test", SimulationScenario.INJECT_LATENCY,
                null, null, 10_001, 5, null))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void neitherRemainingCountNorDurationIsRejected() {
        assertThatThrownBy(() -> overrideService.create(merchantId, "test", SimulationScenario.FORCE_TIMEOUT,
                null, null, null, null, null))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void webhookScenariosNeedNoDeclineErrorOrLatencyField() {
        SimulationOverride created = overrideService.create(merchantId, "test",
                SimulationScenario.DUPLICATE_WEBHOOKS, null, null, null, 5, null);

        assertThat(created.getScenario()).isEqualTo(SimulationScenario.DUPLICATE_WEBHOOKS);
    }

    @Test
    void creatingAnOverrideRevokesAnyExistingActiveOneFirst() {
        overrideService.create(merchantId, "test", SimulationScenario.FORCE_RATE_LIMIT, null, null, null, 3, null);

        verify(repository).revokeActive(eq(merchantId), eq("test"), any(Instant.class));
    }

    @Test
    void durationSecondsBecomesAConcreteExpiryInstant() {
        Instant before = Instant.now();

        ArgumentCaptor<SimulationOverride> captor = ArgumentCaptor.forClass(SimulationOverride.class);
        overrideService.create(merchantId, "test", SimulationScenario.FORCE_TIMEOUT, null, null, null, null, 60);
        verify(repository).save(captor.capture());

        Instant expiresAt = captor.getValue().getExpiresAt();
        assertThat(expiresAt).isAfterOrEqualTo(before.plusSeconds(60));
    }

    @Test
    void consumeDelegatesToTheAtomicDecrement() {
        UUID overrideId = UUID.randomUUID();
        overrideService.consume(overrideId);

        verify(repository).decrementRemainingCount(overrideId);
    }

    @Test
    void findActiveFiltersOutAnAlreadyInactiveRow() throws Exception {
        SimulationOverride exhausted = SimulationOverride.create(merchantId, "test",
                SimulationScenario.FORCE_DECLINE, "card_declined", null, null, 0, null);
        when(repository.findByMerchantIdAndModeAndRevokedAtIsNull(merchantId, "test"))
                .thenReturn(Optional.of(exhausted));

        Optional<SimulationOverride> active = overrideService.findActive(merchantId, "test");

        assertThat(active).isEmpty();
    }
}
