package com.paymentflow.payment.merchant;

import com.paymentflow.payment.exception.MerchantNotOnboardedException;
import com.paymentflow.payment.exception.MerchantServiceUnavailableException;
import feign.FeignException;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** Resolves the calling (JWT-authenticated) user's merchant id via merchant-service. */
@Component
public class MerchantResolver {

    private final MerchantClient merchantClient;

    public MerchantResolver(MerchantClient merchantClient) {
        this.merchantClient = merchantClient;
    }

    public UUID resolveCallerMerchantId() {
        try {
            return merchantClient.getMine().id();
        } catch (FeignException.NotFound e) {
            throw new MerchantNotOnboardedException();
        } catch (FeignException e) {
            throw new MerchantServiceUnavailableException(e);
        }
    }
}
