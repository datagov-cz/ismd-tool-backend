package com.dia.ismdtoolbackend.controller.dto.diagram;

import com.dia.ismdtoolbackend.models.diagram.DiagramPendingEdit;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.Map;

/**
 * The concepts this diagram and a sibling diagram of the same ontology both stage an edit on, returned with
 * HTTP 409 from Převzít when no resolution was given. Materializing one side writes RDF the other's staged
 * edit was fingerprinted against, so the sibling would afterwards fail {@code STALE_BASE} one concept at a
 * time; reporting the whole collision up front lets the user settle it in one decision. See
 * {@code docs/DIAGRAM_LAYER_API.md}.
 */
@Schema(description = "Přehled změn, které kolidují se změnami rozpracovanými v jiném diagramu.")
public record DiagramConflictDto(

        @Schema(description = "Kolidující pojmy — pro každý změna zde a změna v druhém diagramu.")
        List<Conflict> conflicts
) {

    /** One concept staged on both this diagram and at least one sibling. */
    @Schema(description = "Jeden pojem, na kterém mají rozpracovanou změnu oba diagramy.")
    public record Conflict(

            @Schema(description = "IRI pojmu, kterého se kolize týká.")
            String conceptIri,

            @Schema(description = "Název pojmu podle jazyka.")
            Map<String, String> label,

            @Schema(description = "Změna rozpracovaná v tomto diagramu.")
            DiagramPendingEdit mine,

            @Schema(description = "Kolidující změny v ostatních diagramech téhož slovníku.")
            List<Theirs> theirs
    ) {
    }

    /** A competing staged edit and the canvas holding it. */
    @Schema(description = "Kolidující změna v jiném diagramu.")
    public record Theirs(

            @Schema(description = "ID druhého diagramu.")
            Long diagramId,

            @Schema(description = "Název druhého diagramu.")
            String diagramName,

            @Schema(description = "Změna rozpracovaná v druhém diagramu.")
            DiagramPendingEdit pendingEdit
    ) {
    }
}
