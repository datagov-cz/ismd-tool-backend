package com.dia.ismdtoolbackend.enums;

/**
 * Where a concept or ontology stands relative to NKD, surfaced alongside published/draft. Derived from
 * {@code is_published} — not stored, so there is no column to keep in sync.
 *
 * <p><strong>The tag describes its own IRI, never its contents.</strong> An ontology is
 * {@link #WORKING_COPY} because the ontology's own graph IRI is in NKD — it says nothing about its
 * concepts, which carry their own tags. A working-copy ontology legitimately contains a mix of
 * working-copy and draft concepts (Phase C's sever flips one concept at a time), so a WORKING_COPY
 * ontology holding DRAFT concepts is a valid state, not a desync to reconcile.
 */
public enum ConceptSourceTag {

    /** A purely local concept; its IRI is not in NKD. */
    DRAFT,

    /**
     * A local working copy of a published NKD resource — the concept's own IRI is in NKD, so it is
     * deviation-tracked against its NKD twin at that IRI. Severing a working copy (accepting only some
     * upstream deviations) flips it back to {@link #DRAFT}.
     */
    WORKING_COPY
}
