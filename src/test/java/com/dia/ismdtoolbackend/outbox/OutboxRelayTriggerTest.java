package com.dia.ismdtoolbackend.outbox;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.Statement;
import org.apache.jena.sys.JenaSystem;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T6 — the after-commit relay nudge ({@link OutboxRelayTrigger}) and the scheduled backstop
 * ({@link OutboxRelayScheduler}). Verifies the nudge drains a just-committed row, does NOT drain on
 * rollback, and that the scheduler is gated by {@code outbox.enabled}. Runs against real Postgres +
 * an in-memory Jena dataset (same harness as the relay test).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("junit")
@Import({TransactionTemplateConfig.class, OutboxRelayTriggerTest.TriggerTestConfig.class})
@EntityScan(basePackageClasses = OutboxEntry.class)
@EnableJpaRepositories(basePackageClasses = OutboxEntryRepository.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class OutboxRelayTriggerTest extends PostgresIntegrationTestBase {

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
    private OutboxConfig config;
    @Autowired
    private OutboxEntryRepository repository;
    @Autowired
    private TransactionTemplate txTemplate;
    @Autowired
    private InMemoryTdb2 tdb2;

    @BeforeAll
    static void initJena() {
        JenaSystem.init();
    }

    @BeforeEach
    void reset() {
        tdb2.reset();
        config.setEnabled(false);
        config.setBatchSize(100);
        config.setMaxAttempts(10);
    }

    @AfterEach
    void cleanUp() {
        txTemplate.executeWithoutResult(tx -> repository.deleteAll());
    }

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
    void scheduler_skipsWhenDisabled() {
        config.setEnabled(false);
        // Seed a committed PENDING row directly (no nudge).
        txTemplate.executeWithoutResult(tx ->
                writer.enqueueUpsert(GRAPH, A, Set.of(), Set.of(triple("Pending"))));

        scheduler.drainScheduled();

        assertThat(graphHas("Pending")).isFalse(); // disabled → not drained
        assertThat(repository.countByStatus(OutboxStatus.PENDING)).isEqualTo(1);
    }

    @Test
    void scheduler_drainsBacklogWhenEnabled() {
        config.setEnabled(true);
        config.setBatchSize(1); // force multiple passes so the backstop's drain-loop is exercised
        txTemplate.executeWithoutResult(tx -> {
            writer.enqueueUpsert(GRAPH, A, Set.of(), Set.of(triple("One")));
            writer.enqueueUpsert(GRAPH + "/pojem/b", GRAPH + "/pojem/b", Set.of(),
                    Set.of(triple("Two"))); // different aggregate so both are head-of-queue
        });

        scheduler.drainScheduled();

        assertThat(repository.countByStatus(OutboxStatus.DONE)).isEqualTo(2);
    }

    static class TriggerTestConfig {
        @Bean
        OutboxConfig outboxConfig() {
            return new OutboxConfig();
        }

        @Bean
        InMemoryTdb2 inMemoryTdb2() {
            return new InMemoryTdb2();
        }

        @Bean
        OutboxWriter outboxWriter(OutboxEntryRepository repo) {
            return new OutboxWriter(repo);
        }

        @Bean
        OutboxRelay outboxRelay(OutboxEntryRepository repo, InMemoryTdb2 tdb2, OutboxConfig config) {
            return new OutboxRelay(repo, tdb2, config);
        }

        @Bean
        OutboxRelayTrigger outboxRelayTrigger(OutboxRelay relay) {
            return new OutboxRelayTrigger(relay);
        }

        @Bean
        OutboxRelayScheduler outboxRelayScheduler(OutboxConfig config, OutboxRelay relay) {
            return new OutboxRelayScheduler(config, relay);
        }
    }
}
