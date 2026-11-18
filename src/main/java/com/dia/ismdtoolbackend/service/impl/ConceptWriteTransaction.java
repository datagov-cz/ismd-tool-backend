package com.dia.ismdtoolbackend.service.impl;

import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.function.Supplier;

/**
 * The bounded transaction of a write whose NKD answers were fetched before it: concept create, edit and
 * working-copy sync, vocabulary create with concepts, and a local-copy refresh. Its timeout bounds how long
 * row locks and the database connection are held, a wait for a lock included. Inside a caller's transaction
 * (diagram materialize) the work joins it.
 */
@Component
public class ConceptWriteTransaction {

    /** Above the Fuseki request timeout (30 s), so a slow graph read fails as itself first. */
    static final int TIMEOUT_SECONDS = 60;

    private final TransactionTemplate template;

    public ConceptWriteTransaction(PlatformTransactionManager transactionManager) {
        this.template = new TransactionTemplate(transactionManager);
        this.template.setTimeout(TIMEOUT_SECONDS);
    }

    <T> T run(Supplier<T> work) {
        return template.execute(status -> work.get());
    }
}
