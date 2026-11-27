package com.dia.ismdtoolbackend.service.impl;

import org.springframework.data.domain.Page;
import com.dia.ismdtoolbackend.models.OntologyListSort;
import com.dia.ismdtoolbackend.entity.OntologyMetadataEntity;
import com.dia.ismdtoolbackend.exception.OntologyNotFoundException;
import com.dia.ismdtoolbackend.controller.dto.OntologySummaryDto;
import com.dia.ismdtoolbackend.controller.dto.PageDto;
import com.dia.ismdtoolbackend.models.OntologyListQuery;
import com.dia.ismdtoolbackend.utility.paging.PageParams;
import com.dia.ismdtoolbackend.models.OntologyRef;
import com.dia.ismdtoolbackend.mapper.OntologyMetadataMapper;
import com.dia.ismdtoolbackend.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import java.util.List;
import org.springframework.stereotype.Component;

/** Reads vocabulary metadata: filtered lists, by slugs, and single rows. */
@Slf4j
@Component
@RequiredArgsConstructor
class OntologyMetadataReader {

    private final OntologyMetadataRepository ontologyMetadataRepository;
    private final OntologyMetadataMapper ontologyMetadataMapper;
    private final OntologyMetadataEnricher metadataEnricher;

    /** Visited-vocabulary lookups pass a handful of slugs; more is not a lookup. */
    static final int MAX_SLUGS = 6;

    PageDto<OntologySummaryDto> list(OntologyListQuery query, PageParams page) {
        if (query.slugs().size() > MAX_SLUGS) {
            throw new IllegalArgumentException("Maximální počet slugů je " + MAX_SLUGS + ".");
        }
        boolean anySlug = query.slugs().isEmpty();
        List<String> slugs = anySlug ? List.of("") : query.slugs();

        // Newest-first without a text filter needs no RDF to order, so only the page is enriched.
        if (query.q() == null && query.sort() == OntologyListSort.UPDATED) {
            Page<OntologyMetadataEntity> rows = ontologyMetadataRepository.findForListByUpdated(
                    query.userId(), query.isPublished(), query.graphName(), anySlug, slugs, page.pageable());
            return page.page(metadataEnricher.enrichEntitiesWithBatchMetadata(rows.getContent()),
                    rows.getTotalElements());
        }

        List<OntologyMetadataEntity> entities = ontologyMetadataRepository.findForList(
                query.userId(), query.isPublished(), query.graphName(), anySlug, slugs);
        List<OntologySummaryDto> rows = metadataEnricher.enrichEntitiesWithBatchMetadata(entities);
        return page.slice(OntologyListOrdering.filterAndSort(rows, query.q(), query.sort()));
    }

    OntologyRef getOntologyMetadata(Long ontologyId) {
        return ontologyMetadataMapper.toRef(ontologyMetadataRepository.findById(ontologyId).orElseThrow());
    }

    OntologyRef getOntologyMetadataBySlug(String ontologySlug) {
        return ontologyMetadataRepository.findBySlug(ontologySlug)
                .map(ontologyMetadataMapper::toRef)
                .orElseThrow(() -> new OntologyNotFoundException(
                        "Metadata slovníku s názvem " + ontologySlug + " nebyla nalezena."));
    }
}
