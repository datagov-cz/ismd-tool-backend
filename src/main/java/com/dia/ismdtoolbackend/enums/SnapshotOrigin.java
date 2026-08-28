package com.dia.ismdtoolbackend.enums;

/**
 * Why an {@code NkdConceptSnapshotEntity} exists — the relationship between the owning local concept
 * and the NKD concept it tracks. Both origins are a "local copy tracked against NKD", differing only in
 * who owns the IRI. Only {@link #LINK_TARGET} rows are currently written; {@link #WORKING_COPY} is a
 * reserved seam (no rows created/read, no backfill) for a future unification.
 */
public enum SnapshotOrigin {

    /**
     * A local working copy of a published NKD resource — the owning concept's <em>own</em> IRI is in NKD.
     * <strong>Reserved — not written yet.</strong> Working-copy deviation flows through the
     * {@code is_published} path, which is also what {@link ConceptSourceTag#WORKING_COPY} derives from.
     */
    WORKING_COPY,

    /**
     * The owning concept's IRI is <em>not</em> in NKD; it <em>links</em> (subClassOf / subPropertyOf /
     * exactMatch) to a published NKD concept owned by someone else. The snapshot <em>is</em> the local
     * copy; deviation is the stored snapshot vs live NKD at the external IRI.
     */
    LINK_TARGET
}
