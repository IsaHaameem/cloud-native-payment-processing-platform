package com.paymentflow.merchant.service;

import com.paymentflow.common.exception.ResourceNotFoundException;
import com.paymentflow.merchant.domain.ApiKey;
import com.paymentflow.merchant.domain.ApiKeyType;
import com.paymentflow.merchant.domain.KeyMode;
import com.paymentflow.merchant.domain.Merchant;
import com.paymentflow.merchant.dto.MerchantOnboardResponse;
import com.paymentflow.merchant.dto.OnboardMerchantRequest;
import com.paymentflow.merchant.dto.UpdateMerchantRequest;
import com.paymentflow.merchant.dto.UpdateWebhookRequest;
import com.paymentflow.merchant.event.MerchantEventPublisher;
import com.paymentflow.merchant.exception.MerchantAlreadyExistsException;
import com.paymentflow.merchant.mapper.ApiKeyMapper;
import com.paymentflow.merchant.mapper.MerchantMapper;
import com.paymentflow.merchant.repository.MerchantRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MerchantServiceTest {

    @Mock
    private MerchantRepository merchantRepository;
    @Mock
    private ApiKeyService apiKeyService;
    @Spy
    private ApiKeyMapper apiKeyMapper = new ApiKeyMapper();
    @Spy
    private MerchantMapper merchantMapper = new MerchantMapper();
    @Mock
    private MerchantEventPublisher eventPublisher;
    // Deep stubs: meterRegistry.counter(...) must return a (mock) Counter, not null.
    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    private MeterRegistry meterRegistry;

    @InjectMocks
    private MerchantService merchantService;

    @Test
    void onboardRejectsWhenOwnerAlreadyHasAMerchant() {
        UUID ownerUserId = UUID.randomUUID();
        when(merchantRepository.existsByOwnerUserId(ownerUserId)).thenReturn(true);

        assertThatThrownBy(() -> merchantService.onboard(ownerUserId,
                new OnboardMerchantRequest("Acme", "billing@acme.test")))
                .isInstanceOf(MerchantAlreadyExistsException.class);

        verify(merchantRepository, never()).save(any());
    }

    @Test
    void onboardPersistsMerchantAndIssuesTheFourDefaultKeys() {
        UUID ownerUserId = UUID.randomUUID();
        when(merchantRepository.existsByOwnerUserId(ownerUserId)).thenReturn(false);
        when(merchantRepository.save(any(Merchant.class))).thenAnswer(inv -> inv.getArgument(0));
        when(apiKeyService.issueDefaultSet(any())).thenAnswer(inv -> List.of(
                issued(ApiKeyType.PUBLISHABLE, KeyMode.TEST), issued(ApiKeyType.SECRET, KeyMode.TEST),
                issued(ApiKeyType.PUBLISHABLE, KeyMode.LIVE), issued(ApiKeyType.SECRET, KeyMode.LIVE)));

        MerchantOnboardResponse response = merchantService.onboard(ownerUserId,
                new OnboardMerchantRequest("Acme", "billing@acme.test"));

        assertThat(response.merchant().businessName()).isEqualTo("Acme");
        assertThat(response.merchant().contactEmail()).isEqualTo("billing@acme.test");
        assertThat(response.apiKeys()).hasSize(4);
        verify(eventPublisher).publishMerchantOnboarded(any(Merchant.class));
    }

    @Test
    void getMineThrowsNotFoundWhenNoMerchantForOwner() {
        UUID ownerUserId = UUID.randomUUID();
        when(merchantRepository.findByOwnerUserId(ownerUserId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> merchantService.getMine(ownerUserId))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void updateMineChangesProfileFields() {
        UUID ownerUserId = UUID.randomUUID();
        Merchant merchant = Merchant.onboard(ownerUserId, "Old Name", "old@acme.test");
        when(merchantRepository.findByOwnerUserId(ownerUserId)).thenReturn(Optional.of(merchant));

        var response = merchantService.updateMine(ownerUserId,
                new UpdateMerchantRequest("New Name", "new@acme.test"));

        assertThat(response.businessName()).isEqualTo("New Name");
        assertThat(response.contactEmail()).isEqualTo("new@acme.test");
    }

    @Test
    void updateMyWebhookSetsTheUrl() {
        UUID ownerUserId = UUID.randomUUID();
        Merchant merchant = Merchant.onboard(ownerUserId, "Acme", "billing@acme.test");
        when(merchantRepository.findByOwnerUserId(ownerUserId)).thenReturn(Optional.of(merchant));

        var response = merchantService.updateMyWebhook(ownerUserId,
                new UpdateWebhookRequest("https://acme.test/webhooks/payments"));

        assertThat(response.webhookUrl()).isEqualTo("https://acme.test/webhooks/payments");
    }

    @Test
    void updateMyWebhookWithNullClearsIt() {
        UUID ownerUserId = UUID.randomUUID();
        Merchant merchant = Merchant.onboard(ownerUserId, "Acme", "billing@acme.test");
        merchant.updateWebhookUrl("https://acme.test/webhooks/payments");
        when(merchantRepository.findByOwnerUserId(ownerUserId)).thenReturn(Optional.of(merchant));

        var response = merchantService.updateMyWebhook(ownerUserId, new UpdateWebhookRequest(null));

        assertThat(response.webhookUrl()).isNull();
    }

    private static ApiKeyService.IssuedApiKey issued(ApiKeyType type, KeyMode mode) {
        String raw = type.prefix() + "_" + mode.value() + "_abcdefghijklmnopqrstuvwx";
        ApiKey key = ApiKey.issue(UUID.randomUUID(), type, mode, "Default key", raw.substring(0, 12),
                "hash-" + raw, List.of("*"), null);
        return new ApiKeyService.IssuedApiKey(raw, key);
    }
}
