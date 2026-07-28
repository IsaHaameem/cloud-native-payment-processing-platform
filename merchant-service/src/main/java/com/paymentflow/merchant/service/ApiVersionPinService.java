package com.paymentflow.merchant.service;

import com.paymentflow.common.dto.version.ApiVersions;
import com.paymentflow.merchant.domain.Merchant;
import com.paymentflow.merchant.repository.MerchantRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Pins a merchant to the current API revision on their first authenticated public-API call
 * (M21.5, §4.10 — "pinned per merchant at first call").
 *
 * <p><b>Why the pin is written here rather than at key issuance.</b> Issuing a key is not
 * using the API: a merchant can create an account, generate a key, and integrate three
 * months later, by which time the revision they were pinned to at signup is not the one they
 * developed against. Pinning at first *call* pins them to the contract they actually saw.
 *
 * <p><b>Why a verification endpoint is allowed to write.</b> It reads oddly — verify is
 * conceptually a read — and it is worth being explicit that the write happens exactly once
 * per merchant, ever: {@link Merchant#pinApiVersionIfUnset} refuses to move an existing pin,
 * so every subsequent call short-circuits before touching the database. The alternative,
 * a separate "first call" hook somewhere in the gateway, would need its own storage and its
 * own idempotency and would still be writing on the authentication path.
 *
 * <p><b>REQUIRES_NEW, and why it swallows failures.</b> The pin is bookkeeping; the request
 * it rides on is the merchant's actual traffic. If the update fails — a lock timeout, a
 * concurrent first call from two requests at once — the request must still succeed, and the
 * next call simply tries again. Letting a pin failure fail an authentication would turn a
 * cosmetic problem into an outage.
 */
@Service
public class ApiVersionPinService {

    private static final Logger log = LoggerFactory.getLogger(ApiVersionPinService.class);

    private final MerchantRepository merchantRepository;

    public ApiVersionPinService(MerchantRepository merchantRepository) {
        this.merchantRepository = merchantRepository;
    }

    /**
     * Pins {@code merchant} to {@link ApiVersions#CURRENT} if it has no pin yet.
     *
     * @return the effective pin — the existing one, the newly written one, or the current
     *         revision if the write failed. Never null, so the caller can put it straight on
     *         the verification response.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public String pinIfUnset(Merchant merchant) {
        if (merchant.getPinnedApiVersion() != null) {
            return merchant.getPinnedApiVersion();
        }
        String current = ApiVersions.CURRENT.toString();
        try {
            // Re-read inside this transaction: the caller's instance may have been loaded
            // before a concurrent first call pinned it, and pinning twice would be a lost
            // update on a value that is supposed to be written once.
            Merchant managed = merchantRepository.findById(merchant.getId()).orElse(null);
            if (managed == null) {
                return current;
            }
            if (managed.pinApiVersionIfUnset(current)) {
                merchantRepository.save(managed);
                log.info("Pinned merchant {} to API version {} on first call", managed.getId(), current);
            }
            return managed.getPinnedApiVersion();
        } catch (RuntimeException e) {
            // Deliberately not rethrown — see the class javadoc.
            log.warn("Could not pin merchant {} to API version {}; serving {} and will retry on the next call",
                    merchantId(merchant), current, current, e);
            return current;
        }
    }

    private static UUID merchantId(Merchant merchant) {
        return merchant.getId();
    }
}
