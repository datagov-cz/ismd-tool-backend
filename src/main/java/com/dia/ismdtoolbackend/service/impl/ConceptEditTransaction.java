package com.dia.ismdtoolbackend.service.impl;

import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.function.Supplier;

/**
 * The transaction of a concept edit or create. Its timeout bounds how long the row lock and the database
 * connection are held, a wait for the lock included. Inside a caller's transaction (diagram materialize) the
 * work joins it.
 */
@Component
public class ConceptEditTransaction {

    /** Above the Fuseki request timeout (30 s), so a slow graph read fails as itself first. */
    static final int TIMEOUT_SECONDS = 60;

    private final TransactionTemplate template;

    public ConceptEditTransaction(PlatformTransactionManager transactionManager) {
        this.template = new TransactionTemplate(transactionManager);
        this.template.setTimeout(TIMEOUT_SECONDS);
    }

    <T> T run(Supplier<T> work) {
        return template.execute(status -> work.get());
    }
}
