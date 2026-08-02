package com.paymentflow.merchant.web;

import com.paymentflow.common.exception.ResourceNotFoundException;
import com.paymentflow.merchant.dto.MerchantOwnerLookupResponse;
import com.paymentflow.merchant.repository.MerchantRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Service-to-service only (M23.0, {@code /internal/v1} tier, D98) — the gateway's second
 * merchant-service dependency, added for the developer portal's session path (D183).
 * Never routed through the gateway (no route predicate matches {@code /internal/v1/**})
 * and permitted without a JWT in {@code SecurityConfig} for the same reason
 * {@link ApiKeyInternalController} is: its only legitimate caller has none to present.
 *
 * <p>Answers the one question the API-key path never has to ask. A key already knows its
 * merchant; a dashboard session knows only its user, and the gateway must resolve the
 * merchant before it can assert a context. This is the whole of M23.0's merchant-service
 * change — no existing endpoint moved, and no public contract was touched.
 *
 * <p>A user with no merchant surfaces as 404 here — the internal contract's own "not
 * found" semantics, exactly as a key that fails to verify does, and not to be confused
 * with what the gateway then returns to the browser (a 403: the session authenticated
 * fine, it simply has no merchant to act for yet).
 *
 * <p><b>Deliberately does not pin the merchant's API version</b>, unlike
 * {@code /internal/v1/api-keys/verify} (M21.5). A pin records the contract revision a
 * merchant first actually *called*; pinning them because a page of their dashboard
 * rendered would freeze a promise they never made, on a request that is not a public API
 * call at all.
 */
@RestController
@RequestMapping("/internal/v1/merchants")
public class MerchantInternalController {

    private final MerchantRepository merchantRepository;

    public MerchantInternalController(MerchantRepository merchantRepository) {
        this.merchantRepository = merchantRepository;
    }

    @GetMapping("/by-owner/{ownerUserId}")
    public MerchantOwnerLookupResponse byOwner(@PathVariable UUID ownerUserId) {
        return merchantRepository.findByOwnerUserId(ownerUserId)
                .map(merchant -> new MerchantOwnerLookupResponse(
                        merchant.getId(), merchant.getContactEmail(), merchant.getWebhookUrl()))
                .orElseThrow(() -> ResourceNotFoundException.of("Merchant", ownerUserId));
    }
}
