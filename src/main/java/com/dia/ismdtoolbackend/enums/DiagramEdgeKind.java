package com.dia.ismdtoolbackend.enums;

/**
 * Semantic type of a diagram edge. Edges are a visual projection of {@code live-content ⊕ overlay};
 * see {@code docs/DIAGRAM_LAYER.md}.
 *
 * <p>{@link #VZTAH} is concept-backed — the edge <em>is</em> a relationship concept, carrying its own IRI
 * and label. The other two are bare RDF triples with no concept behind them, which is why their wire
 * payload has no {@code iri}.
 *
 * <p>{@code DOMAIN}/{@code RANGE} are gone: a relationship is one edge between its two classes, not a node
 * with an edge to each. {@code SUB_PROPERTY}/{@code SUB_RELATION} are gone too — a property is a row
 * inside its class and a relationship is a connector, so a link between two of them has no drawable
 * endpoints, and the business semantics are undefined. See
 * {@code .planning/diagram-edge-model-REDESIGN.md}.
 */
public enum DiagramEdgeKind {

    /** A relationship concept, drawn from its {@code rdfs:domain} class to its {@code rdfs:range} class. */
    VZTAH,

    /** {@code rdfs:subClassOf} between classes (TRIDA). */
    SUBCLASS_OF,

    /** {@code skos:exactMatch}. */
    EXACT_MATCH
}
