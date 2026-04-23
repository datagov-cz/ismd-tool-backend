package com.dia.ismdtoolbackend.service.impl;

import com.dia.ismdtoolbackend.client.NkdSparqlClient;
import com.dia.ismdtoolbackend.controller.dto.GetNkdConceptDto;
import com.dia.ismdtoolbackend.controller.dto.GetNkdOntologyDto;
import com.dia.ismdtoolbackend.controller.dto.GetNkdOntologyListDto;
import com.dia.ismdtoolbackend.controller.dto.NkdOntologyListItemDto;
import com.dia.ismdtoolbackend.exception.NkdEndpointException;
import com.dia.ismdtoolbackend.exception.NkdResourceNotFoundException;
import com.dia.ismdtoolbackend.models.OntologyDetailModel;
import com.dia.ismdtoolbackend.service.NkdDetailService;
import com.dia.ismdtoolbackend.utility.security.SparqlIriValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class NkdDetailServiceImpl implements NkdDetailService {

    /**
     * Hard cap on a single batch — the FE limits to 10, this is 5x slack for power
     * users while still bounding fan-out (each IRI is one SPARQL round-trip).
     */
    private static final int MAX_LIST_IRIS = 50;

    private final NkdSparqlClient nkdSparqlClient;

    @Override
    public GetNkdOntologyDto getOntologyDetail(String iri) {
        ensureEndpointConfigured();
        validateIri(iri);

        Optional<OntologyDetailModel> ontologyDetail;
        try {
            ontologyDetail = nkdSparqlClient.fetchPublishedOntology(iri);
        } catch (RuntimeException e) {
            log.warn("NKD SPARQL error while fetching ontology {}: {}", iri, e.getMessage());
            throw new NkdEndpointException("NKD SPARQL endpoint je nedostupný.", e);
        }

        OntologyDetailModel detail = ontologyDetail.orElseThrow(() -> {
            log.info("Ontology not found in NKD: {}", iri);
            return new NkdResourceNotFoundException("Slovník s IRI " + iri + " nebyl v NKD nalezen.");
        });

        return new GetNkdOntologyDto(detail);
    }

    @Override
    public GetNkdConceptDto getConceptDetail(String iri, String ontologyIri) {
        ensureEndpointConfigured();
        validateIri(iri);
        if (ontologyIri != null && !ontologyIri.isBlank()) {
            validateIri(ontologyIri);
        }

        Optional<OntologyDetailModel.ConceptDetailModel> conceptDetail;
        try {
            conceptDetail = nkdSparqlClient.fetchPublishedConcept(iri);
        } catch (RuntimeException e) {
            log.warn("NKD SPARQL error while fetching concept {}: {}", iri, e.getMessage());
            throw new NkdEndpointException("NKD SPARQL endpoint je nedostupný.", e);
        }

        OntologyDetailModel.ConceptDetailModel detail = conceptDetail.orElseThrow(() -> {
            log.info("Concept not found in NKD: {}", iri);
            return new NkdResourceNotFoundException("Pojem s IRI " + iri + " nebyl v NKD nalezen.");
        });

        String normalizedOntologyIri = (ontologyIri == null || ontologyIri.isBlank()) ? null : ontologyIri;
        return new GetNkdConceptDto(detail, normalizedOntologyIri);
    }

    @Override
    public GetNkdOntologyListDto getOntologyList(List<String> iris) {
        if (iris == null || iris.isEmpty()) {
            return new GetNkdOntologyListDto(List.of());
        }
        if (iris.size() > MAX_LIST_IRIS) {
            throw new IllegalArgumentException(
                    "Příliš mnoho IRI v jedné žádosti (max " + MAX_LIST_IRIS + ", obdrženo " + iris.size() + ").");
        }
        ensureEndpointConfigured();
        // Validate up front so a single bad IRI doesn't waste N-1 SPARQL round-trips
        // before failing. Per-IRI fetch errors are tolerated below; per-IRI shape
        // errors are not (they indicate a client bug, not a remote outage).
        for (String iri : iris) {
            validateIri(iri);
        }

        List<NkdOntologyListItemDto> items = new ArrayList<>(iris.size());
        for (String iri : iris) {
            try {
                Optional<OntologyDetailModel> detail = nkdSparqlClient.fetchPublishedOntology(iri);
                if (detail.isEmpty()) {
                    log.info("NKD ontology not found, skipping in list response: {}", iri);
                    continue;
                }
                items.add(toListItem(detail.get()));
            } catch (RuntimeException e) {
                // Skip-and-continue: a single stale bookmark in the FE's localStorage
                // shouldn't blank the whole "last accessed" tile row.
                log.warn("NKD SPARQL error while fetching ontology {} for list, skipping: {}",
                        iri, e.getMessage());
            }
        }
        return new GetNkdOntologyListDto(items);
    }

    private static NkdOntologyListItemDto toListItem(OntologyDetailModel detail) {
        return NkdOntologyListItemDto.builder()
                .iri(detail.getIri())
                .name(detail.getName())
                .description(detail.getDescription())
                .creationDate(detail.getCreationDate())
                .modificationDate(detail.getModificationDate())
                .build();
    }

    private void ensureEndpointConfigured() {
        if (!nkdSparqlClient.isEndpointConfigured()) {
            throw new NkdEndpointException("NKD SPARQL endpoint není nakonfigurován.");
        }
    }

    private void validateIri(String iri) {
        if (!SparqlIriValidator.isSafeHttpIri(iri)) {
            throw new IllegalArgumentException("IRI není platné http(s) URI: " + iri);
        }
    }
}
