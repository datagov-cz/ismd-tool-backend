package com.dia.ismdtoolbackend.service;

import com.dia.ismdtoolbackend.controller.dto.diagram.DiagramDto;
import com.dia.ismdtoolbackend.controller.dto.diagram.DiagramLayoutDto;
import com.dia.ismdtoolbackend.controller.dto.diagram.DiagramSummaryDto;
import com.dia.ismdtoolbackend.controller.dto.diagram.MaterializeResultDto;

import java.util.List;

/**
 * The diagram layer's service surface — layout + the pending-edit overlay, plus the Převzít fan-out
 * to the existing concept services. Content has a single owner (RDF); the overlay is a keyed, transient
 * diff. See {@code docs/DIAGRAM_LAYER.md}.
 */
public interface DiagramService {

    /** Lightweight list of every diagram (identity + node count), for a diagram picker. */
    List<DiagramSummaryDto> listAll();

    /** The ontology's diagrams, oldest first — identity and node count only. */
    List<DiagramSummaryDto> listForOntology(String ontologySlug);

    /** Create a new, empty canvas for an ontology. An ontology may hold many. */
    DiagramDto createDiagram(String ontologySlug, String name);

    /** Delete one diagram, its layout and its staged edits. The ontology's concepts are untouched. */
    void deleteDiagram(String ontologySlug, Long diagramId);

    /**
     * Rename one canvas. Deliberately separate from {@link #saveLayout}: that path reads live concept
     * content back and can fail with a 502 AFTER its write commits, which would report a successful
     * rename as an error. This touches PG only and cannot.
     *
     * @return the renamed diagram's summary — identity only, no graph read
     */
    DiagramSummaryDto renameDiagram(String ontologySlug, Long diagramId, String name);

    /** Fat read: layout joined to live concept content with each node's overlay applied, edges projected. */
    DiagramDto getDiagram(String ontologySlug, Long diagramId);

    /**
     * Save (PG only, no RDF) — the diagram's only layout write. Layout is a full replace: the node set is canvas
     * membership, and the edge set is the persisted waypoints. Overlays are additive over what is already
     * staged: a concept absent from {@code overlays} keeps its overlay, and an entry carrying only
     * {@code conceptIri} discards that one.
     */
    DiagramDto saveLayout(String ontologySlug, Long diagramId, DiagramLayoutDto layout);

    /**
     * Převzít: apply every staged change via the concept CRUD → outbox → RDF, clearing each on success.
     *
     * <p>Refuses with a conflict report when a sibling diagram of the same ontology stages an edit on
     * one of the same concepts, unless {@code onConflict} says which side to discard.
     */
    MaterializeResultDto materialize(String ontologySlug, Long diagramId, ConflictResolution onConflict);

    /** How to proceed when sibling diagrams stage edits on the same concept. */
    enum ConflictResolution {
        /** Discard the conflicting edits staged on THIS diagram, then materialize what remains. */
        DISCARD_MINE,
        /** Discard the conflicting edits staged on the SIBLING diagrams, then materialize. */
        DISCARD_THEIRS
    }
}
