package com.dia.ismdtoolbackend.controller.dto.diagram;

import com.dia.ismdtoolbackend.enums.DiagramEdgeKind;
import com.dia.ismdtoolbackend.models.diagram.EdgeWaypoint;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * Write body for {@code PUT /api/diagram/{slug}/{diagramId}/layout} (Save). Layout only, never RDF.
 * {@code nodes} is authoritative for canvas membership as an idempotent full replace: a node present is
 * kept or added, one omitted is removed. {@code overlays} is additive — see the field. See
 * {@code docs/DIAGRAM_LAYER_API.md}.
 */
public record DiagramLayoutDto(
        /* Required on every save; a fresh canvas sends 0. */
        @NotNull Long version,
        ViewportDto viewport,
        @NotNull @Valid List<Node> nodes,
        /* Authoritative canvas membership for edges */
        @Valid List<Edge> edges,
        /* Optional and additive ACROSS concepts: a concept absent from the array keeps
         * what is already staged, so null and [] both mean "not touching overlays", and discarding is
         * explicit.
         * WITHIN one concept an entry is the whole overlay, not a per-field delta — it REPLACES that
         * concept's staged edit, so a field the entry omits is dropped. Send a concept's full staged intent
         * every time. */
        @Valid List<Overlay> overlays
) {

    /**
     * A node's persisted layout. {@code id} is {@code iri:<full-iri>} and a new IRI adds the node;
     * {@code collapsed} is optional on the wire, where null means not collapsed.
     *
     * <p>Any IRI may be placed, this ontology's or another's. Foreignness is not declared here — the server
     * derives it from the concept's own graph and echoes it back as {@code data.readOnly}. It governs
     * placement only, since no overlay may target a foreign concept.
     */
    @Schema(name = "DiagramLayoutNode")
    public record Node(
            @NotBlank String id,
            @NotNull @Valid PositionDto position,
            String parentId,
            Boolean collapsed,
            List<String> visibleProperties
    ) {

        public Node {
            collapsed = collapsed != null && collapsed;
            visibleProperties = visibleProperties != null ? List.copyOf(visibleProperties) : null;
        }
    }

    /**
     * One edge on the canvas: identity plus optional routing. {@code id} is the projected edge id — a VZTAH's
     * concept IRI, or the composite {@code edge|KIND|source|target} of a hierarchy or equivalence link.
     *
     * <p>The entry's presence puts the edge on the canvas; {@code segments} says only how it is drawn, and
     * is three-way: null keeps the stored waypoints, so a client that does not manage routing cannot
     * discard them, {@code []} clears them to default routing, and a list sets them.
     *
     * <p><b>An edge the user has just drawn has no id from a read.</b> A hierarchy or equivalence link is a
     * bare triple, so unlike a node it has no identity of its own and its id is built from its endpoints. The
     * client assembles that same composite itself — see the shared TypeScript helper in
     * {@code docs/DIAGRAM_LAYER_API.md} — so one id matches on the first write and on every read after it,
     * with nothing to re-map when the response comes back. An {@code id} that is neither a composite nor a
     * concept IRI is rejected with 400: its row could never match a projection, so the edge would silently
     * vanish on the next read.
     *
     * <p>{@code edgeKind}, {@code source} and {@code target} are an optional cross-check on a
     * client-assembled id, not a second way to key one: when present, the id must be exactly what they
     * derive, or the save is a 400. They are never stored and never read back as truth — endpoints and kind
     * are re-derived from {@code rdfs:domain}/{@code rdfs:range} ⊕ overlay on every read, so membership can
     * never contradict RDF. Structural changes still go through {@code overlays}, independent of membership.
     */
    @Schema(name = "DiagramLayoutEdge")
    public record Edge(
            @NotBlank String id,
            /* Optional cross-check on a client-assembled id; ignored for a VZTAH, which is keyed by IRI. */
            DiagramEdgeKind edgeKind,
            String source,
            String target,
            List<EdgeWaypoint> segments
    ) {

        /** Membership-only entry for an edge already carrying a projected id. */
        public Edge(String id, List<EdgeWaypoint> segments) {
            this(id, null, null, null, segments);
        }

        /** True when the endpoints needed to derive a composite id are both present. */
        public boolean hasEndpoints() {
            return edgeKind != null
                    && source != null && !source.isBlank()
                    && target != null && !target.isBlank();
        }
    }

    /**
     * One concept's staged structural overlay — the changed structural fields only, IRIs as strings, never
     * RDF content.
     *
     * <p>{@code conceptIri} identifies the target concept rather than a canvas node, optionally
     * {@code iri:}-prefixed: a VZTAH renders as an edge and a VLASTNOST as a row, and both are staged here
     * by their own IRI.
     *
     * <p>An entry is that concept's <em>whole</em> overlay and replaces whatever is staged for it; a field
     * the entry omits is dropped, so there is no per-field delta. An entry carrying every overlay field
     * null discards the overlay outright, {@code conceptIri} being addressing rather than content. An
     * explicitly-empty list ({@code "broaderConcept": []}) is not empty — it stages "clear this predicate".
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
         * Op 6 marker: adds {@code broader} as a super-class of {@code addBroaderOn}, then deletes the
         * VZTAH. Both endpoints are mandatory, since a missing one would delete the concept without the
         * hierarchy link that replaces it.
         */
        @Schema(name = "DiagramLayoutOverlayConvertToHierarchy")
        public record ConvertToHierarchy(@NotBlank String addBroaderOn, @NotBlank String broader) {
        }

        /** True when the entry carries no overlay field at all, which is a discard. */
        public boolean isEmpty() {
            return domain == null
                    && range == null
                    && broaderConcept == null
                    && exactMatch == null
                    && convertToHierarchy == null;
        }
    }
}
