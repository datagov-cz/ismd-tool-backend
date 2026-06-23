package com.dia.ismdtoolbackend.service;

import com.dia.ismdtoolbackend.controller.dto.LinkSnapshotDto;

/**
 * Backs the user-driven NKD local-copy endpoints (UPDATE / REMOVE) on {@code ConceptController}. The
 * implementation owns the owner-keyed outbox flush — the outbox-agnostic {@link NkdSnapshotService}
 * only fills a change set; this service builds it, flushes one {@code enqueueUpsert} keyed on the
 * <em>owner</em> concept's IRI (C1), and assembles the response DTO. It is also the home of the
 * upstream-NKD-deletion cascade (an UPDATE that finds the concept gone removes the link + copy).
 */
public interface NkdSnapshotEndpointService {

    /**
     * Re-snapshots the NKD concept and re-materializes the local copy, then re-evaluates deviation and
     * returns the refreshed DTO. If NKD reports the concept gone, the link + copy are removed and a
     * removed-marker DTO ({@code status = CONCEPT_NOT_FOUND_IN_NKD}) is returned.
     *
     * @throws com.dia.ismdtoolbackend.exception.OntologyValidationException if the snapshot does not
     *         exist or does not belong to {@code conceptId} (HTTP 400)
     */
    LinkSnapshotDto updateSnapshot(Long conceptId, Long snapshotId);

    /**
     * Removes the link to the NKD concept and the local copy (refcount-gated), deleting the PG row.
     *
     * @throws com.dia.ismdtoolbackend.exception.OntologyValidationException if the snapshot does not
     *         exist or does not belong to {@code conceptId} (HTTP 400)
     */
    void removeSnapshot(Long conceptId, Long snapshotId);
}