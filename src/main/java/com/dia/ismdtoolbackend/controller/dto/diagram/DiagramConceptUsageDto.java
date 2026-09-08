package com.dia.ismdtoolbackend.controller.dto.diagram;

import com.dia.ismdtoolbackend.controller.dto.ResolvedConceptDto;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.Map;

/**
 * Where a concept appears on canvases, for the concept detail page. One entry per diagram that draws it,
 * each carrying enough to render a link ({@code ontologySlug} plus {@code diagramId}) and to show what that
 * canvas says the concept is.
 *
 * <p>"On the canvas" means three things by concept type, all covered here: a TŘÍDA is a node, a VZTAH an
 * edge, and a VLASTNOST a row inside its domain class's node. A concept the ontology contains but no canvas
 * draws returns an empty list, which is a normal answer.
 *
 * <p>Structure is reported as that diagram currently shows it — live RDF overlaid with that diagram's own
 * staged edits — so a canvas with a staged domain change reports the staged value and flags itself
 * {@code pending}. Two diagrams disagreeing about one concept is what the user is being shown. See
 * {@code docs/DIAGRAM_LAYER_API.md}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DiagramConceptUsageDto(
        String conceptIri,
        /* Resolved once from live RDF; identity does not vary by canvas, unlike the structure below. */
        Map<String, String> conceptName,
        String conceptSlug,
        @Schema(description = "Diagramy, na jejichž plátně se pojem nachází. Prázdné, pokud na žádném.")
        List<Placement> placements
) {

    /**
     * One diagram that draws this concept, plus the structure that diagram shows for it.
     * {@code domain}/{@code range} are populated for a VZTAH and VLASTNOST and null for a TŘÍDA, which has
     * neither; {@code broader}/{@code exactMatch} carry the resolved hierarchy, empty rather than null.
     */
    @Schema(name = "DiagramConceptPlacement")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Placement(
            Long diagramId,
            String diagramName,
            String ontologySlug,
            @Schema(description = "Jak je pojem na plátně vykreslen: NODE (třída), EDGE (vztah) "
                    + "nebo PROPERTY_ROW (vlastnost uvnitř své třídy).")
            DiagramConceptUsageKind kind,
            /* The VLASTNOST's host class, set only for PROPERTY_ROW: a property row is drawn inside a
             * class cell, so without this the user cannot tell where on the canvas to look. */
            ResolvedConceptDto hostClass,
            ResolvedConceptDto domain,
            ResolvedConceptDto range,
            List<ResolvedConceptDto> broader,
            List<ResolvedConceptDto> exactMatch,
            @Schema(description = "Tento diagram má na pojmu rozpracovanou (nepřevzatou) změnu, "
                    + "takže zobrazená struktura se liší od RDF.")
            boolean pending
    ) {
    }
}