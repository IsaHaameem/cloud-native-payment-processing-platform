package com.paymentflow.merchant.mapper;

import com.paymentflow.merchant.domain.ApiKey;
import com.paymentflow.merchant.dto.ApiKeyIssuedResponse;
import com.paymentflow.merchant.dto.ApiKeyResponse;
import com.paymentflow.merchant.service.ApiKeyService.IssuedApiKey;
import org.springframework.stereotype.Component;

@Component
public class ApiKeyMapper {

    public ApiKeyResponse toResponse(ApiKey key) {
        return new ApiKeyResponse(
                key.getId(), key.getKeyType(), key.getMode(), key.getName(), key.getKeyPrefix(),
                key.getScopes(), key.getLastUsedAt(), key.getExpiresAt(), key.getRevokedAt(), key.getCreatedAt());
    }

    public ApiKeyIssuedResponse toIssuedResponse(IssuedApiKey issued) {
        ApiKey key = issued.apiKey();
        return new ApiKeyIssuedResponse(
                key.getId(), issued.rawValue(), key.getKeyPrefix(), key.getKeyType(), key.getMode(), key.getName(),
                key.getScopes(), key.getExpiresAt(), key.getCreatedAt());
    }
}
