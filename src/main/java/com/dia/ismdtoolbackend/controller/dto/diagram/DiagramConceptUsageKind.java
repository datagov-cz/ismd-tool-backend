package com.dia.ismdtoolbackend.controller.dto.diagram;

/**
 * How a concept is drawn on a canvas. Not a synonym for {@code ConceptType}: it names the shape the
 * canvas gives the concept, which is also where its membership is recorded — the reason "is this
 * concept on that diagram?" is three different lookups.
 */
public enum DiagramConceptUsageKind {

    /** A class cell. Membership is a {@code diagram_nodes} row. */
    NODE,

    /** A relationship line. Membership is a {@code diagram_edges} row keyed by the VZTAH's own IRI. */
    EDGE,

    /** A property row inside its domain class's cell. Membership is that node's visible-properties list. */
    PROPERTY_ROW
}