package com.paymentflow.merchant.mapper;

import com.paymentflow.merchant.domain.Merchant;
import com.paymentflow.merchant.dto.MerchantResponse;
import org.springframework.stereotype.Component;

@Component
public class MerchantMapper {

    public MerchantResponse toResponse(Merchant merchant) {
        return new MerchantResponse(
                merchant.getId(),
                merchant.getBusinessName(),
                merchant.getContactEmail(),
                merchant.getCreatedAt(),
                merchant.getUpdatedAt());
    }
}
