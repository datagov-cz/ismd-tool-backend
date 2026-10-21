package com.dia.ismdtoolbackend.outbox;

import com.dia.ismdtoolbackend.testsupport.pg.OutboxFlowIT;
import com.dia.ismdtoolbackend.testsupport.pg.OutboxFlowTestBase;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.Statement;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T6 — the after-commit relay nudge ({@link OutboxRelayTrigger}) and the scheduled backstop
 * ({@link OutboxRelayScheduler}). Verifies the nudge drains a just-committed row, does NOT drain on
 * rollback, and that the scheduler drains a backlog. Runs against real Postgres +
 * an in-memory Jena dataset (same harness as the relay test).
 */
@OutboxFlowIT
class OutboxRelayTriggerTest extends OutboxFlowTestBase {

    private static final String GRAPH = "https://slovnik.gov.cz/g";
    private static final String A = GRAPH + "/pojem/a";
    private static final String PREF_LABEL = "http://www.w3.org/2004/02/skos/core#prefLabel";

    @Autowired
    private OutboxWriter writer;
    @Autowired
    private OutboxRelayTrigger trigger;
    @Autowired
    private OutboxRelayScheduler scheduler;
    @Autowired
    private OutboxEntryRepository repository;
    @Autowired
    private TransactionTemplate txTemplate;

    private static Statement triple(String literal) {
        Model m = ModelFactory.createDefaultModel();
        Resource s = m.createResource(A);
        Property p = m.createProperty(PREF_LABEL);
        return m.createStatement(s, p, literal);
    }

    private boolean graphHas(String literal) {
        return tdb2.dataset().getNamedModel(GRAPH).contains(triple(literal));
    }

    @Test
    void nudge_drainsRowAfterCommit() {
        txTemplate.executeWithoutResult(tx -> {
            writer.enqueueUpsert(GRAPH, A, Set.of(), Set.of(triple("Hello")));
            trigger.nudgeAfterCommit();
            // Inside the tx the nudge has only REGISTERED — nothing applied yet.
            assertThat(graphHas("Hello")).isFalse();
        });
        // After commit, the registered synchronization ran the drain synchronously.
        assertThat(graphHas("Hello")).isTrue();
        assertThat(repository.countByStatus(OutboxStatus.DONE)).isEqualTo(1);
    }

    @Test
    void nudge_doesNotDrainOnRollback() {
        assertThatThrownBy(() -> txTemplate.executeWithoutResult(tx -> {
            writer.enqueueUpsert(GRAPH, A, Set.of(), Set.of(triple("Rolled back")));
            trigger.nudgeAfterCommit();
            throw new IllegalStateException("boom after enqueue + nudge");
        })).isInstanceOf(IllegalStateException.class);

        // The tx rolled back: the row never committed, and afterCommit never fired.
        assertThat(repository.count()).isZero();
        assertThat(graphHas("Rolled back")).isFalse();
    }

    @Test
    void scheduler_drainsBacklog() {
        outboxConfig.setBatchSize(1); // force multiple passes so the backstop's drain-loop is exercised
        txTemplate.executeWithoutResult(tx -> {
            writer.enqueueUpsert(GRAPH, A, Set.of(), Set.of(triple("One")));
            writer.enqueueUpsert(GRAPH + "/pojem/b", GRAPH + "/pojem/b", Set.of(),
                    Set.of(triple("Two"))); // different aggregate so both are head-of-queue
        });

        scheduler.drainScheduled();

        assertThat(repository.countByStatus(OutboxStatus.DONE)).isEqualTo(2);
    }
}
