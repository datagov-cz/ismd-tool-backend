package com.dia.ismdtoolbackend.controller.dto.diagram;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.NotBlank;

import java.util.List;

/**
 * Stage one node's structural overlay via {@code PATCH /api/diagram/{slug}/nodes/overlay}.
 * Only the changed structural fields; IRIs as strings. Structural-only. An empty/all-null body discards the overlay.
 * See {@code docs/DIAGRAM_LAYER_API.md}.
 *
 * <p>{@code nodeId} identifies the target node ({@code iri:<full-iri>}). It travels in the body, not the
 * path: a concept IRI contains slashes, which cannot survive a path segment.
 *
 * <p>{@code baseUpdatedAt} is not on the wire — the service captures the concept's stale-base
 * fingerprint at stage time.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record NodeOverlayDto(
        @NotBlank String nodeId,
        String domain,
        String range,
        List<String> broaderConcept,
        List<String> superProperty,
        List<String> superRelation,
        List<String> exactMatch,
        ConvertToHierarchy convertToHierarchy
) {

    /** Op 6 marker: add {@code broader} as a super-class of {@code addBroaderOn}, then delete the VZTAH. */
    public record ConvertToHierarchy(String addBroaderOn, String broader) {
    }

    /**
     * True when the body carries no overlay field at all — a discard.
     */
    public boolean isEmpty() {
        return domain == null
                && range == null
                && broaderConcept == null
                && superProperty == null
                && superRelation == null
                && exactMatch == null
                && convertToHierarchy == null;
    }
}
