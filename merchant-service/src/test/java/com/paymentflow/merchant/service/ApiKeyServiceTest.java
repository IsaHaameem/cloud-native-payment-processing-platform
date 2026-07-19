package com.paymentflow.merchant.service;

import com.paymentflow.merchant.domain.ApiKey;
import com.paymentflow.merchant.repository.ApiKeyRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ApiKeyServiceTest {

    @Mock
    private ApiKeyRepository apiKeyRepository;
    // Deep stubs: meterRegistry.counter(...) must return a (mock) Counter, not null.
    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    private MeterRegistry meterRegistry;

    @InjectMocks
    private ApiKeyService apiKeyService;

    @Test
    void issueGeneratesPrefixedKeyAndPersistsOnlyItsHash() {
        UUID merchantId = UUID.randomUUID();
        when(apiKeyRepository.save(any(ApiKey.class))).thenAnswer(inv -> inv.getArgument(0));

        ApiKeyService.IssuedApiKey issued = apiKeyService.issue(merchantId);

        assertThat(issued.rawValue()).startsWith("pf_");
        assertThat(issued.prefix()).isEqualTo(issued.rawValue().substring(0, 12));

        ArgumentCaptor<ApiKey> captor = ArgumentCaptor.forClass(ApiKey.class);
        verify(apiKeyRepository).save(captor.capture());
        ApiKey saved = captor.getValue();
        assertThat(saved.getMerchantId()).isEqualTo(merchantId);
        assertThat(saved.getKeyPrefix()).isEqualTo(issued.prefix());
        assertThat(saved.getKeyHash()).isNotEqualTo(issued.rawValue());
        assertThat(saved.isActive()).isTrue();
    }

    @Test
    void rotateRevokesExistingActiveKeyAndIssuesANewOne() {
        UUID merchantId = UUID.randomUUID();
        ApiKey existing = ApiKey.issue(merchantId, "pf_oldprefix", "old-hash");
        when(apiKeyRepository.findByMerchantIdAndRevokedAtIsNull(merchantId)).thenReturn(Optional.of(existing));
        when(apiKeyRepository.save(any(ApiKey.class))).thenAnswer(inv -> inv.getArgument(0));

        ApiKeyService.IssuedApiKey rotated = apiKeyService.rotate(merchantId);

        assertThat(existing.isActive()).isFalse();
        assertThat(rotated.rawValue()).startsWith("pf_");
    }

    @Test
    void rotateWithNoExistingKeyJustIssuesOne() {
        UUID merchantId = UUID.randomUUID();
        when(apiKeyRepository.findByMerchantIdAndRevokedAtIsNull(merchantId)).thenReturn(Optional.empty());
        when(apiKeyRepository.save(any(ApiKey.class))).thenAnswer(inv -> inv.getArgument(0));

        ApiKeyService.IssuedApiKey issued = apiKeyService.rotate(merchantId);

        assertThat(issued.rawValue()).startsWith("pf_");
    }
}
