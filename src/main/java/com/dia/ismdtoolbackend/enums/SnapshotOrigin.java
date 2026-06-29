package com.dia.ismdtoolbackend.enums;

/**
 * Why an {@code NkdConceptSnapshotEntity} exists — the relationship between the owning local concept
 * and the NKD concept it tracks. Both origins are a "local copy tracked against NKD", differing only in
 * who owns the IRI. Only {@link #LINK_TARGET} rows are currently written; {@link #SELF_PUBLISHED} is a
 * reserved seam (no rows created/read, no backfill) for a future unification.
 */
public enum SnapshotOrigin {

    /**
     * The owning concept's own IRI is in NKD (self-published). <strong>Reserved — not written yet.</strong>
     * Self-published deviation continues to flow through the existing {@code is_published} path.
     */
    SELF_PUBLISHED,

    /**
     * The owning concept's IRI is <em>not</em> in NKD; it <em>links</em> (subClassOf / subPropertyOf /
     * exactMatch) to a published NKD concept owned by someone else. The snapshot <em>is</em> the local
     * copy; deviation is the stored snapshot vs live NKD at the external IRI.
     */
    LINK_TARGET
}