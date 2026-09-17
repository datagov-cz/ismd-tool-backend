package com.dia.ismdtoolbackend.controller.dto.diagram;

/** ReactFlow pan/zoom. Null on a diagram that has never been saved. */
public record ViewportDto(Double x, Double y, Double zoom) {
}
