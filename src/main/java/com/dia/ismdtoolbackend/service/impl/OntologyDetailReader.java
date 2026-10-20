package com.dia.ismdtoolbackend.service.impl;

import com.dia.ismdtoolbackend.config.NkdConfig;
import com.dia.ismdtoolbackend.controller.dto.GetOntologyDto;
import com.dia.ismdtoolbackend.controller.dto.MinimalConceptDto;
import com.dia.ismdtoolbackend.entity.CommentEntity;
import com.dia.ismdtoolbackend.entity.ConceptMetadataEntity;
import com.dia.ismdtoolbackend.entity.OntologyMetadataEntity;
import com.dia.ismdtoolbackend.enums.ConceptType;
import com.dia.ismdtoolbackend.enums.SearchSource;
import com.dia.ismdtoolbackend.exception.OntologyNotFoundException;
import com.dia.ismdtoolbackend.mapper.ConceptMetadataMapper;
import com.dia.ismdtoolbackend.models.*;
import com.dia.ismdtoolbackend.mapper.OntologyMetadataMapper;
import com.dia.ismdtoolbackend.models.concept.PublishedConceptDeviationModel;
import com.dia.ismdtoolbackend.repository.*;
import com.dia.ismdtoolbackend.service.NkdDetailService;
import com.dia.ismdtoolbackend.service.snapshot.NkdSnapshotWarmer;
import com.dia.ismdtoolbackend.utility.detail.OntologyDetailExtractor;
import com.dia.ismdtoolbackend.utility.published.PublishedResourceUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.jena.ontology.OntologyException;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.vocabulary.RDF;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** Reads a vocabulary's detail: structure, deviations, foreign member counts, link snapshots and concept lists. */
@Slf4j
@Component
@RequiredArgsConstructor
class OntologyDetailReader {

    private final OntologyMetadataRepository ontologyMetadataRepository;
    private final ConceptMetadataRepository conceptMetadataRepository;
    private final JenaTDB2Repository jenaTDB2Repository;
    private final CommentRepository commentRepository;
    private final OntologyMetadataMapper ontologyMetadataMapper;
    private final ConceptMetadataMapper conceptMetadataMapper;
    private final OntologyDetailExtractor detailExtractor;
    private final PublishedResourceUtil deviationChecker;
    private final NkdConceptSnapshotRepository nkdSnapshotRepository;
    private final NkdSnapshotWarmer nkdSnapshotWarmer;
    private final NkdConfig nkdConfig;
    private final NkdDetailService nkdDetailService;
    private final OntologyMetadataEnricher metadataEnricher;

    OntologyDetailModel getOntologyDetail(String ontologySlug) {
        return loadOntologyDetail(ontologySlug).detailModel();
    }

    GetOntologyDto getOntologyDetailModel(String ontologySlug) {
        LoadedOntologyDetail loadedDetail = loadOntologyDetail(ontologySlug);
        OntologyMetadataEntity metadataEntity = loadedDetail.metadataEntity();
        Model rawModel = loadedDetail.rawModel();
        Model processedModel = loadedDetail.processedModel();
        OntologyDetailModel detailModel = loadedDetail.detailModel();

        OntologyMetadataModel metadataModel = ontologyMetadataMapper.toDto(metadataEntity);

        metadataEnricher.enrichMetadataFromModel(metadataModel, metadataEntity, rawModel);

        List<CommentEntity> commentEntities = commentRepository.findByOntologyMetadataId(metadataEntity.getId());
        metadataModel.setComments(ontologyMetadataMapper.commentEntitiesToModels(commentEntities));

        String graphName = metadataEntity.getGraphName();
        List<ConceptMetadataEntity> conceptMetadataEntities = conceptMetadataRepository.findByGraphName(graphName);
        List<com.dia.ismdtoolbackend.models.concept.ConceptMetadataModel> conceptModels =
                conceptMetadataEntities.stream().map(conceptMetadataMapper::toDto).toList();
        metadataModel.setConcepts(conceptModels);

        GetOntologyDto result = new GetOntologyDto();
        // Single source of truth: conceptCount on both projections of the
        // ontology comes from the same authoritative PG list we just loaded.
        int conceptCount = conceptMetadataEntities.size();
        metadataModel.setConceptCount(conceptCount);
        detailModel.setConceptCount(conceptCount);
        result.setOntologyMetadata(metadataModel);
        result.setOntologyDetail(detailModel);

        PublishedOntologyDeviationModel ontologyDeviations = deviationChecker.checkOntologyDeviation(detailModel, metadataModel);
        result.setPublishedOntologyDeviationModel(ontologyDeviations);

        Map<String, PublishedConceptDeviationModel> conceptDeviations = deviationChecker.checkConceptsDeviation(processedModel, conceptMetadataEntities);
        result.setPublishedConceptDeviations(conceptDeviations);

        surfaceLinkSnapshots(result, graphName);

        return result;
    }

