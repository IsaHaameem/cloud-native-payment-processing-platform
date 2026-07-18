package com.paymentflow.merchant.dto;

public record MerchantOnboardResponse(
        MerchantResponse merchant,
        ApiKeyIssuedResponse apiKey) {
}
