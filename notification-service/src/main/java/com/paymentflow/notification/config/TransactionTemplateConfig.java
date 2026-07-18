package com.paymentflow.notification.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Exposes a {@link TransactionTemplate} for {@code NotificationService} — the row
 * writes (dedup check, email log, webhook-delivery intent) must commit before the
 * webhook's first delivery attempt is made (an external HTTP call has no place inside
 * a DB transaction), mirroring the outbox pattern's write-then-act-after-commit shape
 * (D3/D46) rather than payment-service's/transaction-service's reasons for the same tool.
 */
@Configuration
public class TransactionTemplateConfig {

    @Bean
    public TransactionTemplate transactionTemplate(PlatformTransactionManager transactionManager) {
        return new TransactionTemplate(transactionManager);
    }
}
