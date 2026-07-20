package com.dia.ismdtoolbackend.outbox;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Review #4 MEDIUM — the DONE-row retention prune ({@link OutboxRetentionScheduler}, the only caller
 * of {@link OutboxEntryRepository#deleteDoneBefore}). Verifies it removes only DONE rows older than
 * {@code outbox.done-retention}, leaves fresh DONE / PENDING / FAILED rows alone, and is gated by
 * {@code outbox.enabled}. Runs against real Postgres (Testcontainers).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("junit")
@Import({TransactionTemplateConfig.class, OutboxRetentionSchedulerTest.RetentionTestConfig.class})
@EntityScan(basePackageClasses = OutboxEntry.class)
@EnableJpaRepositories(basePackageClasses = OutboxEntryRepository.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class OutboxRetentionSchedulerTest extends PostgresIntegrationTestBase {

    private static final String GRAPH = "https://slovnik.gov.cz/g";

    @Autowired
    private OutboxRetentionScheduler scheduler;
    @Autowired
    private OutboxConfig config;
    @Autowired
    private OutboxEntryRepository repository;
    @Autowired
    private TransactionTemplate txTemplate;

    @BeforeEach
    void reset() {
        config.setEnabled(true);
        config.setDoneRetention(Duration.ofDays(7));
    }

    @AfterEach
    void cleanUp() {
        txTemplate.executeWithoutResult(tx -> repository.deleteAll());
    }

    private Long seed(OutboxStatus status, Instant completedAt) {
        return txTemplate.execute(tx -> {
            OutboxEntry e = new OutboxEntry();
            e.setGraphName(GRAPH);
            e.setAggregateIri(GRAPH + "/pojem/x");
            e.setOperation(OutboxOperation.DELETE_GRAPH);
            e.setStatus(status);
            e.setCreatedAt(Instant.now().minus(30, ChronoUnit.DAYS));
            e.setCompletedAt(completedAt);
            e.setSeq(repository.nextSeq());
            return repository.save(e).getId();
        });
    }

    @Test
    void prunesOldDoneRows_keepsFreshAndNonDone() {
        Long oldDone = seed(OutboxStatus.DONE, Instant.now().minus(10, ChronoUnit.DAYS)); // past retention
        Long freshDone = seed(OutboxStatus.DONE, Instant.now().minus(1, ChronoUnit.DAYS)); // within retention
        Long pending = seed(OutboxStatus.PENDING, null);
        Long failed = seed(OutboxStatus.FAILED, null);

        scheduler.pruneDone();

        assertThat(repository.findById(oldDone)).isEmpty();        // pruned
        assertThat(repository.findById(freshDone)).isPresent();    // within window, kept
        assertThat(repository.findById(pending)).isPresent();      // never pruned
        assertThat(repository.findById(failed)).isPresent();       // never pruned
    }

    @Test
    void skipsWhenDisabled() {
        config.setEnabled(false);
        Long oldDone = seed(OutboxStatus.DONE, Instant.now().minus(10, ChronoUnit.DAYS));

        scheduler.pruneDone();

        assertThat(repository.findById(oldDone)).isPresent(); // disabled → nothing pruned
    }

    static class RetentionTestConfig {
        @Bean
        OutboxConfig outboxConfig() {
            return new OutboxConfig();
        }

        @Bean
        OutboxRetentionScheduler outboxRetentionScheduler(OutboxConfig config, OutboxEntryRepository repo) {
            return new OutboxRetentionScheduler(config, repo);
        }
    }
}
