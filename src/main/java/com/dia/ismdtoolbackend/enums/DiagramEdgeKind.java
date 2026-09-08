package com.dia.ismdtoolbackend.enums;

/**
 * Semantic type of a diagram edge, a visual projection of {@code live-content ⊕ overlay}. {@link #VZTAH} is
 * concept-backed — the edge is a relationship concept carrying its own IRI and label — while the other two
 * are bare RDF triples with no concept behind them, hence no {@code iri} in their wire payload.
 *
 * <p>A relationship is one edge between its two classes rather than a node with an edge to each, and links
 * between two properties or two relationships are not drawn, neither endpoint being a node. See
 * {@code docs/DIAGRAM_LAYER.md} and {@code .planning/diagram-edge-model-REDESIGN.md}.
 */
public enum DiagramEdgeKind {

    /** A relationship concept, drawn from its {@code rdfs:domain} class to its {@code rdfs:range} class. */
    VZTAH,

    /** {@code rdfs:subClassOf} between classes (TRIDA). */
    SUBCLASS_OF,

    /** {@code skos:exactMatch}. */
    EXACT_MATCH
}
