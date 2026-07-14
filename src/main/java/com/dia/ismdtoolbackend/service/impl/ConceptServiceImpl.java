package com.dia.ismdtoolbackend.service.impl;

import com.dia.ismdtoolbackend.client.NkdSparqlClient;
import com.dia.ismdtoolbackend.controller.dto.GetConceptDto;
import com.dia.ismdtoolbackend.entity.CommentEntity;
import com.dia.ismdtoolbackend.entity.ConceptMetadataEntity;
import com.dia.ismdtoolbackend.entity.OntologyMetadataEntity;
import com.dia.ismdtoolbackend.exception.ConceptValidationException;
import com.dia.ismdtoolbackend.models.OntologyDetailModel;
import com.dia.ismdtoolbackend.models.concept.ConceptCreateModel;
import com.dia.ismdtoolbackend.models.concept.ConceptEditModel;
import com.dia.ismdtoolbackend.models.concept.ConceptMetadataModel;
import com.dia.ismdtoolbackend.mapper.ConceptMetadataMapper;
import com.dia.ismdtoolbackend.models.concept.PublishedConceptDeviationModel;
import com.dia.ismdtoolbackend.repository.CommentRepository;
import com.dia.ismdtoolbackend.repository.ConceptMetadataRepository;
import com.dia.ismdtoolbackend.repository.JenaTDB2Repository;
import com.dia.ismdtoolbackend.repository.OntologyMetadataRepository;
import com.dia.ismdtoolbackend.service.ConceptService;
import com.dia.ismdtoolbackend.service.NkdSnapshotService;
import com.dia.ismdtoolbackend.service.rpp.RppSnapshotHolder;
import com.dia.ismdtoolbackend.service.snapshot.NkdLinkDetector;
import com.dia.ismdtoolbackend.service.snapshot.OwnerChangeSet;
import com.dia.ismdtoolbackend.entity.NkdConceptSnapshotEntity;
import com.dia.ismdtoolbackend.exception.OntologyValidationException;
import com.dia.ismdtoolbackend.outbox.OutboxConfig;
import com.dia.ismdtoolbackend.outbox.OutboxRelayTrigger;
import com.dia.ismdtoolbackend.outbox.OutboxWriter;
import com.dia.ismdtoolbackend.utility.creator.ConceptCreator;
import com.dia.ismdtoolbackend.utility.detail.OntologyDetailExtractor;
import com.dia.ismdtoolbackend.utility.editor.ConceptEditor;
import com.dia.utility.UtilityMethods;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.jena.ontology.OntologyException;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.Statement;
import org.apache.jena.rdf.model.StmtIterator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class ConceptServiceImpl implements ConceptService {

    private final ConceptMetadataRepository conceptMetadataRepository;
    private final OntologyMetadataRepository ontologyMetadataRepository;
    private final ConceptMetadataMapper conceptMetadataMapper;
    private final ConceptCreator conceptCreator;
    private final ConceptEditor conceptEditor;
    private final JenaTDB2Repository jenaTDB2Repository;
    private final OntologyDetailExtractor detailExtractor;
    private final CommentRepository commentRepository;
    private final NkdSparqlClient nkdSparqlClient;
    private final ConceptDeviationComparator deviationComparator;
    private final RppSnapshotHolder rppSnapshotHolder;
    private final ReferencedConceptsEnricher referencedConceptsEnricher;
    private final OutboxConfig outboxConfig;
    private final OutboxWriter outboxWriter;
    private final OutboxRelayTrigger outboxRelayTrigger;
    private final NkdSnapshotService nkdSnapshotService;
    private final NkdLinkDetector nkdLinkDetector;

    @Override
    @Transactional
    public ConceptMetadataModel createConcept(ConceptCreateModel createModel, String userId) {
        log.info("Creating concept: type={}, name={}, namespace={}, userId={}",
                createModel.getConceptType(), createModel.getNameModel(),
                createModel.getNamespace(), userId);

        validateInput(createModel, userId);

        Resource conceptResource = createConceptResource(createModel);
        String conceptUri = conceptResource.getURI();

        ConceptMetadataModel existingConcept = checkForExistingConcept(conceptUri);
        if (existingConcept != null) {
            return existingConcept;
        }

        String ontologyGraphName = createModel.getOntologyGraphName();

        if (outboxConfig.isEnabled()) {
            // Outbox path: enqueue the concept's triples (empty remove set), committed atomically
            // with the metadata below. No rollback compensator needed — if the PG tx fails, the
            // outbox row rolls back with it, so nothing reaches TDB2.
            outboxWriter.enqueueUpsert(ontologyGraphName, conceptUri, java.util.Set.of(),
                    conceptResource.getModel().listStatements().toSet());
            ConceptMetadataEntity savedEntity = saveMetadata(createModel, userId, conceptUri);
            outboxRelayTrigger.nudgeAfterCommit();
            return conceptMetadataMapper.toDto(savedEntity);
        }

        saveConceptToTDB2(conceptResource, ontologyGraphName);
        return saveMetadataWithRollback(createModel, userId, conceptUri, ontologyGraphName);
    }

    @Override
    @Transactional
    public void deleteConcept(Long conceptId) {
        // On the outbox path, take a row lock FIRST (see ConceptMetadataRepository.findWithLockById)
        // so a concurrent edit/delete of the same concept can't enqueue an out-of-order same-aggregate
        // outbox row. Direct path keeps the plain findById (byte-for-byte unchanged when flag off).
        Optional<ConceptMetadataEntity> conceptMetadataOpt = outboxConfig.isEnabled()
                ? conceptMetadataRepository.findWithLockById(conceptId)
                : conceptMetadataRepository.findById(conceptId);
        if (conceptMetadataOpt.isEmpty()) {
            log.error("conceptId {} not found", conceptId);
            throw new OntologyException("Metadata pojmu s id " + conceptId + "nebyla nalezena.");
        }

        String graphName = conceptMetadataOpt.get().getGraphName();
        String conceptUri = conceptMetadataOpt.get().getConceptIri();

        if (!jenaTDB2Repository.graphHasData(graphName)) {
            log.error("Ontology model is empty.");
            throw new OntologyException("Slovník, ve kterém se pojem nachází, je prázdný, nebo nebyl nalezen.");
        }

        if (jenaTDB2Repository.conceptNotFoundInGraph(conceptUri, graphName)) {
            log.error("Concept resource {} not found in graph {}", conceptUri, graphName);
            throw new OntologyException("Pojem s IRI " + conceptUri + " nebyl nalezen.");
        }

        List<String> relatedConceptUris = jenaTDB2Repository.findRelatedConceptUris(conceptUri, graphName);
        relatedConceptUris.add(conceptUri);
        List<ConceptMetadataEntity> relatedConceptEntities = findRelatedConceptEntities(relatedConceptUris);

        // NKD local-copy cascade: drop the PG snapshot rows for the deleted concepts. The copy lives
        // only in PG, so there is nothing to add to the TDB2 delete-sweep.
        List<Long> deletedConceptIds = relatedConceptEntities.stream()
                .map(ConceptMetadataEntity::getId)
                .toList();
        nkdSnapshotService.cascadeConceptDeletion(deletedConceptIds, graphName);

        if (outboxConfig.isEnabled()) {
            // Outbox path: enqueue the TDB2 deletion (keyed on the concept being deleted), committed
            // atomically with the PG metadata delete below.
            outboxWriter.enqueueDeleteConcepts(graphName, conceptUri, relatedConceptUris);
            conceptMetadataRepository.deleteAll(relatedConceptEntities);
            outboxRelayTrigger.nudgeAfterCommit();
            return;
        }

        jenaTDB2Repository.deleteConceptsFromGraph(relatedConceptUris, graphName);
        conceptMetadataRepository.deleteAll(relatedConceptEntities);
    }

    @Override
    @Transactional
    public ConceptMetadataModel editConcept(Long conceptId, ConceptEditModel conceptEditModel) {
        log.info("Editing concept: ID={}, type={}",
                conceptId, conceptEditModel.getConceptType());

        // On the outbox path, take a row lock FIRST so two concurrent edits of the same concept are
        // serialized — see ConceptMetadataRepository.findWithLockById. The lock must be the first DB
        // read of the critical section (read graph → compute delta → enqueue), so it spans the whole
        // window in which a concurrent edit could enqueue an out-of-order same-aggregate outbox row.
        ConceptMetadataEntity metadata = fetchAndValidateMetadata(conceptId, outboxConfig.isEnabled());
        String graphName = metadata.getGraphName();

        Model model = fetchAndValidateGraph(graphName);
        validateConceptInGraph(metadata.getConceptIri(), graphName, model);

        // Aggregate key = the PRE-EDIT IRI (metadata.getConceptIri() before the reconcile mutates it),
        // NOT the new IRI. A createConcept keys on the concept's IRI; this concept's pre-edit IRI equals
        // that same IRI, so a create and a subsequent rename share an aggregate and the per-aggregate
        // ordering gate relates them (the rename's DELETE of old-IRI triples can never apply before the
        // create's INSERT of them). Keying on the NEW IRI would make them different aggregates and reopen
        // the create→rename inversion (review #4 / M1).
        String aggregateIri = metadata.getConceptIri();

        ConceptEditor.EditResult editResult = performConceptEdit(aggregateIri, conceptEditModel, model, graphName);

        // Reconcile NKD links and union the resulting link delta with the editor's, so a dropped link's
        // edge removal rides the same owner-keyed aggregate as the edit. The snapshot service reads
        // owner.getConceptIri(), so the metadata IRI is set to its post-edit value first. reconcileNkdLinks
        // returns its own mutable set (EditResult's are immutable copies), which we merge — re-using
        // EditResult's sets would throw. (Snapshot copies live only in PG; nothing copy-related is in the delta.)
        if (editResult.iriChanged) {
            metadata.setConceptIri(editResult.newConceptIRI);
        }
        OwnerChangeSet snapshotDelta = reconcileNkdLinks(metadata, model);
        Set<Statement> toRemove = new HashSet<>(editResult.statementsToRemove);
        toRemove.addAll(snapshotDelta.toRemove);
        Set<Statement> toAdd = new HashSet<>(editResult.statementsToAdd);
        toAdd.addAll(snapshotDelta.toAdd);

        if (outboxConfig.isEnabled()) {
            // Outbox path: enqueue the merged change set (editor delta ∪ NKD copy delta), NOT a
            // whole-graph PUT, committed atomically with the metadata update below.
            outboxWriter.enqueueUpsert(graphName, aggregateIri, toRemove, toAdd);
            updateMetadataFromEditResult(metadata, conceptEditModel, editResult);
            outboxRelayTrigger.nudgeAfterCommit();
            return saveAndReturnMetadata(metadata, editResult.newConceptIRI);
        }

        // Direct (outbox-disabled) path: the editor already applied its delta to `model`. Apply the merged
        // delta (editor ∪ reconcile link removals) so any dropped-link edge removal reaches TDB2 in the
        // same write. Idempotent — re-applying the editor's own triples is a no-op.
        model.remove(new ArrayList<>(toRemove));
        model.add(new ArrayList<>(toAdd));
        saveUpdatedModelToTDB2(graphName, model);
        updateMetadataFromEditResult(metadata, conceptEditModel, editResult);
        return saveAndReturnMetadata(metadata, editResult.newConceptIRI);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ConceptMetadataModel> getAll(String userId, Boolean isPublished) {
        List<ConceptMetadataEntity> conceptMetadataEntities;

        if (userId != null && isPublished != null) {
            conceptMetadataEntities = conceptMetadataRepository.findAllByUserIdAndIsPublished(userId, isPublished);
        } else if (userId != null) {
            conceptMetadataEntities = conceptMetadataRepository.findAllByUserId(userId);
        } else if (isPublished != null) {
            conceptMetadataEntities = conceptMetadataRepository.findAllByIsPublished(isPublished);
        } else {
            conceptMetadataEntities = conceptMetadataRepository.findAll();
        }

        List<Long> conceptIds = conceptMetadataEntities.stream()
                .map(ConceptMetadataEntity::getId)
                .toList();
        Map<Long, List<CommentEntity>> commentsByConceptId = conceptIds.isEmpty()
                ? Map.of()
                : commentRepository.findByConceptMetadataIdIn(conceptIds).stream()
                        .collect(Collectors.groupingBy(c -> c.getConceptMetadata().getId()));

        return conceptMetadataEntities.stream()
                .map(entity -> {
                    ConceptMetadataModel model = conceptMetadataMapper.toDto(entity);
                    List<CommentEntity> commentEntities =
                            commentsByConceptId.getOrDefault(entity.getId(), List.of());
                    model.setComments(conceptMetadataMapper.commentEntitiesToModels(commentEntities));
                    return model;
                })
                .toList();
    }

    @Override
    public GetConceptDto getConceptDetail(String conceptSlug) {
        Optional<ConceptMetadataEntity> conceptMetadataOpt = conceptMetadataRepository.findBySlug(conceptSlug);
        if (conceptMetadataOpt.isEmpty()) {
            log.error("conceptSlug {} not found", conceptSlug);
            throw new OntologyException("Metadata pojmu s názvem " + conceptSlug + " nebyla nalezena.");
        }

        ConceptMetadataEntity metadataEntity = conceptMetadataOpt.get();
        String graphName = metadataEntity.getGraphName();
        String conceptIri = metadataEntity.getConceptIri();

        Model rawModel = jenaTDB2Repository.fetchGraph(graphName);

        if (rawModel.isEmpty()) {
            log.error("Ontology model is empty for graph: {}", graphName);
            throw new OntologyException("Slovník je prázdný, nebo nebyl nalezen.");
        }

        // The class-detail read traverses only this concept's own graph, so a
        // property/relationship whose rdfs:domain points here but which lives in a
        // different vocabulary graph would be dropped (it is still visible from the
        // property's own detail). Merge those cross-graph members in so the class
        // shows them too. Additive only — safe for the downstream deviation check.
        rawModel.add(jenaTDB2Repository.fetchExternalDomainMembers(conceptIri));

        OntologyDetailModel.ConceptDetailModel conceptDetail = detailExtractor.extractConceptDetail(rawModel, conceptIri);

        if (conceptDetail == null) {
            log.error("Concept detail not found for IRI: {}", conceptIri);
            throw new OntologyException("Detail pojmu s IRI " + conceptIri + " nebyl nalezen.");
        }

        referencedConceptsEnricher.enrich(conceptDetail);
        resolveRppReferences(conceptDetail);

        ConceptMetadataModel metadataModel = conceptMetadataMapper.toDto(metadataEntity);

        List<CommentEntity> commentEntities = commentRepository.findByConceptMetadataId(metadataEntity.getId());
        metadataModel.setComments(conceptMetadataMapper.commentEntitiesToModels(commentEntities));

        GetConceptDto result = new GetConceptDto();
        result.setConceptMetadata(metadataModel);
        result.setConceptDetail(conceptDetail);

        PublishedConceptDeviationModel conceptDeviation = checkPublishedConcept(conceptDetail, metadataModel);
        result.setPublishedConceptDeviationModel(conceptDeviation);

        return result;
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

    protected ConceptMetadataEntity saveMetadata(ConceptCreateModel createModel,
                                                 String userId,
                                                 String conceptUri) {
        ConceptMetadataEntity entity = createMetadataEntity(createModel, userId, conceptUri);
        ConceptMetadataEntity savedEntity = conceptMetadataRepository.save(entity);

        log.debug("Saved concept metadata: id={}, name={}, type={}, iri={}",
                savedEntity.getId(), savedEntity.getConceptName(),
                savedEntity.getConceptType(), savedEntity.getConceptIri());

        return savedEntity;
    }

    private List<ConceptMetadataEntity> findRelatedConceptEntities(List<String> conceptUris) {
        List<ConceptMetadataEntity> relatedConcepts = new ArrayList<>();
        for (String conceptUri : conceptUris) {
            conceptMetadataRepository.findByConceptIri(conceptUri).ifPresent(relatedConcepts::add);
        }
        return relatedConcepts;
    }

    private void validateInput(ConceptCreateModel createModel, String userId) {
        if (createModel == null) {
            throw new OntologyException("Data pro vytvoření pojmu jsou prázdná");
        }

        if (userId == null || userId.trim().isEmpty()) {
            throw new OntologyException("ID uživatele je povinné");
        }

        // name is required and must include a non-blank cs variant
        Map<String, String> name = createModel.getNameModel() != null
                ? createModel.getNameModel().getName() : null;
        if (name == null || name.isEmpty()) {
            throw new ConceptValidationException("Název pojmu je povinný.");
        }
        if (isBlankValue(name.get("cs"))) {
            throw new ConceptValidationException("Název pojmu musí obsahovat českou variantu (cs).");
        }

        // description is optional, but if present it must include a non-blank cs variant
        Map<String, String> description = createModel.getDescriptionModel() != null
                ? createModel.getDescriptionModel().getDescription() : null;
        if (hasAnyValue(description) && isBlankValue(description.get("cs"))) {
            throw new ConceptValidationException("Popis pojmu musí obsahovat českou variantu (cs).");
        }

        // definition is optional, but if present it must include a non-blank cs variant
        Map<String, String> definition = createModel.getDefinitionModel() != null
                ? createModel.getDefinitionModel().getDefinition() : null;
        if (hasAnyValue(definition) && isBlankValue(definition.get("cs"))) {
            throw new ConceptValidationException("Definice pojmu musí obsahovat českou variantu (cs).");
        }
    }

    private static boolean isBlankValue(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static boolean hasAnyValue(Map<String, String> map) {
        return map != null && !map.isEmpty() && map.values().stream().anyMatch(v -> !isBlankValue(v));
    }

    private ConceptMetadataEntity createMetadataEntity(ConceptCreateModel createModel,
                                                       String userId,
                                                       String conceptIri) {
        String ontologyGraphName = createModel.getOntologyGraphName();
        OntologyMetadataEntity ontologyMetadata = ontologyMetadataRepository
                .findByGraphName(ontologyGraphName)
                .orElseThrow(() -> {
                    log.error("Ontology metadata not found for graph: {}", ontologyGraphName);
                    return new OntologyException("Slovník s názvem " + ontologyGraphName + " nebyl nalezen.");
                });

        String baseSlug = UtilityMethods.extractNameFromIRI(ontologyGraphName) + "-" + UtilityMethods.extractNameFromIRI(conceptIri);
        String slug = baseSlug;
        int counter = 1;

        while (conceptMetadataRepository.findBySlug(slug).isPresent()) {
            slug = baseSlug + "-" + counter;
            counter++;
        }

        ConceptMetadataEntity entity = new ConceptMetadataEntity();
        entity.setSlug(slug);
        entity.setConceptName(getNameForMetadata(createModel.getNameModel()));
        entity.setConceptType(createModel.getConceptTypeEnum());
        entity.setConceptIri(conceptIri);
        entity.setGraphName(createModel.getOntologyGraphName());
        entity.setUserId(userId);
        entity.setIsPublished(false);
        entity.setInTezaurus(createModel.getInTezaurus());
        entity.setOntologyMetadata(ontologyMetadata);

        return entity;
    }

    private ConceptMetadataEntity fetchAndValidateMetadata(Long conceptId) {
        return fetchAndValidateMetadata(conceptId, false);
    }

    private ConceptMetadataEntity fetchAndValidateMetadata(Long conceptId, boolean lock) {
        Optional<ConceptMetadataEntity> metadataOpt = lock
                ? conceptMetadataRepository.findWithLockById(conceptId)
                : conceptMetadataRepository.findById(conceptId);
        if (metadataOpt.isEmpty()) {
            log.error("Concept metadata not found for ID: {}", conceptId);
            throw new OntologyException("Metadata pojmu s ID " + conceptId + " nebyla nalezena.");
        }
        return metadataOpt.get();
    }

    private Model fetchAndValidateGraph(String graphName) {
        Model model = jenaTDB2Repository.fetchGraph(graphName);
        if (model.isEmpty()) {
            log.error("Graph {} is empty or not found", graphName);
            throw new OntologyException("Slovník " + graphName + " je prázdný nebo nebyl nalezen.");
        }
        return model;
    }

    private void validateConceptInGraph(String conceptIRI, String graphName, Model model) {
        Resource conceptResource = model.getResource(conceptIRI);
        if (conceptResource == null || !model.containsResource(conceptResource)) {
            log.error("Concept {} not found in graph {}", conceptIRI, graphName);
            throw new OntologyException("Pojem s IRI " + conceptIRI + " nebyl nalezen ve slovníku.");
        }
    }

    /**
     * Reconciles this concept's links to published NKD concepts against its local-copy snapshots and
     * returns the materialized-copy delta as a fresh mutable {@link OwnerChangeSet} for the caller to
     * merge into the owner aggregate's flush. Operates on the post-edit {@code model} and owner IRI,
     * inside the edit transaction.
     *
     * <p>Returns its own change set rather than mutating {@code EditResult}'s, whose sets are immutable
     * copies. Best-effort: a transient NKD outage never rolls back the edit (the service skips); only a
     * confirmed-published domain/range link throws (HTTP 400).
     */
    private OwnerChangeSet reconcileNkdLinks(ConceptMetadataEntity owner, Model model) {
        String ownerIri = owner.getConceptIri();
        String graphScheme = owner.getGraphName();
        OwnerChangeSet ownerChangeSet = new OwnerChangeSet();

        List<String> domainRangeTargets =
                nkdLinkDetector.forbiddenDomainRangeTargets(ownerIri, graphScheme, model);
        if (!domainRangeTargets.isEmpty()) {
            Set<String> publishedForbidden = publishedAmong(domainRangeTargets);
            if (!publishedForbidden.isEmpty()) {
                throw new OntologyValidationException(
                        "Definiční obor / obor hodnot nesmí odkazovat na publikovaný pojem v NKD: "
                                + publishedForbidden);
            }
        }

        List<NkdLinkDetector.LinkTarget> allowed =
                nkdLinkDetector.allowedTargets(ownerIri, owner.getConceptType(), graphScheme, model);
        Set<String> currentTargetIris = new HashSet<>();
        allowed.forEach(t -> currentTargetIris.add(t.targetIri()));

        List<NkdConceptSnapshotEntity> existing = nkdSnapshotService.findForConcept(owner.getId());
        if (!existing.isEmpty()) {
            Set<Statement> ownerOutgoing = outgoingStatements(model, ownerIri);
            for (NkdConceptSnapshotEntity snapshot : existing) {
                if (!currentTargetIris.contains(snapshot.getNkdIri())) {
                    nkdSnapshotService.removeSnapshotAndLink(snapshot, ownerOutgoing, ownerChangeSet);
                }
            }
        }

        for (NkdLinkDetector.LinkTarget target : allowed) {
            nkdSnapshotService.createOrRefreshSnapshot(
                    owner, target.targetIri(), target.linkType().value(), ownerChangeSet);
        }
        return ownerChangeSet;
    }

    /** The owner concept's current outgoing statements in {@code model} (for unlink triple removal). */
    private Set<Statement> outgoingStatements(Model model, String ownerIri) {
        Set<Statement> out = new HashSet<>();
        Resource ownerRes = model.getResource(ownerIri);
        StmtIterator it = model.listStatements(ownerRes, null, (RDFNode) null);
        try {
            while (it.hasNext()) {
                out.add(it.next());
            }
        } finally {
            it.close();
        }
        return out;
    }

    /** Best-effort batch "which of these IRIs are published in NKD"; empty set on any failure (fail-open). */
    private Set<String> publishedAmong(List<String> iris) {
        try {
            return new HashSet<>(nkdSparqlClient.getPublishedResourcesList(iris));
        } catch (Exception e) {
            log.warn("NKD published-check failed during edit reconcile (fail-open, enforcement skipped): {}",
                    e.getMessage());
            return Set.of();
        }
    }

    private ConceptEditor.EditResult performConceptEdit(String conceptIri, ConceptEditModel conceptEditModel, Model model, String graphName) {
        try {
            ConceptEditor.EditResult editResult = conceptEditor.editConcept(conceptIri, conceptEditModel, model, graphName);
            log.info("Edit completed: {} changes, IRI changed: {}, new IRI: {}",
                    editResult.changesCount, editResult.iriChanged, editResult.newConceptIRI);
            return editResult;
        } catch (ConceptValidationException e) {
            // Invalid user input → propagate unwrapped so it surfaces as HTTP 400
            // (GlobalExceptionHandler maps ConceptValidationException to BAD_REQUEST).
            // Wrapping in OntologyException here would turn it into a 500.
            log.warn("Concept edit rejected — invalid input: {}", e.getMessage());
            throw e;
        } catch (Exception e) {
            log.error("Failed to edit concept", e);
            throw new OntologyException("Nepodařilo se upravit pojem: " + e.getMessage());
        }
    }

    private void saveUpdatedModelToTDB2(String graphName, Model model) {
        try {
            jenaTDB2Repository.putOntologyModel(graphName, model);
            log.info("Updated model saved to TDB2 graph: {}", graphName);
        } catch (Exception e) {
            log.error("Failed to save updated model to TDB2", e);
            throw new OntologyException("Nepodařilo se uložit upravený pojem do TDB2: " + e.getMessage());
        }
    }

    private void updateMetadataFromEditResult(ConceptMetadataEntity metadata, ConceptEditModel conceptEditModel, ConceptEditor.EditResult editResult) {
        if (editResult.iriChanged) {
            metadata.setConceptIri(editResult.newConceptIRI);
        }

        if (conceptEditModel.getNameModel() != null && conceptEditModel.getNameModel().getName() != null) {
            metadata.setConceptName(getNameForMetadata(conceptEditModel.getNameModel()));
        }

        if (conceptEditModel.getInTezaurus() != null) {
            metadata.setInTezaurus(conceptEditModel.getInTezaurus());
        }
    }

    private ConceptMetadataModel saveAndReturnMetadata(ConceptMetadataEntity metadata, String conceptIRI) {
        try {
            ConceptMetadataEntity savedMetadata = conceptMetadataRepository.save(metadata);
            log.info("Metadata updated successfully for concept: {}", conceptIRI);
            return conceptMetadataMapper.toDto(savedMetadata);
        } catch (Exception e) {
            log.error("Failed to update concept metadata", e);
            throw new OntologyException("Nepodařilo se aktualizovat metadata pojmu: " + e.getMessage());
        }
    }

    private Resource createConceptResource(ConceptCreateModel createModel) {
        try {
            Resource conceptResource = conceptCreator.createSingleConcept(createModel);
            log.debug("Successfully transformed concept to Jena Resource: {}", conceptResource.getURI());
            return conceptResource;
        } catch (Exception e) {
            log.error("Failed to transform concept to Jena Resource", e);
            throw new OntologyException("Nepodařilo se transformovat pojem: " + e.getMessage());
        }
    }

    private ConceptMetadataModel checkForExistingConcept(String conceptUri) {
        Optional<ConceptMetadataEntity> existingConcept = conceptMetadataRepository.findByConceptIri(conceptUri);
        if (existingConcept.isPresent()) {
            log.error("Concept already exists with IRI: {}", conceptUri);
            return conceptMetadataMapper.toDto(existingConcept.get());
        }
        return null;
    }

    private void saveConceptToTDB2(Resource conceptResource, String ontologyGraphName) {
        try {
            String conceptIRI = jenaTDB2Repository.saveConcept(conceptResource, ontologyGraphName);
            log.info("Concept saved to TDB2 graph {} successfully: {}", ontologyGraphName, conceptIRI);
        } catch (Exception e) {
            log.error("Failed to save concept to TDB2 graph {}", ontologyGraphName, e);
            throw new OntologyException("Nepodařilo se uložit pojem do TDB2: " + e.getMessage());
        }
    }

    private ConceptMetadataModel saveMetadataWithRollback(ConceptCreateModel createModel, String userId,
                                                          String conceptUri, String ontologyGraphName) {
        try {
            ConceptMetadataEntity savedEntity = saveMetadata(createModel, userId, conceptUri);
            log.info("Metadata saved successfully with ID: {}", savedEntity.getId());

            ConceptMetadataModel result = conceptMetadataMapper.toDto(savedEntity);
            log.info("Concept creation completed successfully: {}", conceptUri);
            return result;
        } catch (Exception e) {
            log.error("Failed to save concept metadata, rolling back TDB2 data", e);
            rollbackTDB2Data(conceptUri, ontologyGraphName);
            throw new OntologyException("Nepodařilo se uložit metadata pojmu: " + e.getMessage());
        }
    }

    private void rollbackTDB2Data(String conceptUri, String ontologyGraphName) {
        try {
            jenaTDB2Repository.deleteConceptFromGraph(conceptUri, ontologyGraphName);
            log.info("Successfully rolled back TDB2 data from graph {} for failed metadata save", ontologyGraphName);
        } catch (Exception rollbackException) {
            log.error("CRITICAL: Failed to rollback TDB2 data from graph {} after metadata failure. " +
                    "Manual cleanup required for concept IRI: {}", ontologyGraphName, conceptUri, rollbackException);
        }
    }

    private String getNameForMetadata(com.dia.ismdtoolbackend.models.NameModel nameModel) {
        if (nameModel == null || nameModel.getName() == null || nameModel.getName().isEmpty()) {
            return "";
        }
        Map<String, String> names = nameModel.getName();
        if (names.containsKey("cs")) {
            return names.get("cs");
        }
        return names.values().iterator().next();
    }

    private PublishedConceptDeviationModel checkPublishedConcept(OntologyDetailModel.ConceptDetailModel localConcept,
                                                                ConceptMetadataModel conceptMetadata) {
        if (Boolean.FALSE.equals(conceptMetadata.getIsPublished())) {
            return null;
        }

        String conceptIri = conceptMetadata.getConceptIri();

        try {
            Optional<OntologyDetailModel.ConceptDetailModel> publishedConceptOpt =
                    nkdSparqlClient.fetchPublishedConcept(conceptIri);

            if (publishedConceptOpt.isEmpty()) {
                log.warn("Published concept not found in NKD: {}", conceptIri);
                return createErrorDeviation(
                        PublishedConceptDeviationModel.DeviationStatus.CONCEPT_NOT_FOUND_IN_NKD,
                        "Concept not found in NKD SPARQL endpoint"
                );
            }

            OntologyDetailModel.ConceptDetailModel publishedConcept = publishedConceptOpt.get();
            return deviationComparator.compareConceptDetails(localConcept, publishedConcept);

        } catch (Exception e) {
            log.error("Error checking published concept deviation: {}", e.getMessage(), e);
            return createErrorDeviation(
                    PublishedConceptDeviationModel.DeviationStatus.ENDPOINT_UNAVAILABLE,
                    "NKD SPARQL endpoint unavailable: " + e.getMessage()
            );
        }
    }

    private PublishedConceptDeviationModel createErrorDeviation(
            PublishedConceptDeviationModel.DeviationStatus status,
            String errorMessage) {
        return PublishedConceptDeviationModel.builder()
                .status(status)
                .errorMessage(errorMessage)
                .build();
    }
}