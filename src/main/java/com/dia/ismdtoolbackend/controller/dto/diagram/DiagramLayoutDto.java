package com.dia.ismdtoolbackend.controller.dto.diagram;

import com.dia.ismdtoolbackend.models.diagram.EdgeWaypoint;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * Thin write body for {@code PUT /api/diagram/{slug}/layout} (Save) — the diagram's only write endpoint.
 * Layout only — never RDF. The {@code nodes} array is authoritative for canvas membership (idempotent
 * full-replace): a node present is kept or added (a new IRI is hydrated in the response), a node omitted
 * is removed from the canvas. {@code overlays} is additive, not authoritative — see the field.
 * See {@code docs/DIAGRAM_LAYER_API.md}.
 */
public record DiagramLayoutDto(
        /* Required on every save, including the first — a fresh canvas sends 0. */
        @NotNull Long version,
        ViewportDto viewport,
        @NotNull @Valid List<Node> nodes,
        /* Optional: null and [] both mean "no hand-routed edges" — the state of a freshly auto-laid-out
         * canvas, where ReactFlow has positioned everything and the user has not dragged a waypoint yet.
         * Full-replace of the saved waypoints: an omitted edge still renders (it is re-projected), it just
         * reverts to default routing. */
        @Valid List<Edge> edges,
        /* Optional, and ADDITIVE — deliberately unlike `edges` above. An entry stages or updates that
         * concept's overlay; a concept absent from the array keeps whatever is already staged, so null and
         * [] both mean "not touching overlays". Discarding is explicit: an entry carrying only conceptIri.
         * Full-replace is wrong here because a staged overlay need not be visible in a read at all (an
         * off-canvas endpoint, or a concept deleted underneath the diagram), so the client cannot echo back
         * what it was never shown. */
        @Valid List<Overlay> overlays
) {

    /**
     * A node's persisted layout. {@code id} is {@code iri:<full-iri>}; a new IRI adds the node.
     * {@code collapsed} is optional on the wire — omitted or null means not collapsed.
     */
    @Schema(name = "DiagramLayoutNode")
    public record Node(
            @NotBlank String id,
            @NotNull @Valid PositionDto position,
            String parentId,
            Boolean collapsed
    ) {

        public Node {
            collapsed = collapsed != null && collapsed;
        }
    }

    /**
     * A projected edge's persisted waypoints, and nothing else. {@code id} is the projected edge id from
     * the last read — a VZTAH's concept IRI, or the composite {@code edge|KIND|source|target} of a
     * hierarchy/equivalence link. {@code segments} is optional; omitted or null means default routing.
     *
     * <p>Endpoints and kind are deliberately absent: they are derived from {@code rdfs:domain}/
     * {@code rdfs:range} ⊕ overlay on every read, so accepting them here would let a client persist a
     * value that contradicts the projection. Structural changes go through the overlay endpoint.
     */
    @Schema(name = "DiagramLayoutEdge")
    public record Edge(
            @NotBlank String id,
            List<EdgeWaypoint> segments
    ) {
    }

    /**
     * One concept's staged structural overlay. Only the changed structural fields; IRIs as strings.
     * Structural-only — never RDF content.
     *
     * <p>{@code conceptIri} identifies the target concept, optionally {@code iri:}-prefixed. It addresses a
     * <em>concept</em>, not a canvas node — a VZTAH renders as an edge and a VLASTNOST as a row inside its
     * class, and both are staged through this same field by their own IRI, which is why overlays ride their
     * own array rather than nesting inside {@code nodes}.
     *
     * <p>An entry carrying every overlay field null discards that concept's overlay; {@code conceptIri} is
     * addressing, not content, so it never counts toward emptiness. An explicitly-empty <em>list</em>
     * ({@code "broaderConcept": []}) is not empty — it stages "clear this predicate".
     *
     * <p>{@code baseUpdatedAt} is deliberately absent from the wire: the service captures the concept's
     * stale-base fingerprint itself. Accepting it would let a client forge the value the STALE_BASE guard
     * compares against.
     */
    @Schema(name = "DiagramLayoutOverlay")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Overlay(
            @NotBlank String conceptIri,
            String domain,
            String range,
            List<String> broaderConcept,
            List<String> exactMatch,
            @Valid ConvertToHierarchy convertToHierarchy
    ) {

        /**
         * Op 6 marker: add {@code broader} as a super-class of {@code addBroaderOn}, then delete the VZTAH.
         * Both endpoints are mandatory — the marker deletes a concept, and a missing endpoint would delete
         * it without establishing the hierarchy link that replaces it.
         */
        @Schema(name = "DiagramLayoutOverlayConvertToHierarchy")
        public record ConvertToHierarchy(@NotBlank String addBroaderOn, @NotBlank String broader) {
        }

        /** True when the entry carries no overlay field at all — a discard. */
        public boolean isEmpty() {
            return domain == null
                    && range == null
                    && broaderConcept == null
                    && exactMatch == null
                    && convertToHierarchy == null;
        }
    }
}
