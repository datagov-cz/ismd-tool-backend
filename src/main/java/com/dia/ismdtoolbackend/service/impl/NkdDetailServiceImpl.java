package com.dia.ismdtoolbackend.service.impl;

import com.dia.ismdtoolbackend.client.NkdSparqlClient;
import com.dia.ismdtoolbackend.controller.dto.GetNkdConceptDto;
import com.dia.ismdtoolbackend.controller.dto.GetNkdOntologyDto;
import com.dia.ismdtoolbackend.exception.NkdEndpointException;
import com.dia.ismdtoolbackend.exception.NkdResourceNotFoundException;
import com.dia.ismdtoolbackend.models.OntologyDetailModel;
import com.dia.ismdtoolbackend.service.NkdDetailService;
import com.dia.ismdtoolbackend.utility.security.SparqlIriValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class NkdDetailServiceImpl implements NkdDetailService {

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
