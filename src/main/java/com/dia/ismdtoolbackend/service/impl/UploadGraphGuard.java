package com.dia.ismdtoolbackend.service.impl;

import com.dia.ismdtoolbackend.exception.OntologyCreationConflictException;
import com.dia.ismdtoolbackend.outbox.OutboxEntryRepository;
import com.dia.ismdtoolbackend.repository.JenaTDB2Repository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Upload writes its graph directly, not through the outbox. Run under the upload's vocabulary claim, once no
 * vocabulary row exists for the graph.
 */
@Slf4j
@Component
@RequiredArgsConstructor
class UploadGraphGuard {

    private final OutboxEntryRepository outboxRepository;
    private final JenaTDB2Repository jenaTDB2Repository;

    /**
     * Rejects while an outbox row for the graph is not applied (pending, or failed until an admin retries it): a
     * queued delete of an earlier vocabulary with this IRI would otherwise remove the uploaded graph later.
     */
    void requireNoUnappliedWrites(String graphName) {
        if (outboxRepository.existsEarlierUnappliedForGraph(graphName, Long.MAX_VALUE)) {
            throw new OntologyCreationConflictException(
                    "Předchozí operace se slovníkem ještě nebyla dokončena, zkuste nahrání později: " + graphName);
        }
    }

    /** The upload replaces a graph left without a vocabulary row; that is logged, never silent. */
    void warnIfGraphHoldsData(String graphName) {
        if (jenaTDB2Repository.graphHasData(graphName)) {
            log.warn("Graph {} holds data but has no vocabulary row; the upload replaces it", graphName);
        }
    }
}
