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

    /** Fat read: layout joined to live concept content with each node's overlay applied, edges projected. */
    DiagramDto getDiagram(String ontologySlug);

    /**
     * Save (PG only, no RDF) — the diagram's only write. Layout is a full replace: the node set is canvas
     * membership, and the edge set is the persisted waypoints. Overlays are additive over what is already
     * staged: a concept absent from {@code overlays} keeps its overlay, and an entry carrying only
     * {@code conceptIri} discards that one.
     */
    DiagramDto saveLayout(String ontologySlug, DiagramLayoutDto layout);

    /** Převzít: apply every staged change via the concept CRUD → outbox → RDF, clearing each on success. */
    MaterializeResultDto materialize(String ontologySlug);
}
