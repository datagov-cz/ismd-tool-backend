package com.dia.ismdtoolbackend.service.impl;

import com.dia.ismdtoolbackend.client.NkdSparqlClient;
import com.dia.ismdtoolbackend.controller.dto.DataTypeDto;
import com.dia.ismdtoolbackend.controller.dto.ResolvedConceptDto;
import com.dia.ismdtoolbackend.entity.ConceptMetadataEntity;
import com.dia.ismdtoolbackend.enums.SnapshotOrigin;
import com.dia.ismdtoolbackend.models.OntologyDetailModel.ConceptDetailModel;
import com.dia.ismdtoolbackend.models.concept.PublishedConceptDeviationModel;
import com.dia.ismdtoolbackend.models.concept.PublishedConceptDeviationModel.DeviationStatus;
import com.dia.ismdtoolbackend.models.concept.PublishedConceptDeviationModel.PropertyDeviation;
import com.dia.ismdtoolbackend.models.rpp.RppAgenda;
import com.dia.ismdtoolbackend.models.rpp.RppIsvs;
import com.dia.ismdtoolbackend.repository.ConceptMetadataRepository;
import com.dia.ismdtoolbackend.repository.JenaTDB2Repository;
import com.dia.ismdtoolbackend.service.WorkingCopyDeviationService;
import com.dia.ismdtoolbackend.service.rpp.RppSnapshotHolder;
import com.dia.ismdtoolbackend.utility.detail.OntologyDetailExtractor;
import com.dia.ismdtoolbackend.enums.PropertyDataType;
import lombok.extern.slf4j.Slf4j;
import org.apache.jena.rdf.model.Model;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Single source of truth for a working copy's deviation against its NKD twin, so ontology detail and
 * concept detail can never show conflicting results.
 *
 * <p>The deviation is derived from two inputs, and each is cached independently rather than the
 * deviation result itself (a two-tier decomposition): the <b>canonical local projection</b>
 * ({@link #canonicalLocalConcept}, this cache) and the <b>NKD projection</b>
 * ({@link NkdSparqlClient#fetchPublishedConcept}). Both surfaces read the same two cached inputs and run
 * the cheap comparison live, so they produce identical output by construction — there is no cached
 * result to fall out of sync.
 */
@Service
@Slf4j
public class WorkingCopyDeviationServiceImpl implements WorkingCopyDeviationService {

    public static final String LOCAL_CONCEPT_PROJECTION_CACHE = "workingCopyLocalProjection";

    private final ConceptMetadataRepository conceptMetadataRepository;
    private final JenaTDB2Repository jenaTDB2Repository;
    private final OntologyDetailExtractor detailExtractor;
    private final ConceptDeviationComparator conceptDeviationComparator;
    private final NkdSparqlClient nkdSparqlClient;
    private final ReferencedConceptResolutionEngine resolutionEngine;
    private final RppSnapshotHolder rppSnapshotHolder;

    // Self-reference through the Spring proxy so canonicalLocalConcept's @Cacheable is honoured when called from deviationFor
    private final WorkingCopyDeviationService self;

    public WorkingCopyDeviationServiceImpl(ConceptMetadataRepository conceptMetadataRepository,
                                           JenaTDB2Repository jenaTDB2Repository,
                                           OntologyDetailExtractor detailExtractor,
                                           ConceptDeviationComparator conceptDeviationComparator,
                                           NkdSparqlClient nkdSparqlClient,
                                           ReferencedConceptResolutionEngine resolutionEngine,
                                           RppSnapshotHolder rppSnapshotHolder,
                                           @Lazy WorkingCopyDeviationService self) {
        this.conceptMetadataRepository = conceptMetadataRepository;
        this.jenaTDB2Repository = jenaTDB2Repository;
        this.detailExtractor = detailExtractor;
        this.conceptDeviationComparator = conceptDeviationComparator;
        this.nkdSparqlClient = nkdSparqlClient;
        this.resolutionEngine = resolutionEngine;
        this.rppSnapshotHolder = rppSnapshotHolder;
        this.self = self;
    }

    /**
     * The working-copy deviation for {@code conceptIri}: compare the canonical local projection against
     * the live (cached) NKD twin. Both inputs are cached, the comparison is live
     */
    @Override
    public PublishedConceptDeviationModel deviationFor(String conceptIri) {
        ConceptDetailModel local = self.canonicalLocalConcept(conceptIri);
        if (local == null) {
            return error(DeviationStatus.QUERY_ERROR, "Local concept detail not available", conceptIri);
        }
        try {
            Optional<ConceptDetailModel> publishedOpt = nkdSparqlClient.fetchPublishedConcept(conceptIri);
            if (publishedOpt.isEmpty()) {
                log.warn("Published concept not found in NKD: {}", conceptIri);
                return error(DeviationStatus.CONCEPT_NOT_FOUND_IN_NKD,
                        "Concept not found in NKD SPARQL endpoint", conceptIri);
            }
            // WORKING_COPY: this concept's own IRI is the NKD twin it is compared against.
            PublishedConceptDeviationModel deviation = conceptDeviationComparator.compareConceptDetails(
                    local, publishedOpt.get(), SnapshotOrigin.WORKING_COPY, conceptIri);
            enrichResolved(deviation);
            return deviation;
        } catch (Exception e) {
            log.error("Error checking working-copy deviation for {}: {}", conceptIri, e.getMessage(), e);
            return error(DeviationStatus.ENDPOINT_UNAVAILABLE,
                    "NKD SPARQL endpoint unavailable: " + e.getMessage(), conceptIri);
        }
    }

    /**
     * The one canonical way the local side of a working-copy comparison is read: the concept's graph,
     * OFN-transformed, then extracted. Cached per {@code conceptIri} so repeated deviation checks share it.
     * Returns {@code null} when the concept has no metadata row or its graph is empty.
     */
    @Override
    @Cacheable(cacheNames = LOCAL_CONCEPT_PROJECTION_CACHE, key = "#conceptIri")
    public ConceptDetailModel canonicalLocalConcept(String conceptIri) {
        Optional<ConceptMetadataEntity> metadataOpt = conceptMetadataRepository.findByConceptIri(conceptIri);
        if (metadataOpt.isEmpty()) {
            return null;
        }
        String graphName = metadataOpt.get().getGraphName();
        Model rawModel = jenaTDB2Repository.fetchGraph(graphName);
        if (rawModel.isEmpty()) {
            return null;
        }
        Model processedModel = detailExtractor.applyOFNTransformations(rawModel);
        return detailExtractor.extractConceptDetail(processedModel, conceptIri);
    }

    /**
     * Attaches resolved navigation metadata to a deviation so the FE can render/navigate its concept
     * references (source twin, domain, concept-typed range) and RPP references (agenda, ais) — on BOTH
     * sides of every diff — without a second resolve round-trip. Mirrors the detail flow's resolved shapes.
     */
    private void enrichResolved(PublishedConceptDeviationModel deviation) {
        if (deviation == null) {
            return;
        }

        // Concept IRIs: the source twin + domain + concept-typed range, both diff sides. One batch resolve.
        Set<String> conceptIris = new LinkedHashSet<>();
        if (deviation.getSource() != null) {
            addIri(conceptIris, deviation.getSource().getIri());
        }
        addBothSides(conceptIris, deviation.getDomain());

        // Range: datatype values resolve to DataTypeDto; anything else is a concept IRI → resolve as a concept.
        Map<String, DataTypeDto> rangeResolved = new LinkedHashMap<>();
        collectRange(deviation.getRange(), conceptIris, rangeResolved);
        if (!rangeResolved.isEmpty()) {
            deviation.setRangeResolved(rangeResolved);
        }

        if (!conceptIris.isEmpty()) {
            Map<String, ResolvedConceptDto> resolved = resolutionEngine.resolveAll(new ArrayList<>(conceptIris));
            if (!resolved.isEmpty()) {
                deviation.setReferencedConceptsResolved(resolved);
            }
        }

        // RPP agenda / ais, both diff sides.
        Map<String, RppAgenda> agendaResolved = new LinkedHashMap<>();
        forEachSide(deviation.getAgenda(), iri ->
                rppSnapshotHolder.findAgendaByIri(iri).ifPresent(a -> agendaResolved.put(iri, a)));
        if (!agendaResolved.isEmpty()) {
            deviation.setAgendaResolved(agendaResolved);
        }

        Map<String, RppIsvs> aisResolved = new LinkedHashMap<>();
        forEachSide(deviation.getAis(), iri ->
                rppSnapshotHolder.findIsvsByIri(iri).ifPresent(i -> aisResolved.put(iri, i)));
        if (!aisResolved.isEmpty()) {
            deviation.setAisResolved(aisResolved);
        }
    }

    /** A range value is a DataTypeDto when it's a known XSD datatype (VLASTNOST); otherwise a concept IRI. */
    private void collectRange(PropertyDeviation<String> range, Set<String> conceptIris,
                              Map<String, DataTypeDto> rangeResolved) {
        forEachSide(range, value -> {
            Optional<PropertyDataType> datatype = PropertyDataType.fromValue(value);
            if (datatype.isPresent()) {
                rangeResolved.put(value, datatype.get().toDto());
            } else {
                addIri(conceptIris, value);
            }
        });
    }

    /** Runs {@code action} for each non-blank side (local, published) of a string deviation. */
    private void forEachSide(PropertyDeviation<String> deviation, java.util.function.Consumer<String> action) {
        if (deviation == null) {
            return;
        }
        applyIfPresent(deviation.getLocalValue(), action);
        applyIfPresent(deviation.getPublishedValue(), action);
    }

    private void applyIfPresent(String value, java.util.function.Consumer<String> action) {
        if (value != null && !value.isBlank()) {
            action.accept(value);
        }
    }

    private void addBothSides(Set<String> sink, PropertyDeviation<String> deviation) {
        forEachSide(deviation, v -> addIri(sink, v));
    }

    private void addIri(Set<String> sink, String iri) {
        if (iri != null && !iri.isBlank()) {
            sink.add(iri);
        }
    }

    private PublishedConceptDeviationModel error(DeviationStatus status, String message, String conceptIri) {
        return PublishedConceptDeviationModel.builder()
                .status(status)
                .origin(SnapshotOrigin.WORKING_COPY)
                .source(com.dia.ismdtoolbackend.controller.dto.NkdConceptRefDto.builder().iri(conceptIri).build())
                .errorMessage(message)
                .build();
    }
}