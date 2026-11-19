package com.dia.ismdtoolbackend.service.impl;

import com.dia.ismdtoolbackend.entity.DiagramEntity;
import com.dia.ismdtoolbackend.entity.OntologyMetadataEntity;
import com.dia.ismdtoolbackend.exception.OntologyNotFoundException;
import com.dia.ismdtoolbackend.repository.DiagramRepository;
import com.dia.ismdtoolbackend.repository.OntologyMetadataRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Resolves the ontology and diagram a request names, and checks that the diagram belongs to the ontology. */
@Component
@RequiredArgsConstructor
class DiagramScopeReader {

    private final OntologyMetadataRepository ontologyMetadataRepository;
    private final DiagramRepository diagramRepository;

    OntologyMetadataEntity requireOntology(String ontologySlug) {
        return ontologyMetadataRepository.findBySlug(ontologySlug)
                .orElseThrow(() -> new OntologyNotFoundException(
                        "Metadata slovníku s názvem " + ontologySlug + " nebyla nalezena."));
    }

    /**
     * Resolves a diagram, requiring that it belongs to the ontology in the path. The endpoints authorize the
     * slug only, so without this an owner of any ontology could reach another's diagram through their own
     * slug. 404 rather than 403, so the response does not confirm the id exists elsewhere.
     */
    DiagramEntity requireDiagramOf(OntologyMetadataEntity ontology, String ontologySlug,
                                           Long diagramId) {
        return diagramRepository.findByIdAndOntologyMetadataId(diagramId, ontology.getId())
                .orElseThrow(() -> new EntityNotFoundException(
                        "Diagram " + diagramId + " nepatří do slovníku " + ontologySlug + "."));
    }
}
