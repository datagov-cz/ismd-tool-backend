package com.dia.ismdtoolbackend.service.impl;

import com.dia.ismdtoolbackend.entity.OntologyMetadataEntity;
import com.dia.ismdtoolbackend.exception.OntologyNotFoundException;
import com.dia.ismdtoolbackend.controller.dto.OntologySummaryDto;
import com.dia.ismdtoolbackend.models.OntologyRef;
import com.dia.ismdtoolbackend.mapper.OntologyMetadataMapper;
import com.dia.ismdtoolbackend.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.jena.ontology.OntologyException;
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

    List<OntologySummaryDto> getAll(String userId, Boolean isPublished) {
        List<OntologyMetadataEntity> ontologyMetadataEntities;

        if (userId != null && isPublished != null) {
            ontologyMetadataEntities = ontologyMetadataRepository.findAllByUserIdAndIsPublished(userId, isPublished);
        } else if (userId != null) {
            ontologyMetadataEntities = ontologyMetadataRepository.findAllByUserId(userId);
        } else if (isPublished != null) {
            ontologyMetadataEntities = ontologyMetadataRepository.findAllByIsPublished(isPublished);
        } else {
            ontologyMetadataEntities = ontologyMetadataRepository.findAll();
        }

        return metadataEnricher.enrichEntitiesWithBatchMetadata(ontologyMetadataEntities);
    }

    List<OntologySummaryDto> getBySlugs(List<String> slugs) {
        if (slugs == null || slugs.isEmpty()) {
            throw new OntologyException("Seznam slugů je prázdný");
        }

        if (slugs.size() > 6) {
            throw new OntologyException("Maximální počet slugů je 6");
        }

        List<OntologyMetadataEntity> ontologyMetadataEntities = ontologyMetadataRepository.findBySlugIn(slugs);

        return metadataEnricher.enrichEntitiesWithBatchMetadata(ontologyMetadataEntities);
    }

    OntologyRef getOntologyMetadata(Long ontologyId) {
        return ontologyMetadataMapper.toRef(ontologyMetadataRepository.findById(ontologyId).orElseThrow());
    }

    OntologyRef getOntologyMetadataBySlug(String ontologySlug) {
        return ontologyMetadataRepository.findBySlug(ontologySlug)
                .map(ontologyMetadataMapper::toRef)
                .orElseThrow(() -> {
                    log.error("ontologySlug {} not found", ontologySlug);
                    return new OntologyNotFoundException(
                            "Metadata slovníku s názvem " + ontologySlug + " nebyla nalezena.");
                });
    }
}