    LoadedOntologyDetail loadOntologyDetail(String ontologySlug) {
        Optional<OntologyMetadataEntity> ontologyMetadataOpt = ontologyMetadataRepository.findBySlug(ontologySlug);
        if (ontologyMetadataOpt.isEmpty()) {
            log.error("ontologySlug {} not found", ontologySlug);
            throw new OntologyNotFoundException("Metadata slovníku s názvem " + ontologySlug + " nebyla nalezena.");
        }

        OntologyMetadataEntity metadataEntity = ontologyMetadataOpt.get();
        String graphName = metadataEntity.getGraphName();

        Model rawModel = jenaTDB2Repository.fetchGraph(graphName);

        if (rawModel.isEmpty()) {
            log.error("Ontology model is empty for graph: {}", graphName);
            throw new OntologyNotFoundException("Slovník je prázdný, nebo nebyl nalezen.");
        }

        // OFN transform is expensive (filter + reformat over the full graph);
        // run once and share with the deviation checker instead of re-running
        // it three times across detail extraction and deviation checks.
        Model processedModel = detailExtractor.applyOFNTransformations(rawModel);
        OntologyDetailModel detailModel = detailExtractor.extractOntologyDetail(processedModel);
        applyForeignMemberCounts(detailModel, graphName);
        return new LoadedOntologyDetail(metadataEntity, rawModel, processedModel, detailModel);
    }

    /**
     * Annotates each concept with how many properties/relationships point at it from other
     * vocabularies. The detail's own member lists are scoped to this graph, so without the
     * counts an ontology detail silently shows fewer members than the same concept's detail
     * page (which merges cross-graph members in).
     *
     * <p>One batched query for the whole ontology — a per-concept call would reintroduce the
     * N+1 fan-out the read-path work removed. Never lets counting break ontology detail.
     */
    void applyForeignMemberCounts(OntologyDetailModel detailModel, String graphName) {
        List<OntologyDetailModel.ConceptDetailModel> concepts = detailModel.getConcepts();
        if (concepts == null || concepts.isEmpty()) {
            return;
        }

        try {
            List<String> conceptIris = concepts.stream()
                    .map(OntologyDetailModel.ConceptDetailModel::getIri)
                    .filter(Objects::nonNull)
                    .toList();

            Map<String, ForeignMemberCount> counts =
                    jenaTDB2Repository.countExternalDomainMembers(graphName, conceptIris);
            if (counts.isEmpty()) {
                return;
            }

            for (OntologyDetailModel.ConceptDetailModel concept : concepts) {
                ForeignMemberCount count = counts.get(concept.getIri());
                if (count != null) {
                    concept.setForeignPropertyCount(count.properties());
                    concept.setForeignRelationshipCount(count.relationships());
                }
            }
        } catch (Exception e) {
            log.warn("Failed to count cross-graph members for graph {}: {}", graphName, e.getMessage());
        }
    }

    private record LoadedOntologyDetail(
            OntologyMetadataEntity metadataEntity,
            Model rawModel,
            Model processedModel,
            OntologyDetailModel detailModel
    ) {
    }

