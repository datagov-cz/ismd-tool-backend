package com.dia.ismdtoolbackend.reconciler;

import lombok.Getter;

/**
 * The kinds of PG↔TDB2 drift the consistency reconciler can detect.
 *
 * <p>Only {@link #RDF_ORPHAN} (and, gated, {@code GRAPH_ORPHAN}) is ever auto-repairable —
 * deleting leaked owned RDF that has no Postgres backing is the one direction that cannot
 * lose authoritative data. Every other category is <b>report-only</b>: Postgres is the
 * source of truth and never reprojects full RDF (it doesn't hold labels/definitions), and
 * some categories (e.g. a failed delete surfacing as {@link #PG_MISSING_RDF}) have an
 * intent that "PG-authoritative" alone can't disambiguate. See the reconciler plan addendum.
 */
@Getter
public enum MismatchCategory {

    /**
     * An OWNED concept subject (carries {@code skos:inScheme} + {@code STRSTARTS}) exists in
     * a Fuseki graph but no Postgres row has that {@code conceptIri}. The leak left by a
     * dual-write where the RDF write committed but the PG commit did not. The only
     * auto-repairable category (delete the leaked RDF) — but never for an IRI that is part of
     * a {@link #SUSPECTED_RENAME} pair.
     */
    RDF_ORPHAN(true),

    /**
     * A Postgres concept row exists but its {@code conceptIri} is not owned-resolvable in its
     * declared {@code graphName}. Two root causes that PG-authority cannot disambiguate:
     * a failed create (PG committed, RDF never written) or — the common one, since deletes
     * are TDB2-first — a failed delete (RDF gone, PG row leaked). Report-only; carries a
     * probable-cause hint.
     */
    PG_MISSING_RDF(false),

    /**
     * A Postgres concept is owned-resolvable in SOME graph, but not its declared
     * {@code graphName} (e.g. a half-finished IRI/graph move, or PG {@code graphName}
     * corruption). Report-only.
     */
    IRI_GRAPH_MISMATCH(false),

    /**
     * A Fuseki named graph holds owned concept subjects but no {@code OntologyMetadataEntity}
     * row references it. Report-only by default; deletable only under both
     * {@code auto-repair} and {@code repair-orphan-graphs} (a later phase).
     */
    GRAPH_ORPHAN(false),

    /**
     * An {@link #RDF_ORPHAN} (new IRI) paired with a {@link #PG_MISSING_RDF} (old IRI) in the
     * same graph that look like one half-finished IRI rename. Emitted INSTEAD of those two
     * findings; both IRIs are excluded from any repair. Permanently report-only here — the
     * safe repair (finish the rename in PG) inverts PG-authority and belongs to a separate,
     * explicitly-reviewed step.
     */
    SUSPECTED_RENAME(false),

    /**
     * A subject whose IRI is under a graph's namespace but which carries no
     * {@code skos:inScheme} — a deliberately "excluded" concept (user declined to normalize)
     * or legacy data from before the upload-prune fix. Informational; never repaired.
     */
    EXCLUDED_NO_INSCHEME(false),

    /**
     * A Postgres row whose IRI has triples in its graph but is not ownership-resolvable
     * (e.g. it lost its {@code skos:inScheme} — real corruption). Surfaced distinctly so it
     * doesn't masquerade as {@link #PG_MISSING_RDF}. Report-only.
     */
    RDF_NOT_OWNED_RESOLVABLE(false);

    /**
     * -- GETTER --
     * Whether findings of this category may EVER be auto-repaired (subject to further gates).
     */
    private final boolean autoRepairable;

    MismatchCategory(boolean autoRepairable) {
        this.autoRepairable = autoRepairable;
    }

}