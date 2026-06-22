package com.dia.ismdtoolbackend.enums;

/**
 * Why an {@code NkdConceptSnapshotEntity} exists — the relationship between the owning local concept
 * and the NKD concept it tracks.
 *
 * <p>Both origins are a "local copy tracked against NKD"; they differ only in <em>who owns the IRI</em>.
 * The conceptual unification is real, but this round writes <strong>{@link #LINK_TARGET} rows only</strong>
 * — see the {@code m7} decision in {@code .planning/nkd-local-copy-snapshot-PLAN.md}. {@link #SELF_PUBLISHED}
 * is a reserved seam: no rows are created or read for it yet, and existing {@code is_published=true}
 * concepts are NOT backfilled. Keeping the value documents the intended future unification without
 * shipping a half-wired discriminator.
 */
public enum SnapshotOrigin {

    /**
     * The owning concept's own IRI is in NKD (self-published). Deviation is the local concept's own
     * RDF vs live NKD at the same IRI. <strong>Reserved — not written this round.</strong> Self-published
     * deviation continues to flow through the existing {@code is_published} path.
     */
    SELF_PUBLISHED,

    /**
     * The owning concept's IRI is <em>not</em> in NKD; it <em>links</em> (subClassOf / subPropertyOf /
     * exactMatch) to a published NKD concept owned by someone else. The snapshot <em>is</em> the local
     * copy; deviation is the stored snapshot vs live NKD at the external IRI.
     */
    LINK_TARGET
}