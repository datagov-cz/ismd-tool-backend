package com.dia.ismdtoolbackend.outbox;

import com.dia.ismdtoolbackend.testsupport.pg.OutboxEntries;
import com.dia.ismdtoolbackend.testsupport.pg.OutboxFlowIT;
import com.dia.ismdtoolbackend.testsupport.pg.OutboxFlowTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Review #4 MEDIUM — the DONE-row retention prune ({@link OutboxRetentionScheduler}, the only caller
 * of {@link OutboxEntryRepository#deleteDoneBefore}). Verifies it removes only DONE rows older than
 * {@code outbox.done-retention} and leaves fresh DONE / PENDING / FAILED rows alone. Runs against
 * real Postgres (Testcontainers).
 */
@OutboxFlowIT
class OutboxRetentionSchedulerTest extends OutboxFlowTestBase {

    private static final String GRAPH = "https://slovnik.gov.cz/g";

    @Autowired
    private OutboxRetentionScheduler scheduler;
    @Autowired
    private OutboxEntryRepository repository;

    private Long seed(OutboxStatus status, Instant completedAt) {
        OutboxEntry e = OutboxEntries.pending(GRAPH, GRAPH + "/pojem/x", OutboxOperation.DELETE_GRAPH,
                repository.nextSeq());
        e.setStatus(status);
        e.setCreatedAt(Instant.now().minus(30, ChronoUnit.DAYS));
        e.setCompletedAt(completedAt);
        return repository.save(e).getId();
    }

    @Test
    void prunesOldDoneRows_keepsFreshAndNonDone() {
        outboxConfig.setDoneRetention(Duration.ofDays(7));
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
}