    /**
     * Builds {@code linkSnapshots} from cached snapshot rows (no NKD call — the snapshot row IS the
     * cache) and triggers the async warmer when cold/stale. The read never writes; the warmer runs on its
     * own thread/transaction. Never lets snapshot surfacing break ontology detail.
     */
    void surfaceLinkSnapshots(GetOntologyDto result, String graphName) {
        try {
            List<com.dia.ismdtoolbackend.entity.NkdConceptSnapshotEntity> rows =
                    nkdSnapshotRepository.findByGraphName(graphName);

            com.dia.ismdtoolbackend.service.snapshot.LinkSnapshotAssembler.Result assembled =
                    com.dia.ismdtoolbackend.service.snapshot.LinkSnapshotAssembler.assemble(
                            rows, nkdConfig.getSnapshot().getDeviationTtl(), java.time.Instant.now());

            if (!assembled.byOwnerConcept().isEmpty()) {
                result.setLinkSnapshots(assembled.byOwnerConcept());
            }

            // Warm when a row is cold/stale, OR when there are no rows yet (true cold start — the
            // graph may have NKD links never snapshotted). Warm path is async; detail returns now.
            //
            // Accepted tradeoff: an ontology whose external links are NOT published in NKD has zero
            // rows forever, so it re-scans on every detail load. The scan is in-memory only — the NKD
            // batch call is gated behind finding external candidates — so a truly link-free ontology
            // pays nothing across the wire. A per-graph "last warmed" marker (skip re-scan within TTL)
            // is the steady-state optimization, deferred as a follow-up.
            if (assembled.needsWarming() || rows.isEmpty()) {
                nkdSnapshotWarmer.warmGraph(graphName);
            }
        } catch (Exception e) {
            log.warn("Failed to surface NKD link snapshots for graph {}: {}", graphName, e.getMessage(), e);
        }
    }

    List<MinimalConceptDto> getConceptsByIri(String ontologyIri, SearchSource source) {
        if (ontologyIri == null || ontologyIri.isBlank()) {
            throw new OntologyException("IRI slovníku musí být zadáno.");
        }
        if (source == null) {
            throw new OntologyException("Zdroj (source) musí být zadán.");
        }

        return switch (source) {
            case ISMD -> getIsmdConceptsByIri(ontologyIri);
            case NKD -> getNkdConceptsByIri(ontologyIri);
            default -> throw new IllegalArgumentException(
                    "Nepodporovaný zdroj: " + source + ". Povolené hodnoty: ISMD, NKD.");
        };
    }

    List<MinimalConceptDto> getIsmdConceptsByIri(String ontologyIri) {
        Optional<OntologyMetadataEntity> ontologyMetadataOpt = ontologyMetadataRepository.findByGraphName(ontologyIri);
        if (ontologyMetadataOpt.isEmpty()) {
            log.info("ISMD ontology not found for IRI: {}", ontologyIri);
            throw new OntologyNotFoundException("Slovník s IRI " + ontologyIri + " nebyl nalezen.");
        }

        Model rawModel = jenaTDB2Repository.fetchGraph(ontologyIri);
        if (rawModel.isEmpty()) {
            throw new OntologyNotFoundException("Slovník je prázdný, nebo nebyl nalezen.");
        }

        Model processedModel = detailExtractor.applyOFNTransformations(rawModel);
        OntologyDetailModel detailModel = detailExtractor.extractOntologyDetail(processedModel);
        List<OntologyDetailModel.ConceptDetailModel> concepts = detailModel.getConcepts();
        if (concepts == null || concepts.isEmpty()) {
            return List.of();
        }

        // Slug lives in PG, not in the RDF graph — join by conceptIri so the FE
        // can deep-link via /concept/{slug} (local-only navigation key).
        Map<String, String> slugByIri = conceptMetadataRepository.findByGraphName(ontologyIri).stream()
                .filter(e -> e.getConceptIri() != null && e.getSlug() != null)
                .collect(java.util.stream.Collectors.toMap(
                        ConceptMetadataEntity::getConceptIri,
                        ConceptMetadataEntity::getSlug,
                        (a, b) -> a));

        return concepts.stream()
                .map(c -> MinimalConceptDto.builder()
                        .iri(c.getIri())
                        .slug(slugByIri.get(c.getIri()))
                        .name(c.getName())
                        .conceptType(ConceptType.fromRdfTypes(c.getTypes()))
                        .build())
                .toList();
    }

    List<MinimalConceptDto> getNkdConceptsByIri(String ontologyIri) {
        // One targeted SELECT for the three fields this projection keeps. The full ontology detail
        // would CONSTRUCT every concept's whole triple set and OFN-transform it, then discard all
        // but iri/name/conceptType.
        return nkdDetailService.listOntologyConcepts(ontologyIri);
    }
}
