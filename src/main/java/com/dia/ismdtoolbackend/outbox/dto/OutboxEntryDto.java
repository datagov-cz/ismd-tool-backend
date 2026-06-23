package com.dia.ismdtoolbackend.outbox.dto;

import com.dia.ismdtoolbackend.outbox.OutboxEntry;

import java.time.Instant;

/**
 * Admin view of one outbox row — metadata + diagnostics, deliberately WITHOUT the triple payloads
 * ({@code deleteTriples}/{@code insertTriples}/{@code targetIris}), which can be large and aren't
 * needed to triage a stuck/failed row. Used to list FAILED rows.
 */
public record OutboxEntryDto(
        Long id,
        String operation,
        String aggregateIri,
        String graphName,
        String status,
        int attempts,
        String lastError,
        Instant createdAt,
        Instant claimedAt,
        long seq
) {
    public static OutboxEntryDto from(OutboxEntry e) {
        return new OutboxEntryDto(
                e.getId(),
                e.getOperation() == null ? null : e.getOperation().name(),
                e.getAggregateIri(),
                e.getGraphName(),
                e.getStatus() == null ? null : e.getStatus().name(),
                e.getAttempts(),
                e.getLastError(),
                e.getCreatedAt(),
                e.getClaimedAt(),
                e.getSeq());
    }
}
