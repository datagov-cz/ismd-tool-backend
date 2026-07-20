package com.dia.ismdtoolbackend.outbox;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Prunes {@code DONE} outbox rows older than {@code outbox.done-retention}, on
 * {@code outbox.prune-cron}, gated by {@code outbox.enabled}.
 *
 * <p>{@code DONE} rows are kept as a short write-path audit trail, but without this prune they would
 * accumulate one-per-write forever, bloating the table and slowing the relay's claim/gate scans.
 * This is the only caller of {@link OutboxEntryRepository#deleteDoneBefore} — the retention config
 * is otherwise inert.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class OutboxRetentionScheduler {

    private final OutboxConfig config;
    private final OutboxEntryRepository repository;

    @Scheduled(cron = "${outbox.prune-cron:0 30 3 * * *}")
    @Transactional
    public void pruneDone() {
        if (!config.isEnabled()) {
            log.debug("Outbox DONE-row prune skipped — outbox.enabled=false");
            return;
        }
        Instant cutoff = Instant.now().minus(config.getDoneRetention());
        int removed = repository.deleteDoneBefore(cutoff);
        if (removed > 0) {
            log.info("Outbox retention prune removed {} DONE row(s) completed before {}", removed, cutoff);
        }
    }
}
