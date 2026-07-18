package com.paymentflow.payment.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Exposes a {@link TransactionTemplate} for the payment mutation methods, which need
 * precise control over exactly when the transaction commits relative to the
 * idempotency Redis lock (see {@link com.paymentflow.payment.idempotency.IdempotencyService}) —
 * finer-grained than declarative {@code @Transactional} allows without a cross-bean
 * self-invocation split.
 */
@Configuration
public class TransactionTemplateConfig {

    @Bean
    public TransactionTemplate transactionTemplate(PlatformTransactionManager transactionManager) {
        return new TransactionTemplate(transactionManager);
    }
}
