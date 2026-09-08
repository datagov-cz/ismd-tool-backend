package com.dia.ismdtoolbackend.service;

import com.dia.ismdtoolbackend.controller.dto.diagram.DiagramDto;
import com.dia.ismdtoolbackend.controller.dto.diagram.DiagramLayoutDto;
import com.dia.ismdtoolbackend.controller.dto.diagram.DiagramSummaryDto;
import com.dia.ismdtoolbackend.controller.dto.diagram.MaterializeResultDto;

import java.util.List;

/**
 * The diagram layer's service surface: layout and the pending-edit overlay, plus the Převzít fan-out to the
 * concept services. Content has a single owner in RDF; the overlay is a keyed, transient diff. See
 * {@code docs/DIAGRAM_LAYER.md}.
 */
public interface DiagramService {

    /** Every diagram's identity and node count, for a diagram picker. */
    List<DiagramSummaryDto> listAll();

    /** The ontology's diagrams, oldest first; identity and node count only. */
    List<DiagramSummaryDto> listForOntology(String ontologySlug);

    /** Creates an empty canvas for an ontology, which may hold many. */
    DiagramDto createDiagram(String ontologySlug, String name);

    /** Deletes one diagram with its layout and staged edits; the ontology's concepts are untouched. */
    void deleteDiagram(String ontologySlug, Long diagramId);

    /**
     * Renames one diagram. Separate from {@link #saveLayout}, which reads live content back and can fail
     * with a 502 after its write commits; this touches PG only and cannot.
     *
     * @return the renamed diagram's summary, identity only
     */
    DiagramSummaryDto renameDiagram(String ontologySlug, Long diagramId, String name);

    /** Layout joined to live concept content, each node's overlay applied and edges projected. */
    DiagramDto getDiagram(String ontologySlug, Long diagramId);

    /**
     * Save: the diagram's only layout write, PG only. Layout is a full replace — the node set is canvas
     * membership and the edge set the persisted waypoints. Overlays are additive across concepts: one
     * absent from {@code overlays} keeps its overlay, and an entry carrying only {@code conceptIri}
     * discards that one. Within a concept an entry is the whole overlay and replaces what is staged.
     */
    DiagramDto saveLayout(String ontologySlug, Long diagramId, DiagramLayoutDto layout);

    /**
     * Převzít: applies every staged change through the concept CRUD → outbox → RDF, clearing each on
     * success. Refuses with a conflict report when a sibling diagram of the same ontology stages an edit on
     * one of the same concepts, unless {@code onConflict} names the winning side.
     *
     * @param winnerDiagramId the diagram whose edits win, required by {@link ConflictResolution#ACCEPT_THEIRS}
     *                        and rejected by every other value
     */
    MaterializeResultDto materialize(String ontologySlug, Long diagramId,
                                     ConflictResolution onConflict, Long winnerDiagramId);

    /**
     * Which side wins when sibling diagrams stage edits on the same concept. A resolution names exactly one
     * winner however many canvases are in conflict, and every loser's contested edits are discarded in the
     * same pass — naming a side to discard would leave the remaining siblings' collisions standing.
     */
    enum ConflictResolution {
        /** This diagram wins: discards every sibling's conflicting edits, then materializes it. */
        ACCEPT_MINE,
        /**
         * A named sibling wins: discards the conflicting edits on this diagram and every other sibling, then
         * materializes the winner. Requires {@code winnerDiagramId}.
         */
        ACCEPT_THEIRS
    }
}
