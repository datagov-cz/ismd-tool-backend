package com.dia.ismdtoolbackend.service.impl;

import com.dia.ismdtoolbackend.config.NkdConfig;
import com.dia.ismdtoolbackend.controller.dto.GetOntologyDto;
import com.dia.ismdtoolbackend.controller.dto.OntologyCreateWithConceptsRequestDto;
import com.dia.ismdtoolbackend.controller.dto.OntologyCreateWithConceptsResponseDto;
import com.dia.ismdtoolbackend.controller.dto.OntologyIriCheckRequestDto;
import com.dia.ismdtoolbackend.controller.dto.OntologyIriCheckResponseDto;
import com.dia.ismdtoolbackend.controller.dto.MinimalConceptDto;
import com.dia.ismdtoolbackend.entity.CommentEntity;
import com.dia.ismdtoolbackend.entity.ConceptMetadataEntity;
import com.dia.ismdtoolbackend.entity.OntologyMetadataEntity;
import com.dia.ismdtoolbackend.entity.ValidationReportEntity;
import com.dia.ismdtoolbackend.enums.ConceptType;
import com.dia.ismdtoolbackend.enums.SearchSource;
import com.dia.ismdtoolbackend.exception.OntologyNotFoundException;
import com.dia.ismdtoolbackend.exception.OntologyCreationConflictException;
import com.dia.ismdtoolbackend.exception.ConceptValidationException;
import com.dia.ismdtoolbackend.models.concept.ConceptCreateModel;
import com.dia.ismdtoolbackend.utility.creator.ConceptCreator;
import com.dia.ismdtoolbackend.utility.creator.VocabularyConceptBuilder;
import org.springframework.dao.DataIntegrityViolationException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import com.dia.ismdtoolbackend.exception.OntologyValidationException;
import com.dia.ismdtoolbackend.mapper.ConceptMetadataMapper;
import com.dia.ismdtoolbackend.models.*;
import com.dia.ismdtoolbackend.mapper.OntologyMetadataMapper;
import com.dia.ismdtoolbackend.models.concept.PublishedConceptDeviationModel;
import com.dia.ismdtoolbackend.outbox.OutboxConfig;
import com.dia.ismdtoolbackend.outbox.OutboxEntryRepository;
import com.dia.ismdtoolbackend.outbox.OutboxOperation;
import com.dia.ismdtoolbackend.outbox.OutboxStatus;
import com.dia.ismdtoolbackend.outbox.OutboxTriples;
import com.dia.ismdtoolbackend.utility.creator.ConceptMetadataFactory;
import com.dia.ismdtoolbackend.outbox.OutboxRelayTrigger;
import com.dia.ismdtoolbackend.outbox.OutboxWriter;
import com.dia.ismdtoolbackend.repository.*;
import com.dia.ismdtoolbackend.service.NkdDetailService;
import com.dia.ismdtoolbackend.service.NkdSnapshotService;
import com.dia.ismdtoolbackend.service.OntologyService;
import com.dia.ismdtoolbackend.service.snapshot.NkdSnapshotWarmer;
import com.dia.ismdtoolbackend.utility.detail.OntologyDetailExtractor;
import com.dia.ismdtoolbackend.utility.published.PublishedResourceUtil;
import com.dia.ismdtoolbackend.utility.editor.OntologyEditor;
import com.dia.utility.URIGenerator;
import com.dia.utility.UtilityMethods;
import com.dia.ismdtoolbackend.utility.creator.OntologyModelFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.jena.ontology.OntologyException;
import org.apache.jena.rdf.model.Literal;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.Statement;
import org.apache.jena.vocabulary.DCTerms;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.SKOS;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.StmtIterator;

