package com.dia.ismdtoolbackend.reconciler;

/**
 * One PG↔TDB2 consistency finding.
 *
 * @param category    the kind of drift
 * @param graphName   the Fuseki graph / ontology IRI the finding pertains to (always set)
 * @param conceptIri  the primary concept IRI; for {@link MismatchCategory#SUSPECTED_RENAME}
 *                    this is the NEW (TDB2-side) IRI; null for {@link MismatchCategory#GRAPH_ORPHAN}
 * @param relatedIri  secondary IRI — the OLD (PG-side) IRI for {@code SUSPECTED_RENAME}; null otherwise
 * @param detail      human-readable probable cause / context (e.g. why a PG_MISSING_RDF arose)
 */
public record Mismatch(
        MismatchCategory category,
        String graphName,
        String conceptIri,
        String relatedIri,
        String detail
) {

    public static Mismatch of(MismatchCategory category, String graphName, String conceptIri, String detail) {
        return new Mismatch(category, graphName, conceptIri, null, detail);
    }

    public static Mismatch graphOrphan(String graphName, String detail) {
        return new Mismatch(MismatchCategory.GRAPH_ORPHAN, graphName, null, null, detail);
    }

    /** A suspected IRI rename: {@code newIri} is the TDB2-side orphan, {@code oldIri} the PG-side. */
    public static Mismatch suspectedRename(String graphName, String newIri, String oldIri, String detail) {
        return new Mismatch(MismatchCategory.SUSPECTED_RENAME, graphName, newIri, oldIri, detail);
    }
}