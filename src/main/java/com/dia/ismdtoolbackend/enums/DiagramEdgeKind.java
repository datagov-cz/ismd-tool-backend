package com.dia.ismdtoolbackend.enums;

/**
 * Semantic type of a diagram edge. Edges are a visual projection of {@code live-content ⊕ overlay};
 * see {@code docs/DIAGRAM_LAYER.md}.
 */
public enum DiagramEdgeKind {

    /** {@code rdfs:domain} — property/relationship node to its subject class. */
    DOMAIN,

    /** {@code rdfs:range} — relationship, or a property's value type, to its object. */
    RANGE,

    /** {@code rdfs:subClassOf} between classes (TRIDA). */
    SUBCLASS_OF,

    /** {@code rdfs:subPropertyOf} between properties (VLASTNOST). */
    SUB_PROPERTY,

    /** Super-relation between relationships (VZTAH). */
    SUB_RELATION,

    /** {@code skos:exactMatch}. */
    EXACT_MATCH
}