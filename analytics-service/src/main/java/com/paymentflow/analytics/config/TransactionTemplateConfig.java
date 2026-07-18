package com.paymentflow.analytics.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Exposes a {@link TransactionTemplate} for {@code AnalyticsService}, which needs to
 * retry its *entire* event-processing transaction from scratch on an optimistic-lock
 * or aggregate-row-creation race — mirrors transaction-service's identical M6 pattern.
 */
@Configuration
public class TransactionTemplateConfig {

    @Bean
    public TransactionTemplate transactionTemplate(PlatformTransactionManager transactionManager) {
        return new TransactionTemplate(transactionManager);
    }
}
