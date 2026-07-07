package com.dia.ismdtoolbackend.enums;

/**
 * The semantic type of a diagram edge — what kind of link it is between the two nodes it connects.
 *
 * <p>The FE authors the edge set and the diagram persists every edge as sent, regardless of whether RDF
 * already implies it. So this kind, together with the edge's endpoints, fully describes the link; the
 * diagram does not re-derive edges from RDF on load.
 */
public enum DiagramEdgeKind {

    /**
     * {@code rdfs:domain} — links a property (VLASTNOST) or relationship (VZTAH) node to its subject
     * class. This is the edge a class property is drawn as.
     */
    DOMAIN,

    /** {@code rdfs:range} — links a relationship (VZTAH), or a property's value type, to its object. */
    RANGE,

    /** rdfs:subClassOf between classes (TRIDA). */
    SUBCLASS_OF,

    /** rdfs:subPropertyOf between properties (VLASTNOST). */
    SUB_PROPERTY,

    /** Super-relation between relationships (VZTAH). */
    SUB_RELATION,

    /** skos:exactMatch / equivalent-concept link. */
    EXACT_MATCH,

    /**
     * A link with at least one draft endpoint whose intended semantics aren't yet one of the kinds above.
     * Refined into a concrete kind once both endpoints are real (e.g. at promote time).
     */
    DRAFT_LINK
}