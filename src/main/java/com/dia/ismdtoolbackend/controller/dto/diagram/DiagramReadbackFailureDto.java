package com.dia.ismdtoolbackend.controller.dto.diagram;

/**
 * Error payload for {@code DIAGRAM_SAVED_READBACK_FAILED}: the write committed, only the content read failed.
 * Carries the post-write {@code version} so the client can save again without a recovery {@code GET} first.
 * See {@code docs/DIAGRAM_LAYER_API.md}.
 */
public record DiagramReadbackFailureDto(Long version) {
}
