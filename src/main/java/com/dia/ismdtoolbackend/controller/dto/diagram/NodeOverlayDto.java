package com.dia.ismdtoolbackend.controller.dto.diagram;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * Stage one node's structural overlay via {@code PATCH /api/diagram/{slug}/nodes/{nodeId}/overlay}.
 * Only the changed structural fields; IRIs as strings. Structural-only. An empty/all-null body discards the overlay.
 * See {@code docs/DIAGRAM_LAYER_API.md}.
 *
 * <p>{@code baseUpdatedAt} is not on the wire — the service captures the concept's stale-base
 * fingerprint at stage time.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record NodeOverlayDto(
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
     * True when the body carries no field at all — a discard. An explicitly-empty list is NOT empty: it is a
     * meaningful "clear this predicate" (e.g. op 2's flip A-side drops its last {@code broaderConcept}), so
     * it must stage rather than be discarded. Only an all-null body (or {@code {}}) discards.
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
