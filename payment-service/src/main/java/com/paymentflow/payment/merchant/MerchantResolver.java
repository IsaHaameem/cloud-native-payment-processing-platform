package com.paymentflow.payment.merchant;

import com.paymentflow.payment.exception.MerchantNotOnboardedException;
import com.paymentflow.payment.exception.MerchantServiceUnavailableException;
import feign.FeignException;
import org.springframework.stereotype.Component;

/** Resolves the calling (JWT-authenticated) user's merchant profile via merchant-service. */
@Component
public class MerchantResolver {

    private final MerchantClient merchantClient;

    public MerchantResolver(MerchantClient merchantClient) {
        this.merchantClient = merchantClient;
    }

    public MerchantSummary resolveCallerMerchant() {
        try {
            return merchantClient.getMine();
        } catch (FeignException.NotFound e) {
            throw new MerchantNotOnboardedException();
        } catch (FeignException e) {
            throw new MerchantServiceUnavailableException(e);
        }
    }
}
