package com.dia.ismdtoolbackend.testsupport.pg;

import com.dia.ismdtoolbackend.outbox.OutboxEntry;
import com.dia.ismdtoolbackend.outbox.OutboxOperation;
import com.dia.ismdtoolbackend.outbox.OutboxStatus;

import java.time.Instant;

/** Unsaved {@link OutboxEntry} rows for tests; the caller adds the payload and saves. */
public final class OutboxEntries {

    private OutboxEntries() {
    }

    /** A {@code PENDING} row created now, with no payload. */
    public static OutboxEntry pending(String graphName, String aggregateIri, OutboxOperation operation, long seq) {
        OutboxEntry e = new OutboxEntry();
        e.setGraphName(graphName);
        e.setAggregateIri(aggregateIri);
        e.setOperation(operation);
        e.setStatus(OutboxStatus.PENDING);
        e.setCreatedAt(Instant.now());
        e.setSeq(seq);
        return e;
    }
}
