package com.dia.ismdtoolbackend.outbox;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Supplies a {@link TransactionTemplate} for tests that need explicit transaction boundaries. */
@TestConfiguration
public class TransactionTemplateConfig {

    @Bean
    TransactionTemplate txTemplate(PlatformTransactionManager txManager) {
        return new TransactionTemplate(txManager);
    }
}
