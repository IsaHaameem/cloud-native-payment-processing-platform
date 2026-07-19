package com.paymentflow.merchant.service;

import com.paymentflow.common.exception.ResourceNotFoundException;
import com.paymentflow.merchant.domain.Merchant;
import com.paymentflow.merchant.dto.MerchantOnboardResponse;
import com.paymentflow.merchant.dto.OnboardMerchantRequest;
import com.paymentflow.merchant.dto.UpdateMerchantRequest;
import com.paymentflow.merchant.dto.UpdateWebhookRequest;
import com.paymentflow.merchant.exception.MerchantAlreadyExistsException;
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
    private MerchantMapper merchantMapper = new MerchantMapper();
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
    void onboardPersistsMerchantAndIssuesAnApiKey() {
        UUID ownerUserId = UUID.randomUUID();
        when(merchantRepository.existsByOwnerUserId(ownerUserId)).thenReturn(false);
        when(merchantRepository.save(any(Merchant.class))).thenAnswer(inv -> inv.getArgument(0));
        when(apiKeyService.issue(any())).thenReturn(new ApiKeyService.IssuedApiKey("pf_raw-key", "pf_raw-key12"));

        MerchantOnboardResponse response = merchantService.onboard(ownerUserId,
                new OnboardMerchantRequest("Acme", "billing@acme.test"));

        assertThat(response.merchant().businessName()).isEqualTo("Acme");
        assertThat(response.merchant().contactEmail()).isEqualTo("billing@acme.test");
        assertThat(response.apiKey().apiKey()).isEqualTo("pf_raw-key");
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

    @Test
    void rotateMyApiKeyDelegatesToApiKeyServiceForTheOwnedMerchant() {
        UUID ownerUserId = UUID.randomUUID();
        Merchant merchant = Merchant.onboard(ownerUserId, "Acme", "billing@acme.test");
        when(merchantRepository.findByOwnerUserId(ownerUserId)).thenReturn(Optional.of(merchant));
        when(apiKeyService.rotate(any())).thenReturn(new ApiKeyService.IssuedApiKey("pf_new-key", "pf_new-key12"));

        var response = merchantService.rotateMyApiKey(ownerUserId);

        assertThat(response.apiKey()).isEqualTo("pf_new-key");
        verify(apiKeyService).rotate(merchant.getId());
    }
}
