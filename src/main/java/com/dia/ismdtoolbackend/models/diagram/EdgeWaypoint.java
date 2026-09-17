package com.dia.ismdtoolbackend.models.diagram;

/**
 * One waypoint on an edge's route, in the same canvas coordinate space as a node's position. Waypoints are
 * pure presentation — they shape how a link is drawn and carry no meaning for the concepts it connects, so
 * nothing derives them from RDF and nothing validates them against it. Serialized to
 * {@code diagram_edges.segments_json}. See {@code docs/DIAGRAM_LAYER.md}.
 */
public record EdgeWaypoint(double x, double y) {
}