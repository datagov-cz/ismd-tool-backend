package com.dia.ismdtoolbackend.controller.dto.diagram;

import jakarta.validation.constraints.NotNull;

/** A node's canvas coordinates. */
public record PositionDto(@NotNull Double x, @NotNull Double y) {
}
