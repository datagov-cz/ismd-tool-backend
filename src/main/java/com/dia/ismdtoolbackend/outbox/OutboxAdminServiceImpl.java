package com.dia.ismdtoolbackend.outbox;

import com.dia.ismdtoolbackend.outbox.dto.OutboxEntryDto;
import com.dia.ismdtoolbackend.outbox.dto.OutboxStatusDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * Admin operations over the outbox: status snapshot, FAILED-row listing, force-drain, and retry of a
 * FAILED row. Keeps the controller thin and owns the transaction boundary for the mutating retry.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class OutboxAdminServiceImpl implements OutboxAdminService {

    private final OutboxEntryRepository repository;
    private final OutboxRelay relay;

    @Override
    @Transactional(readOnly = true)
    public OutboxStatusDto status() {
        return new OutboxStatusDto(
                repository.countByStatus(OutboxStatus.PENDING),
                repository.countByStatus(OutboxStatus.FAILED),
                repository.countByStatus(OutboxStatus.DONE),
                repository.oldestPendingCreatedAt());
    }

    @Override
    @Transactional(readOnly = true)
    public List<OutboxEntryDto> failed() {
        return repository.findByStatusOrderBySeqAsc(OutboxStatus.FAILED).stream()
                .map(OutboxEntryDto::from)
                .toList();
    }

    /** Forces a drain pass on demand. Returns rows applied. (Relay opens its own REQUIRES_NEW tx.) */
    @Override
    public int drainNow() {
        return relay.drainOnce();
    }

    /**
     * Resets a FAILED row to PENDING (attempts → 0, error cleared) so the relay retries it — the
     * unwedge path for an aggregate blocked by a FAILED row. Only FAILED rows are retryable; returns
     * false if the id is unknown or not FAILED.
     */
    @Override
    @Transactional
    public boolean retry(Long id) {
        Optional<OutboxEntry> opt = repository.findById(id);
        if (opt.isEmpty()) {
            return false;
        }
        OutboxEntry row = opt.get();
        if (row.getStatus() != OutboxStatus.FAILED) {
            log.info("Outbox retry refused for row {} — status is {}, not FAILED", id, row.getStatus());
            return false;
        }
        row.setStatus(OutboxStatus.PENDING);
        row.setAttempts(0);
        row.setLastError(null);
        repository.save(row);
        log.info("Outbox row {} reset FAILED → PENDING for retry", id);
        return true;
    }
}
