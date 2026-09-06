package com.dia.ismdtoolbackend.controller.dto.diagram;

import com.dia.ismdtoolbackend.models.diagram.EdgeWaypoint;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * Thin write body for {@code PUT /api/diagram/{slug}/{diagramId}/layout} (Save). Layout only — never RDF.
 * {@code nodes} is authoritative for canvas membership (idempotent full-replace): a node present is kept
 * or added, a node omitted is removed. {@code overlays} is additive, not authoritative — see the field.
 * See {@code docs/DIAGRAM_LAYER_API.md}.
 */
public record DiagramLayoutDto(
        /* Required on every save, including the first — a fresh canvas sends 0. */
        @NotNull Long version,
        ViewportDto viewport,
        @NotNull @Valid List<Node> nodes,
        /* Optional full-replace of the saved waypoints; null and [] both mean "no hand-routed edges".
         * An omitted edge still renders — it is re-projected, and reverts to default routing. */
        @Valid List<Edge> edges,
        /* Optional, and ADDITIVE — unlike `edges` above. An entry stages or updates that concept's
         * overlay; a concept absent from the array keeps whatever is already staged, so null and [] both
         * mean "not touching overlays". Discarding is explicit: an entry carrying only conceptIri. */
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
            Boolean collapsed,
            /* The VLASTNOST rows this class cell renders — authoritative full-replace, like `position`.*/
            List<String> properties,
            /*
             * This node references a concept from ANOTHER ontology (or NKD), placed for context and
             * rendered read-only. Optional; omitted means an ordinary own-ontology node. The server
             * verifies the claim both ways — a foreign IRI needs this set, and setting it on an own-graph
             * concept is a 400. Permits PLACEMENT only: no overlay may target a foreign concept.
             */
            Boolean isForeign
    ) {

        public Node {
            collapsed = collapsed != null && collapsed;
            properties = properties != null ? List.copyOf(properties) : List.of();
            isForeign = isForeign != null && isForeign;
        }
    }

    /**
     * A projected edge's persisted waypoints, and nothing else. {@code id} is the projected edge id from
     * the last read — a VZTAH's concept IRI, or the composite {@code edge|KIND|source|target} of a
     * hierarchy/equivalence link. {@code segments} is optional; omitted or null means default routing.
     *
     * <p>Endpoints and kind are absent: they are re-derived from {@code rdfs:domain}/{@code rdfs:range}
     * ⊕ overlay on every read. Structural changes go through {@code overlays}.
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
     * <p>{@code conceptIri} identifies the target <em>concept</em>, not a canvas node, optionally
     * {@code iri:}-prefixed — a VZTAH renders as an edge and a VLASTNOST as a row, and both are staged
     * here by their own IRI.
     *
     * <p>An entry carrying every overlay field null discards that concept's overlay; {@code conceptIri} is
     * addressing, not content, so it never counts toward emptiness. An explicitly-empty <em>list</em>
     * ({@code "broaderConcept": []}) is not empty — it stages "clear this predicate".
     *
     * <p>{@code baseUpdatedAt} is absent from the wire: the service captures the stale-base fingerprint
     * itself, so a client cannot forge the value the STALE_BASE guard compares against.
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
         * Both endpoints are mandatory — a missing one would delete the concept without the hierarchy
         * link that replaces it.
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