import static com.dia.constants.ExportConstants.Common.DEFAULT_LANG;
import static com.dia.constants.VocabularyConstants.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class OntologyServiceImpl implements OntologyService {

    private final OntologyMetadataRepository ontologyMetadataRepository;
    private final MetadataTouchService metadataTouchService;
    private final ConceptMetadataRepository conceptMetadataRepository;
    private final ValidationReportRepository validationReportRepository;
    private final JenaTDB2Repository jenaTDB2Repository;
    private final CommentRepository commentRepository;

    private final OntologyMetadataMapper ontologyMetadataMapper;
    private final ConceptMetadataMapper conceptMetadataMapper;
    private final OntologyEditor ontologyEditor;
    private final OntologyDetailExtractor detailExtractor;
    private final PublishedResourceUtil deviationChecker;
    private final OutboxConfig outboxConfig;
    private final OutboxWriter outboxWriter;
    private final OutboxEntryRepository outboxRepository;
    private final OutboxRelayTrigger outboxRelayTrigger;
    private final NkdConceptSnapshotRepository nkdSnapshotRepository;
    private final NkdSnapshotWarmer nkdSnapshotWarmer;
    private final NkdSnapshotService nkdSnapshotService;
    private final NkdConfig nkdConfig;
    private final NkdDetailService nkdDetailService;

    @Override
    @Transactional
    @CacheEvict(cacheNames = {ReferencedConceptResolutionEngine.CACHE_NAME,
            WorkingCopyDeviationServiceImpl.LOCAL_CONCEPT_PROJECTION_CACHE}, allEntries = true)
    public void deleteOntology(Long ontologyId) {
        Optional<OntologyMetadataEntity> ontologyMetadataOpt = ontologyMetadataRepository.findById(ontologyId);
        if (ontologyMetadataOpt.isEmpty()) {
            log.error("ontologyId {} not found", ontologyId);
            throw new OntologyException("Slovník s id " + ontologyId + "nebyl nalezen.");
        }

        String graphName = ontologyMetadataOpt.get().getGraphName();
        requireInitialGraphApplied(graphName);

        Optional<ValidationReportEntity> validationReport =
                validationReportRepository.findByOntologyMetadataId(ontologyId);
        validationReport.ifPresent(validationReportRepository::delete);

        if (!jenaTDB2Repository.graphHasData(graphName)) {
            log.error("Ontology model is empty.");
            throw new OntologyException("Slovník je prázdný, nebo nebyl nalezen.");
        }

        // NKD local-copy cascade: drop the PG snapshot rows before the ontology (and its cascade-deleted
        // concepts) go. Copies live only in Postgres, so there is nothing of theirs in the graph for
        // DELETE_GRAPH to sweep. fk_nkd_snapshot_owning_concept is not db-cascade, so keying on the owned
        // concept ids (not just graph_name, which drifts on rename) is what keeps the concept DELETE legal.
        List<Long> ownedConceptIds = conceptMetadataRepository.findByGraphName(graphName).stream()
                .map(ConceptMetadataEntity::getId)
                .toList();
        nkdSnapshotService.cascadeGraphDeletion(ownedConceptIds, graphName);

        if (outboxConfig.isEnabled()) {
            // Outbox path: enqueue the graph deletion, committed atomically with the PG metadata
            // delete below.
            outboxWriter.enqueueDeleteGraph(graphName);
            ontologyMetadataRepository.deleteById(ontologyId);
            outboxRelayTrigger.nudgeAfterCommit();
            return;
        }

        jenaTDB2Repository.deleteGraph(graphName);
        ontologyMetadataRepository.deleteById(ontologyId);
    }

    @Override
    @Transactional
    @CacheEvict(cacheNames = {ReferencedConceptResolutionEngine.CACHE_NAME,
            WorkingCopyDeviationServiceImpl.LOCAL_CONCEPT_PROJECTION_CACHE}, allEntries = true)
    public OntologyMetadataModel createOntology(OntologyCreateModel ontologyCreateModel, String userId) {
        validateOntologyCreateModel(ontologyCreateModel);

        String ontologyIRI = generateOntologyIri(ontologyCreateModel.getNameModel(), ontologyCreateModel.getNamespace());

        if (!UtilityMethods.isValidIRI(ontologyIRI)) {
            log.error("ontologyIRI {} not valid", ontologyCreateModel.getNameModel().getName());
            throw new OntologyException("IRI slovníku " + ontologyIRI + " není platné.");
        }

        Optional<OntologyMetadataEntity> ontologyMetadataOpt = findLocalCollision(ontologyIRI);
        if (ontologyMetadataOpt.isPresent() && ontologyIRI.equals(ontologyMetadataOpt.get().getGraphName())) {
            log.error("ontologyId {} already present", ontologyIRI);
            OntologyMetadataEntity existingEntity = ontologyMetadataOpt.get();
            OntologyMetadataModel model = ontologyMetadataMapper.toDto(existingEntity);
            var pending = outboxRepository.findFirstByGraphNameAndOperationAndStatusNotOrderBySeqAsc(
                    ontologyIRI, OutboxOperation.CREATE_GRAPH, OutboxStatus.DONE);
            if (pending.isPresent()) {
                Model initial = OutboxTriples.parse(pending.get().getInsertTriples());
                try {
                    enrichMetadataFromModel(model, existingEntity, initial);
                } finally {
                    initial.close();
                }
            } else {
                enrichMetadataFromRDF(model, existingEntity);
            }
            return model;
        }

        requireCreationOutbox();
        if (ontologyMetadataOpt.isPresent()) {
            throw new OntologyCreationConflictException("Slovník s tímto IRI nebo identifikátorem již existuje: " + ontologyIRI);
        }
        Model graphModel = OntologyModelFactory.create(ontologyIRI, ontologyCreateModel);
        try {
            OntologyMetadataEntity metadataEntity;
            try {
                metadataEntity = createOntologyMetadata(ontologyIRI, userId);
                ontologyMetadataRepository.flush();
            } catch (Exception e) {
                throw new OntologyException("Nepodařilo se uložit metadata slovníku: " + e.getMessage());
            }
            outboxWriter.enqueueCreateGraph(ontologyIRI, graphModel);
            outboxRelayTrigger.nudgeAfterCommit();
            OntologyMetadataModel result = ontologyMetadataMapper.toDto(metadataEntity);
            enrichMetadataFromModel(result, metadataEntity, graphModel);
            return result;
        } finally {
            graphModel.close();
        }
    }

    @Override
    @Transactional
    @CacheEvict(cacheNames = {ReferencedConceptResolutionEngine.CACHE_NAME,
            WorkingCopyDeviationServiceImpl.LOCAL_CONCEPT_PROJECTION_CACHE}, allEntries = true)
    public OntologyCreateWithConceptsResponseDto createWithConcepts(OntologyCreateWithConceptsRequestDto request, String userId) {
        validateOntologyCreateModel(request.ontology());
        if (userId == null || userId.isBlank()) throw new OntologyValidationException("ID uživatele je povinné.");
        String graph = generateOntologyIri(request.ontology().getNameModel(), request.ontology().getNamespace());
        if (!UtilityMethods.isValidIRI(graph)) throw new OntologyValidationException("Neplatné IRI slovníku: " + graph);
        requireCreationOutbox();
        if (findLocalCollision(graph).isPresent()) {
            throw new OntologyCreationConflictException("Slovník s tímto IRI nebo identifikátorem již existuje: " + graph);
        }

        var prepared = VocabularyConceptBuilder.prepare(request, graph, userId);
        for (String iri : prepared.conceptIris().values()) {
            if (conceptMetadataRepository.findByConceptIri(iri).isPresent()) {
                throw new ConceptValidationException("Pojem s tímto IRI již existuje: " + iri);
            }
        }
        // Build the whole graph with the existing creators before the first write.
        // ConceptCreator holds a mutable model, so this batch uses its own instance.
        Model graphModel = OntologyModelFactory.create(graph, request.ontology());
        try {
            ConceptCreator creator = new ConceptCreator();
            for (ConceptCreateModel concept : prepared.concepts()) {
                Resource resource = creator.createSingleConcept(concept);
                graphModel.add(resource.getModel());
                resource.getModel().close();
                creator.getOntModel().close();
            }
            // Metadata and the serialized graph task commit together. The unique slug arbitrates concurrent creates.
            OntologyMetadataEntity ontology;
            try {
                ontology = createOntologyMetadata(graph, userId);
                ontologyMetadataRepository.flush();
                saveDraftConceptMetadata(prepared.concepts(), ontology, userId);
                conceptMetadataRepository.flush();
            } catch (DataIntegrityViolationException e) {
                throw new OntologyCreationConflictException("IRI nebo identifikátor slovníku či pojmu již existuje.");
            }

            OntologyMetadataModel result = ontologyMetadataMapper.toDto(ontology);
            Resource vocabulary = graphModel.getResource(graph);
            result.setName(extractMultilingualValue(vocabulary, SKOS.prefLabel));
            result.setPopis(extractMultilingualValue(vocabulary, DCTerms.description));
            result.setConceptCount(prepared.concepts().size());

            outboxWriter.enqueueCreateGraph(graph, graphModel);
            outboxRelayTrigger.nudgeAfterCommit();
            return new OntologyCreateWithConceptsResponseDto(result, prepared.conceptIris());
        } finally {
            graphModel.close();
        }
    }

    private void saveDraftConceptMetadata(List<ConceptCreateModel> concepts, OntologyMetadataEntity ontology, String userId) {
        Set<String> slugs = new HashSet<>();
        List<ConceptMetadataEntity> entities = new ArrayList<>();
        for (ConceptCreateModel concept : concepts) {
            ConceptMetadataEntity entity = ConceptMetadataFactory.create(concept, concept.getIdentifier(), userId,
                    ontology, slug -> conceptMetadataRepository.findBySlug(slug).isPresent(), slugs);
            entities.add(entity);
        }
        conceptMetadataRepository.saveAll(entities);
    }

    private void requireCreationOutbox() {
        if (!outboxConfig.isEnabled()) {
            throw new OntologyException("Vytvoření slovníku vyžaduje zapnutý outbox (outbox.enabled=true).");
        }
    }

    // Direct PUT/rename/delete cannot race a retry of the initial PUT, including after a restart
    // with outbox disabled. Seeing DONE means the relay's transaction and its row lock completed.
    private void requireInitialGraphApplied(String graph) {
        if (outboxRepository.existsEarlierUnappliedCreateGraph(graph, Long.MAX_VALUE)) {
            throw new OntologyCreationConflictException("Počáteční zápis slovníku ještě nebyl dokončen: " + graph);
        }
    }

    private Optional<OntologyMetadataEntity> findLocalCollision(String graph) {
        return ontologyMetadataRepository.findByGraphName(graph)
                .or(() -> ontologyMetadataRepository.findBySlug(UtilityMethods.extractNameFromIRI(graph)));
    }

    @Override
    @Transactional(readOnly = true)
    public OntologyDetailModel getOntologyDetail(String ontologySlug) {
        return loadOntologyDetail(ontologySlug).detailModel();
    }

    @Override
    @Transactional(readOnly = true)
    public GetOntologyDto getOntologyDetailModel(String ontologySlug) {
        LoadedOntologyDetail loadedDetail = loadOntologyDetail(ontologySlug);
        OntologyMetadataEntity metadataEntity = loadedDetail.metadataEntity();
        Model rawModel = loadedDetail.rawModel();
        Model processedModel = loadedDetail.processedModel();
        OntologyDetailModel detailModel = loadedDetail.detailModel();

        OntologyMetadataModel metadataModel = ontologyMetadataMapper.toDto(metadataEntity);

        enrichMetadataFromModel(metadataModel, metadataEntity, rawModel);

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

    private LoadedOntologyDetail loadOntologyDetail(String ontologySlug) {
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
    private void applyForeignMemberCounts(OntologyDetailModel detailModel, String graphName) {
        List<OntologyDetailModel.ConceptDetailModel> concepts = detailModel.getConcepts();
        if (concepts == null || concepts.isEmpty()) {
            return;
        }

        try {
            List<String> conceptIris = concepts.stream()
                    .map(OntologyDetailModel.ConceptDetailModel::getIri)
                    .filter(Objects::nonNull)
                    .toList();

            Map<String, JenaTDB2Repository.ForeignMemberCount> counts =
                    jenaTDB2Repository.countExternalDomainMembers(graphName, conceptIris);
            if (counts.isEmpty()) {
                return;
            }

            for (OntologyDetailModel.ConceptDetailModel concept : concepts) {
                JenaTDB2Repository.ForeignMemberCount count = counts.get(concept.getIri());
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
    private void surfaceLinkSnapshots(GetOntologyDto result, String graphName) {
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

    @Override
    @Transactional(readOnly = true)
    public List<MinimalConceptDto> getConceptsByIri(String ontologyIri, SearchSource source) {
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

    private List<MinimalConceptDto> getIsmdConceptsByIri(String ontologyIri) {
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

    private List<MinimalConceptDto> getNkdConceptsByIri(String ontologyIri) {
        // One targeted SELECT for the three fields this projection keeps. The full ontology detail
        // would CONSTRUCT every concept's whole triple set and OFN-transform it, then discard all
        // but iri/name/conceptType.
        return nkdDetailService.listOntologyConcepts(ontologyIri);
    }

    @Override
    @Transactional(readOnly = true)
    public OntologyIriCheckResponseDto checkIri(OntologyIriCheckRequestDto request) {
        validateOntologyName(request.nameModel());
        String iri = generateOntologyIri(request.nameModel(), request.namespace());
        boolean valid = UtilityMethods.isValidIRI(iri);
        boolean available = valid && findLocalCollision(iri).isEmpty();
        return new OntologyIriCheckResponseDto(iri, valid, available);
    }

    private String generateOntologyIri(NameModel nameModel, String namespace) {
        return new URIGenerator().generateVocabularyURIFromGivenNamespace(getNameForUriGeneration(nameModel), namespace);
    }

    private void validateOntologyName(NameModel nameModel) {
        Map<String, String> name = nameModel != null ? nameModel.getName() : null;
        if (name == null || name.isEmpty()) {
            throw new OntologyValidationException("Název slovníku je povinný.");
        }
        if (isBlank(name.get(DEFAULT_LANG))) {
            throw new OntologyValidationException("Název slovníku musí obsahovat českou variantu (cs).");
        }
    }

    private void validateOntologyCreateModel(OntologyCreateModel model) {
        if (model == null) {
            throw new OntologyException("Data pro vytvoření slovníku jsou prázdná");
        }

        validateOntologyName(model.getNameModel());

        // description is optional, but if present it must include a non-blank cs variant
        Map<String, String> description = model.getDescriptionModel() != null
                ? model.getDescriptionModel().getDescription() : null;
        if (description != null && !description.isEmpty()
                && description.values().stream().anyMatch(v -> !isBlank(v))
                && isBlank(description.get(DEFAULT_LANG))) {
            throw new OntologyValidationException("Popis slovníku musí obsahovat českou variantu (cs).");
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private OntologyMetadataEntity createOntologyMetadata(String ontologyIRI, String userId) {
        OntologyMetadataEntity metadataEntity = new OntologyMetadataEntity();
        String slug = UtilityMethods.extractNameFromIRI(ontologyIRI);
        metadataEntity.setSlug(slug);
        metadataEntity.setGraphName(ontologyIRI);
        metadataEntity.setUserId(userId);
        metadataEntity.setIsPublished(false);

        return ontologyMetadataRepository.save(metadataEntity);
    }

    @Override
    @Transactional
    @CacheEvict(cacheNames = {ReferencedConceptResolutionEngine.CACHE_NAME,
            WorkingCopyDeviationServiceImpl.LOCAL_CONCEPT_PROJECTION_CACHE}, allEntries = true)
    public OntologyMetadataModel editOntology(Long id, OntologyEditModel ontologyEditModel) {
        if (ontologyEditModel == null) {
            throw new OntologyException("Data pro úpravu slovníku jsou prázdná");
        }
        OntologyMetadataEntity metadataEntity = fetchOntologyMetadata(id);
        String oldOntologyIRI = metadataEntity.getGraphName();
        requireInitialGraphApplied(oldOntologyIRI);
        Model model = fetchOntologyModel(oldOntologyIRI);

        String oldNamespace = UtilityMethods.ensureNamespaceEndsWithDelimiter(oldOntologyIRI);
        OntologyEditor.EditResult editResult = performOntologyEdit(ontologyEditModel, model, oldNamespace, metadataEntity.getGraphName());

        log.info("Ontology edit completed: IRI changed={}", editResult.iriChanged);

        if (editResult.iriChanged) {
            metadataEntity = handleOntologyIRIChange(oldOntologyIRI, editResult.newOntologyIRI, model, metadataEntity);
        } else {
            saveOntologyModel(oldOntologyIRI, model);
        }

        // Both branches bump updatedAt: the IRI-change branch dirties graphName, but an edit that only
        // touches RDF (name, description) leaves every mapped column identical, so the touch is what
        // makes updatedAt move at all.
        metadataTouchService.touchOntology(metadataEntity);

        OntologyMetadataModel resultModel = ontologyMetadataMapper.toDto(metadataEntity);
        enrichMetadataFromRDF(resultModel, metadataEntity);
        return resultModel;
    }

    @Override
    @Transactional(readOnly = true)
    public List<OntologyMetadataModel> getAll(String userId, Boolean isPublished) {
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

        return enrichEntitiesWithBatchMetadata(ontologyMetadataEntities);
    }

    @Override
    @Transactional(readOnly = true)
    public List<OntologyMetadataModel> getBySlugs(List<String> slugs) {
        if (slugs == null || slugs.isEmpty()) {
            throw new OntologyException("Seznam slugů je prázdný");
        }

        if (slugs.size() > 6) {
            throw new OntologyException("Maximální počet slugů je 6");
        }

        List<OntologyMetadataEntity> ontologyMetadataEntities = ontologyMetadataRepository.findBySlugIn(slugs);

        return enrichEntitiesWithBatchMetadata(ontologyMetadataEntities);
    }

    private List<OntologyMetadataModel> enrichEntitiesWithBatchMetadata(List<OntologyMetadataEntity> entities) {
        if (entities.isEmpty()) {
            return List.of();
        }

        List<String> graphNames = entities.stream()
                .map(OntologyMetadataEntity::getGraphName)
                .filter(g -> g != null && !g.isEmpty())
                .toList();

        Model batchMetadata = jenaTDB2Repository.fetchMetadataProperties(graphNames);
        Map<String, Model> perGraphModels = partitionModelBySubject(batchMetadata, graphNames);

        // One comment query for the whole page, grouped in memory — a per-entity lookup here would
        // undo the batched RDF fetch above with 1+N queries on an unfiltered list.
        List<Long> ontologyIds = entities.stream()
                .map(OntologyMetadataEntity::getId)
                .toList();
        Map<Long, List<CommentEntity>> commentsByOntologyId = ontologyIds.isEmpty()
                ? Map.of()
                : commentRepository.findByOntologyMetadataIdIn(ontologyIds).stream()
                        .collect(java.util.stream.Collectors.groupingBy(c -> c.getOntologyMetadata().getId()));

        return entities.stream()
                .map(entity -> {
                    OntologyMetadataModel model = ontologyMetadataMapper.toDto(entity);
                    Model graphModel = perGraphModels.get(entity.getGraphName());
                    enrichMetadataFromModel(model, entity, graphModel);
                    List<CommentEntity> commentEntities =
                            commentsByOntologyId.getOrDefault(entity.getId(), List.of());
                    model.setComments(ontologyMetadataMapper.commentEntitiesToModels(commentEntities));
                    return model;
                })
                .toList();
    }

    private Map<String, Model> partitionModelBySubject(Model batchModel, List<String> graphNames) {
        Map<String, Model> result = new HashMap<>();
        for (String graphName : graphNames) {
            Resource subject = batchModel.getResource(graphName);
            Model subModel = ModelFactory.createDefaultModel();
            StmtIterator stmts = batchModel.listStatements(subject, null, (org.apache.jena.rdf.model.RDFNode) null);
            while (stmts.hasNext()) {
                subModel.add(stmts.next());
            }
            result.put(graphName, subModel);
        }
        return result;
    }

    @Override
    public String getTtlContentFromOntology(OntologyMetadataModel ontologyMetadataModel) {
        try {
            String graphName = ontologyMetadataModel.getGraphName();

            if (graphName == null || graphName.isEmpty()) {
                log.error("Graph name is null or empty for ontology: {}", ontologyMetadataModel.getSlug());
                throw new OntologyException("Graph name is missing for the ontology");
            }

            log.info("Fetching TTL content for graph: {}", graphName);

            Model model = jenaTDB2Repository.fetchGraph(graphName);

            if (model == null || model.isEmpty()) {
                log.warn("Model is empty or null for graph: {}", graphName);
                throw new OntologyException("Ontology model not found or is empty");
            }

            java.io.StringWriter writer = new java.io.StringWriter();
            model.write(writer, "TTL");
            String ttlContent = writer.toString();

            log.info("Successfully retrieved TTL content for graph: {} ({} statements)",
                    graphName, model.size());

            return ttlContent;

        } catch (OntologyException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error retrieving TTL content from ontology: {}", e.getMessage(), e);
            throw new OntologyException("Failed to retrieve TTL content: " + e.getMessage());
        }
    }

    @Override
    @Transactional(readOnly = true)
    public OntologyMetadataModel getOntologyMetadata(Long ontologyId) {
        return ontologyMetadataMapper.toDto(ontologyMetadataRepository.findById(ontologyId).orElseThrow());
    }

    @Override
    @Transactional(readOnly = true)
    public OntologyMetadataModel getOntologyMetadataBySlug(String ontologySlug) {
        return ontologyMetadataRepository.findBySlug(ontologySlug)
                .map(ontologyMetadataMapper::toDto)
                .orElseThrow(() -> {
                    log.error("ontologySlug {} not found", ontologySlug);
                    return new OntologyNotFoundException(
                            "Metadata slovníku s názvem " + ontologySlug + " nebyla nalezena.");
                });
    }

    private String extractNameFromGraphName(String graphName) {
        if (graphName == null || graphName.isEmpty()) {
            return graphName;
        }

        String result = graphName.replace("-", " ");

        result = result.substring(0, 1).toUpperCase() + result.substring(1);

        return result;
    }

    private OntologyMetadataEntity fetchOntologyMetadata(Long id) {
        Optional<OntologyMetadataEntity> ontologyMetadataOpt = ontologyMetadataRepository.findById(id);
        if (ontologyMetadataOpt.isEmpty()) {
            log.error("Ontology with ID {} not found", id);
            throw new OntologyException("Slovník s ID " + id + " nebyl nalezen.");
        }
        return ontologyMetadataOpt.get();
    }

    private Model fetchOntologyModel(String ontologyIRI) throws OntologyException {
        try {
            Model model = jenaTDB2Repository.fetchGraph(ontologyIRI);
            if (model.isEmpty()) {
                log.error("Ontology model is empty for IRI: {}", ontologyIRI);
                throw new OntologyException("Model slovníku je prázdný nebo nebyl nalezen.");
            }
            return model;
        } catch (Exception e) {
            log.error("Failed to fetch ontology model for IRI: {}. Error: {}", ontologyIRI, e.getMessage());
            throw new OntologyException("Slovník s IRI " + ontologyIRI + " nebyl nalezen v úložišti. " +
                    "Je možné, že došlo k nesrovnalosti mezi databází a RDF úložištěm. " +
                    "Zkontrolujte, zda slovník existuje v Fuseki.");
        }
    }

    private OntologyEditor.EditResult performOntologyEdit(OntologyEditModel editModel, Model model, String oldNamespace, String iri) {
        return ontologyEditor.editOntology(editModel, model, oldNamespace, iri);
    }

    private OntologyMetadataEntity handleOntologyIRIChange(String oldOntologyIRI, String newOntologyIRI,
                                                           Model model, OntologyMetadataEntity metadataEntity)
            throws OntologyException {
        requireInitialGraphApplied(newOntologyIRI);
        log.info("Starting ontology IRI change: {} -> {}", oldOntologyIRI, newOntologyIRI);

        try {
            saveOntologyModel(newOntologyIRI, model);
            log.info("Successfully saved ontology to new graph: {}", newOntologyIRI);
        } catch (Exception e) {
            log.error("Failed to save new graph to Fuseki: {}", e.getMessage());
            throw new OntologyException("Nepodařilo se uložit nový graf do Fuseki: " + e.getMessage());
        }

        try {
            String oldNamespace = UtilityMethods.ensureNamespaceEndsWithDelimiter(oldOntologyIRI);

            updateConceptMetadataIRIs(metadataEntity.getId(), newOntologyIRI, oldNamespace);
            log.info("Successfully updated concept metadata in SQL");

            OntologyMetadataEntity updatedEntity = updateOntologyMetadata(metadataEntity, newOntologyIRI);
            log.info("Successfully updated ontology metadata in SQL");

            try {
                deleteOntologyGraph(oldOntologyIRI);
                log.info("Successfully deleted old graph: {}", oldOntologyIRI);
            } catch (Exception e) {
                log.warn("Failed to delete old graph {}, but operation succeeded. Manual cleanup may be needed: {}",
                        oldOntologyIRI, e.getMessage());
            }

            return updatedEntity;
        } catch (Exception e) {
            log.error("Failed to update SQL, rolling back. Attempting to clean up new graph: {}", newOntologyIRI);

            try {
                deleteOntologyGraph(newOntologyIRI);
                log.info("Successfully cleaned up new graph after SQL failure: {}", newOntologyIRI);
            } catch (Exception cleanupException) {
                log.error("Failed to cleanup new graph {} after SQL failure. Manual cleanup required: {}",
                        newOntologyIRI, cleanupException.getMessage());
            }

            throw new OntologyException("Nepodařilo se aktualizovat databázi: " + e.getMessage());
        }
    }

    private void updateConceptMetadataIRIs(Long ontologyMetadataId, String newGraphName,
                                           String oldNamespace) {
        List<ConceptMetadataEntity> concepts = conceptMetadataRepository.findByOntologyMetadataId(ontologyMetadataId);

        if (concepts.isEmpty()) {
            log.info("No concepts found for ontology ID {}, skipping concept metadata updates", ontologyMetadataId);
            return;
        }

        log.info("Updating {} concept metadata entries for ontology namespace change", concepts.size());

        URIGenerator uriGenerator = new URIGenerator();
        uriGenerator.setEffectiveNamespace(newGraphName);

        int updatedIRICount = 0;
        int updatedGraphNameOnlyCount = 0;

        for (ConceptMetadataEntity concept : concepts) {
            String oldConceptIRI = concept.getConceptIri();
            String conceptName = concept.getConceptName();

            if (oldConceptIRI != null && conceptName != null && oldConceptIRI.startsWith(oldNamespace)) {
                String newConceptIRI = uriGenerator.generateConceptURI(conceptName, null);
                concept.setConceptIri(newConceptIRI);
                concept.setGraphName(newGraphName);
                log.debug("Updated concept IRI and graphName: {} -> {}", oldConceptIRI, newConceptIRI);
                updatedIRICount++;
            } else {
                concept.setGraphName(newGraphName);
                log.debug("Updated graphName only for concept with custom identifier: {}", oldConceptIRI);
                updatedGraphNameOnlyCount++;
            }
        }

        conceptMetadataRepository.saveAll(concepts);
        log.info("Successfully updated {} concept IRIs and {} graphNames for concepts with custom identifiers",
                 updatedIRICount, updatedGraphNameOnlyCount);
    }

    private void saveOntologyModel(String ontologyIRI, Model model) {
        try {
            jenaTDB2Repository.saveOntologyModel(ontologyIRI, model);
            log.info("Saved/updated ontology model for graph: {}", ontologyIRI);
        } catch (Exception e) {
            log.error("Failed to save ontology model: {}", e.getMessage());
            throw new OntologyException("Nepodařilo se uložit změny slovníku: " + e.getMessage());
        }
    }

    private void deleteOntologyGraph(String ontologyIRI) {
        jenaTDB2Repository.deleteGraph(ontologyIRI);
    }

    private OntologyMetadataEntity updateOntologyMetadata(OntologyMetadataEntity metadataEntity, String newGraphName) {
        metadataEntity.setGraphName(newGraphName);
        OntologyMetadataEntity updatedEntity = ontologyMetadataRepository.save(metadataEntity);
        log.info("Updated metadata with new graph name: {} (slug unchanged: {})", newGraphName, metadataEntity.getSlug());
        return updatedEntity;
    }

    private void cleanupTDB2Graph(String graphName) {
        jenaTDB2Repository.deleteGraph(graphName);
    }

    private void enrichMetadataFromRDF(OntologyMetadataModel model, OntologyMetadataEntity entity) {
        String graphName = entity.getGraphName();
        if (graphName == null || graphName.isEmpty()) {
            log.warn("Cannot enrich metadata: graphName is null or empty");
            return;
        }

        try {
            Model rdfModel = jenaTDB2Repository.fetchMetadataProperties(graphName);
            enrichMetadataFromModel(model, entity, rdfModel);
        } catch (Exception e) {
            log.warn("Failed to enrich metadata from RDF for graph {}: {}", graphName, e.getMessage());
            if ((model.getName() == null || model.getName().isEmpty())) {
                model.setName(fallbackNameFromGraphName(graphName));
            }
        }
    }

    private void enrichMetadataFromModel(OntologyMetadataModel model, OntologyMetadataEntity entity, Model rdfModel) {
        String graphName = entity.getGraphName();
        Map<String, String> fallbackName = fallbackNameFromGraphName(graphName);

        try {
            if (rdfModel == null || rdfModel.isEmpty()) {
                log.warn("Cannot enrich metadata: RDF model is empty for graph {}", graphName);
                model.setName(fallbackName);
                return;
            }

            Resource ontologyResource = rdfModel.getResource(graphName);
            if (ontologyResource == null) {
                log.warn("Cannot find ontology resource for IRI: {}", graphName);
                model.setName(fallbackName);
                return;
            }

            Map<String, String> names = extractMultilingualValue(ontologyResource, SKOS.prefLabel);
            if (names.isEmpty()) {
                model.setName(fallbackName);
            } else {
                model.setName(names);
                log.debug("Enriched name from skos:prefLabel with {} language variant(s)", names.size());
            }

            Map<String, String> descriptions = extractMultilingualValue(ontologyResource, DCTerms.description);
            if (!descriptions.isEmpty()) {
                model.setPopis(descriptions);
                log.debug("Enriched popis from dcterms:description with {} language variant(s)", descriptions.size());
            }
        } catch (Exception e) {
            log.warn("Failed to enrich metadata from model for graph {}: {}", graphName, e.getMessage());
            if (model.getName() == null || model.getName().isEmpty()) {
                model.setName(fallbackName);
            }
        }
    }

    private Map<String, String> fallbackNameFromGraphName(String graphName) {
        String derived = extractNameFromGraphName(UtilityMethods.extractNameFromIRI(graphName));
        return derived == null || derived.isEmpty() ? Map.of() : Map.of(DEFAULT_LANG, derived);
    }

    private Map<String, String> extractMultilingualValue(Resource resource, Property property) {
        Map<String, String> valuesByLang = new LinkedHashMap<>();
        StmtIterator iter = resource.listProperties(property);
        while (iter.hasNext()) {
            Statement stmt = iter.next();
            RDFNode object = stmt.getObject();
            if (!object.isLiteral()) {
                continue;
            }
            Literal literal = object.asLiteral();
            String value = literal.getString();
            if (value == null || value.trim().isEmpty()) {
                continue;
            }
            String lang = literal.getLanguage();
            valuesByLang.putIfAbsent((lang != null && !lang.isEmpty()) ? lang : DEFAULT_LANG, value);
        }
        return valuesByLang;
    }

    private String getNameForUriGeneration(com.dia.ismdtoolbackend.models.NameModel nameModel) {
        if (nameModel == null || nameModel.getName() == null || nameModel.getName().isEmpty()) {
            return "";
        }
        Map<String, String> names = nameModel.getName();
        if (names.containsKey(DEFAULT_LANG)) {
            return names.get(DEFAULT_LANG);
        }
        return names.values().iterator().next();
    }
}
