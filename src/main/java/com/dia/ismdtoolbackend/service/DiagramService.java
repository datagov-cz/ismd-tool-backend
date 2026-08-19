package com.dia.ismdtoolbackend.service;

import com.dia.ismdtoolbackend.controller.dto.diagram.DiagramDto;
import com.dia.ismdtoolbackend.controller.dto.diagram.DiagramLayoutDto;
import com.dia.ismdtoolbackend.controller.dto.diagram.DiagramSummaryDto;
import com.dia.ismdtoolbackend.controller.dto.diagram.MaterializeResultDto;
import com.dia.ismdtoolbackend.controller.dto.diagram.NodeOverlayDto;

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

    /** Save (PG only, no RDF): full-replace layout + overlays; the node set is canvas membership. */
    DiagramDto saveLayout(String ontologySlug, DiagramLayoutDto layout);

    /**
     * Stage a concept's structural overlay; an empty payload discards it. Returns the refreshed node only.
     * {@code conceptRef} is the concept's IRI, optionally {@code iri:}-prefixed.
     */
    DiagramDto.Node stageOverlay(String ontologySlug, String conceptRef, NodeOverlayDto overlay);

    /** Převzít: apply every staged change via the concept CRUD → outbox → RDF, clearing each on success. */
    MaterializeResultDto materialize(String ontologySlug);
}
