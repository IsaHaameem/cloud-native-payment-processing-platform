package com.paymentflow.merchant.dto;

import java.util.List;

/** M15: onboarding now issues all four keys at once (pk/sk × test/live) — §3.1 step 2. */
public record MerchantOnboardResponse(
        MerchantResponse merchant,
        List<ApiKeyIssuedResponse> apiKeys) {
}
