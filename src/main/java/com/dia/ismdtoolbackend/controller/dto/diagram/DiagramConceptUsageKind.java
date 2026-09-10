package com.dia.ismdtoolbackend.controller.dto.diagram;

/**
 * How a concept is drawn on a canvas. Not a synonym for {@code ConceptType}: it names the shape the canvas
 * gives the concept, which is also where its membership is recorded, so "is this concept on that diagram?"
 * is three different lookups.
 *
 * <p><b>These names are a contract with SQL.</b> {@code DiagramRepository.findConceptUsage} emits them as
 * string literals in its {@code UNION ALL} branches and {@code DiagramConceptUsageService} reads them back
 * through {@code valueOf}, so renaming a constant here compiles cleanly and then throws
 * {@code IllegalArgumentException} at runtime. Rename both sides together.
 *
 * <p>{@code DiagramConceptUsageIntegrationTest} covers all three against real Postgres, so a one-sided
 * rename fails there rather than in production — verified by making that rename and watching it fail.
 */
public enum DiagramConceptUsageKind {

    /** A class cell; membership is a {@code diagram_nodes} row. */
    NODE,

    /** A relationship line; membership is a {@code diagram_edges} row keyed by the VZTAH's own IRI. */
    EDGE,

    /** A property row inside its domain class's cell; membership is that node's visible-properties list. */
    PROPERTY_ROW
}