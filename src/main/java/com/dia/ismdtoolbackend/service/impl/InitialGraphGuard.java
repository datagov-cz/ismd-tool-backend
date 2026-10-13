package com.dia.ismdtoolbackend.service.impl;

import com.dia.ismdtoolbackend.exception.OntologyCreationConflictException;
import com.dia.ismdtoolbackend.models.*;
import com.dia.ismdtoolbackend.outbox.OutboxEntryRepository;
import com.dia.ismdtoolbackend.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Blocks direct graph writes while a vocabulary's initial write is still queued. */
@Component
@RequiredArgsConstructor
class InitialGraphGuard {

    private final OutboxEntryRepository outboxRepository;

    // Direct PUT/rename/delete cannot race a retry of the initial PUT. Seeing DONE means the relay's
    // transaction and its row lock completed.
    void requireInitialGraphApplied(String graph) {
        if (outboxRepository.existsEarlierUnappliedCreateGraph(graph, Long.MAX_VALUE)) {
            throw new OntologyCreationConflictException("Počáteční zápis slovníku ještě nebyl dokončen: " + graph);
        }
    }
}
