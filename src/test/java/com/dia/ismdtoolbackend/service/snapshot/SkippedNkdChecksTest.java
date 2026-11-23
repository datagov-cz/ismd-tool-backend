package com.dia.ismdtoolbackend.service.snapshot;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SkippedNkdChecksTest {

    private static final String IRI = "https://example.org/pojem/a";

    @AfterEach
    void clear() {
        RequestContextHolder.resetRequestAttributes();
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    private static void inRequest() {
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
    }

    @Test
    void outsideARequestNothingIsCollected() {
        SkippedNkdChecks.record(List.of(IRI));
        assertThat(SkippedNkdChecks.current()).isEmpty();
    }

    @Test
    void withoutATransactionTheConceptCountsAtOnce_once() {
        inRequest();
        SkippedNkdChecks.record(List.of(IRI));
        SkippedNkdChecks.record(List.of(IRI));
        assertThat(SkippedNkdChecks.current()).containsExactly(IRI);
    }

    @Test
    void inATransactionTheConceptCountsOnlyAfterCommit() {
        inRequest();
        TransactionSynchronizationManager.initSynchronization();
        SkippedNkdChecks.record(List.of(IRI));
        assertThat(SkippedNkdChecks.current()).isEmpty();

        TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
        assertThat(SkippedNkdChecks.current()).containsExactly(IRI);
    }

    @Test
    void aRolledBackTransactionReportsNothing() {
        inRequest();
        TransactionSynchronizationManager.initSynchronization();
        SkippedNkdChecks.record(List.of(IRI));

        TransactionSynchronizationManager.getSynchronizations()
                .forEach(s -> s.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
        assertThat(SkippedNkdChecks.current()).isEmpty();
    }
}
