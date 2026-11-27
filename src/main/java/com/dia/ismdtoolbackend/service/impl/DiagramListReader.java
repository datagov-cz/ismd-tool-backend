package com.dia.ismdtoolbackend.service.impl;

import com.dia.ismdtoolbackend.utility.paging.PageParams;
import org.springframework.data.domain.Page;
import com.dia.ismdtoolbackend.entity.OntologyMetadataEntity;
import com.dia.ismdtoolbackend.repository.DiagramRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/** Reads diagram summary rows from Postgres, across all ontologies or for one. */
@Component
@RequiredArgsConstructor
class DiagramListReader {

    private final DiagramRepository diagramRepository;
    private final DiagramScopeReader scopeReader;

    Page<DiagramRepository.DiagramSummaryRow> listAll(String userId, PageParams page) {
        // Blank is treated as absent: an omitted query param and `?userId=` should both mean
        // "no filter" rather than "match the empty user", which would return nothing.
        String filter = userId == null || userId.isBlank() ? null : userId.trim();
        return diagramRepository.findSummaryPage(filter, page.pageable());
    }

    List<DiagramRepository.DiagramSummaryRow> listForOntology(String ontologySlug) {
        OntologyMetadataEntity ontology = scopeReader.requireOntology(ontologySlug);
        return diagramRepository.findSummaries(ontology.getId(), null);
    }
}
