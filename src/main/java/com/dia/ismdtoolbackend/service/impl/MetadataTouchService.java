package com.dia.ismdtoolbackend.service.impl;

import com.dia.ismdtoolbackend.entity.ConceptMetadataEntity;
import com.dia.ismdtoolbackend.entity.OntologyMetadataEntity;
import com.dia.ismdtoolbackend.repository.ConceptMetadataRepository;
import com.dia.ismdtoolbackend.repository.OntologyMetadataRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * Single source of truth for bumping {@code updatedAt} on concept and ontology metadata.
 *
 * <p>{@code @LastModifiedDate} fires only when a flush writes the row, and a flush happens only when
 * some mapped column is dirty. An RDF-only edit changes no mapped column, so
 * {@code repository.save(entity)} on it is a no-op: no UPDATE, no timestamp. These methods set
 * {@code updatedAt} explicitly, which both dirties the row and supplies the value — so the write
 * happens and the auditing listener's own value is irrelevant.
 *
 * <p><b>Lock ordering — concept, then ontology.</b> Touching a concept also touches its parent, so
 * an edit holds two row locks. Every caller acquires them concept-first, matching the outbox edit
 * path, which already locks the concept at the top of its critical section
 * ({@code ConceptMetadataRepository.findWithLockById}). Two concurrent edits of different concepts
 * in one ontology therefore queue on the shared parent in the same order instead of deadlocking.
 * The ontology row is re-read under {@code PESSIMISTIC_WRITE} rather than reused from the concept's
 * lazy association: the association gives an unlocked instance, and serializing the parent bump is
 * the whole point.
 *
 * <p>All methods are {@code MANDATORY} — a touch is part of the caller's write, never a transaction
 * of its own. Committing one separately would leave the timestamp moved while the edit rolled back.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MetadataTouchService {

    private final ConceptMetadataRepository conceptMetadataRepository;
    private final OntologyMetadataRepository ontologyMetadataRepository;

    /**
     * Bumps the concept's {@code updatedAt} and propagates to its parent ontology.
     *
     * <p>The entity must already be managed and, on the outbox path, already locked by the caller —
     * this method does not re-lock it, so the caller's concept-first ordering is preserved.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void touchConceptAndOntology(ConceptMetadataEntity concept) {
        if (concept == null) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        concept.setUpdatedAt(now);
        conceptMetadataRepository.save(concept);

        OntologyMetadataEntity parent = concept.getOntologyMetadata();
        if (parent == null) {
            log.warn("Concept {} has no parent ontology; skipping ontology touch", concept.getId());
            return;
        }
        touchOntologyById(parent.getId(), now);
    }

    /**
     * Bumps only the ontology's {@code updatedAt} — for ontology-level edits, and for bulk concept
     * writes (upload/import) that touch the parent once at the end rather than per concept.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void touchOntology(OntologyMetadataEntity ontology) {
        if (ontology == null) {
            return;
        }
        touchOntologyById(ontology.getId(), LocalDateTime.now());
    }

    private void touchOntologyById(Long ontologyId, LocalDateTime now) {
        ontologyMetadataRepository.findWithLockById(ontologyId).ifPresentOrElse(locked -> {
            locked.setUpdatedAt(now);
            ontologyMetadataRepository.save(locked);
        }, () -> log.warn("Ontology {} not found while touching updatedAt", ontologyId));
    }
}
