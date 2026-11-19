package com.dia.ismdtoolbackend.service.impl;

import com.dia.ismdtoolbackend.client.NkdSparqlClient;
import com.dia.ismdtoolbackend.controller.dto.GetNkdConceptDto;
import com.dia.ismdtoolbackend.controller.dto.GetNkdOntologyDto;
import com.dia.ismdtoolbackend.controller.dto.GetNkdOntologyListDto;
import com.dia.ismdtoolbackend.controller.dto.MinimalConceptDto;
import com.dia.ismdtoolbackend.enums.SearchSource;
import com.dia.ismdtoolbackend.exception.NkdEndpointException;
import com.dia.ismdtoolbackend.exception.NkdResourceNotFoundException;
import com.dia.ismdtoolbackend.models.OntologyDetailModel;
import com.dia.ismdtoolbackend.service.NkdDetailService;
import com.dia.ismdtoolbackend.service.nkod.NkodCodelistService;
import com.dia.ismdtoolbackend.service.rpp.RppSnapshotHolder;
import com.dia.ismdtoolbackend.utility.exporter.json.JsonExporter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.jena.rdf.model.Model;
import org.springframework.stereotype.Service;

import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class NkdDetailServiceImpl implements NkdDetailService {

    private final NkdSparqlClient nkdSparqlClient;
    private final JsonExporter jsonExporter;
    private final RppSnapshotHolder rppSnapshotHolder;
    private final NkodCodelistService nkodCodelistService;
    private final ReferencedConceptsEnricher referencedConceptsEnricher;
    private final NkdRequestGuard guard;
    private final NkdOntologyListReader ontologyLists;
    private final NkdConceptListReader conceptLists;

    @Override
    public GetNkdOntologyDto getOntologyDetail(String iri) {
        guard.ensureEndpointConfigured();
        guard.validateIri(iri);

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

        // Concepts are loaded eagerly by the CONSTRUCT path (skos:inScheme), so
        // size is the authoritative count for this ontology. No extra round-trip.
        if (detail.getConcepts() != null) {
            detail.setConceptCount(detail.getConcepts().size());
        }

        return new GetNkdOntologyDto(detail);
    }

    @Override
    public byte[] downloadOntology(String iri, String format) {
        guard.ensureEndpointConfigured();
        guard.validateIri(iri);
        if (format == null || format.isBlank()) {
            throw new IllegalArgumentException("Formát stahování musí být zadán (ttl nebo json-ld).");
        }
        String normalized = format.trim().toLowerCase();
        if (!normalized.equals("ttl") && !normalized.equals("json-ld")) {
            throw new IllegalArgumentException("Nepodporovaný formát: " + format + " (povolené: ttl, json-ld).");
        }

        Optional<Model> rawModel;
        try {
            rawModel = nkdSparqlClient.fetchPublishedOntologyRaw(iri);
        } catch (RuntimeException e) {
            log.warn("NKD SPARQL error while fetching ontology for download {}: {}", iri, e.getMessage());
            throw new NkdEndpointException("NKD SPARQL endpoint je nedostupný.", e);
        }

        Model model = rawModel.orElseThrow(() -> {
            log.info("Ontology not found in NKD for download: {}", iri);
            return new NkdResourceNotFoundException("Slovník s IRI " + iri + " nebyl v NKD nalezen.");
        });

        // Not closed: fetchPublishedOntologyRaw is @Cacheable, so this is the shared cached
        // instance — closing it would poison the entry for the rest of its TTL.
        String body;
        if (normalized.equals("ttl")) {
            StringWriter writer = new StringWriter();
            model.write(writer, "TTL");
            body = writer.toString();
        } else {
            // NKD already publishes OFN-aligned RDF. We bypass the local-store
            // OFN re-formatting pipeline (TurtleFilterUtil/TurtleFormatterUtil)
            // because applying it to an already-OFN payload is a noop at best
            // and lossy at worst. JsonExporter is enough.
            body = jsonExporter.exportToJson(model);
        }
        return body.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public GetNkdConceptDto getConceptDetail(String iri, String ontologyIri) {
        guard.ensureEndpointConfigured();
        guard.validateIri(iri);
        if (ontologyIri != null && !ontologyIri.isBlank()) {
            guard.validateIri(ontologyIri);
        }

        Optional<NkdSparqlClient.PublishedConcept> conceptResult;
        try {
            conceptResult = nkdSparqlClient.fetchPublishedConceptWithScheme(iri);
        } catch (RuntimeException e) {
            log.warn("NKD SPARQL error while fetching concept {}: {}", iri, e.getMessage());
            throw new NkdEndpointException("NKD SPARQL endpoint je nedostupný.", e);
        }

        NkdSparqlClient.PublishedConcept published = conceptResult.orElseThrow(() -> {
            log.info("Concept not found in NKD: {}", iri);
            return new NkdResourceNotFoundException("Pojem s IRI " + iri + " nebyl v NKD nalezen.");
        });

        OntologyDetailModel.ConceptDetailModel detail = published.detail();
        // NKD detail context: resolve referenced concepts NKD-only so an IRI that
        // also exists in ISMD stays in the NKD context the user is viewing.
        referencedConceptsEnricher.enrich(detail, SearchSource.NKD);
        resolveRppReferences(detail);
        resolveCodeList(detail);

        // Query param wins (FE supplies it as breadcrumb context); fall back to
        // the skos:inScheme target parsed from the NKD response so the FE has
        // an IRI to deep-link the parent slovník with.
        String resolvedOntologyIri =
                (ontologyIri != null && !ontologyIri.isBlank()) ? ontologyIri : published.ontologyIri();

        return new GetNkdConceptDto(detail, resolvedOntologyIri);
    }

    private void resolveRppReferences(OntologyDetailModel.ConceptDetailModel detail) {
        String agendaIri = detail.getAgenda();
        if (agendaIri != null) {
            rppSnapshotHolder.findAgendaByIri(agendaIri).ifPresent(detail::setAgendaResolved);
        }
        String aisIri = detail.getAis();
        if (aisIri != null) {
            rppSnapshotHolder.findIsvsByIri(aisIri).ifPresent(detail::setAisResolved);
        }
    }

    private void resolveCodeList(OntologyDetailModel.ConceptDetailModel detail) {
        nkodCodelistService.check(detail.getCodeList()).ifPresent(detail::setCodeListResolved);
    }

    @Override
    public GetNkdOntologyListDto getOntologyList(List<String> iris) {
        return ontologyLists.getOntologyList(iris);
    }

    @Override
    public List<MinimalConceptDto> listOntologyConcepts(String ontologyIri) {
        return conceptLists.listOntologyConcepts(ontologyIri);
    }

    @Override
    public GetNkdOntologyListDto listAllOntologies(int limit, int offset, String lang) {
        return ontologyLists.listAllOntologies(limit, offset, lang);
    }
}
