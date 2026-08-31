package com.dia.ismdtoolbackend.service.impl;

import com.dia.ismdtoolbackend.client.NkdSparqlClient;
import com.dia.ismdtoolbackend.config.NkdConfig;
import com.dia.ismdtoolbackend.config.security.SecurityUser;
import com.dia.ismdtoolbackend.controller.dto.GetConceptDto;
import com.dia.ismdtoolbackend.controller.dto.LinkSnapshotDto;
import com.dia.ismdtoolbackend.enums.SnapshotOrigin;
import com.dia.ismdtoolbackend.service.snapshot.LinkSnapshotAssembler;
import com.dia.ismdtoolbackend.service.snapshot.NkdSnapshotWarmer;
import com.dia.ismdtoolbackend.entity.CommentEntity;
import com.dia.ismdtoolbackend.entity.ConceptMetadataEntity;
import com.dia.ismdtoolbackend.entity.OntologyMetadataEntity;
import com.dia.ismdtoolbackend.exception.ConceptValidationException;
import com.dia.ismdtoolbackend.models.OntologyDetailModel;
import com.dia.ismdtoolbackend.enums.ConceptType;
import com.dia.ismdtoolbackend.models.concept.ClassConceptEditModel;
import com.dia.ismdtoolbackend.models.concept.ConceptCreateModel;
import com.dia.ismdtoolbackend.models.concept.ConceptEditModel;
import com.dia.ismdtoolbackend.models.concept.ConceptMetadataModel;
import com.dia.ismdtoolbackend.models.concept.PropertyConceptEditModel;
import com.dia.ismdtoolbackend.models.concept.RelationshipConceptEditModel;
import com.dia.ismdtoolbackend.utility.published.WorkingCopySyncFields;
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
import com.dia.ismdtoolbackend.utility.validation.ConceptInputValidator;
import com.dia.ismdtoolbackend.utility.detail.OntologyDetailExtractor;
import com.dia.ismdtoolbackend.utility.editor.ConceptEditor;
import com.dia.ismdtoolbackend.utility.security.SecurityUtils;
import com.dia.utility.UtilityMethods;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.jena.ontology.OntologyException;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.Statement;
import org.apache.jena.rdf.model.StmtIterator;
import org.apache.jena.vocabulary.RDF;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static com.dia.constants.VocabularyConstants.NEVEREJNY_UDAJ;
import static com.dia.constants.VocabularyConstants.OFN_NAMESPACE_LEGAL;
import static com.dia.constants.VocabularyConstants.USTANOVENI_NEVEREJNOST;
import static com.dia.constants.VocabularyConstants.VEREJNY_UDAJ;

@Service
@RequiredArgsConstructor
@Slf4j
public class ConceptServiceImpl implements ConceptService {

    private final ConceptMetadataRepository conceptMetadataRepository;
    private final OntologyMetadataRepository ontologyMetadataRepository;
    private final MetadataTouchService metadataTouchService;
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
    private final WorkingCopySyncFields syncFields;
    private final NkdSnapshotWarmer nkdSnapshotWarmer;
    private final NkdConfig nkdConfig;
    private final com.dia.ismdtoolbackend.service.WorkingCopyDeviationService workingCopyDeviationService;

    @Override
    @Transactional
    @CacheEvict(cacheNames = WorkingCopyDeviationServiceImpl.LOCAL_CONCEPT_PROJECTION_CACHE, allEntries = true)
    public ConceptMetadataModel createConcept(ConceptCreateModel createModel, String userId) {
        log.info("Creating concept: type={}, name={}, namespace={}, userId={}",
                createModel.getConceptType(), createModel.getNameModel(),
                createModel.getNamespace(), userId);

        validateInput(createModel, userId);

        Resource conceptResource = createConceptResource(createModel);
        String conceptUri = conceptResource.getURI();

        rejectIfConceptIriTaken(conceptUri);

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
    @CacheEvict(cacheNames = WorkingCopyDeviationServiceImpl.LOCAL_CONCEPT_PROJECTION_CACHE, allEntries = true)
    public void deleteConcept(Long conceptId) {
        assertCanModify(conceptId);
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
            OntologyMetadataEntity parent = conceptMetadataOpt.get().getOntologyMetadata();
            conceptMetadataRepository.deleteAll(relatedConceptEntities);
            metadataTouchService.touchOntology(parent);
            outboxRelayTrigger.nudgeAfterCommit();
            return;
        }

        jenaTDB2Repository.deleteConceptsFromGraph(relatedConceptUris, graphName);
        OntologyMetadataEntity parent = conceptMetadataOpt.get().getOntologyMetadata();
        conceptMetadataRepository.deleteAll(relatedConceptEntities);
        metadataTouchService.touchOntology(parent);
    }

    @Override
    @Transactional
    @CacheEvict(cacheNames = WorkingCopyDeviationServiceImpl.LOCAL_CONCEPT_PROJECTION_CACHE, allEntries = true)
    public ConceptMetadataModel editConcept(Long conceptId, ConceptEditModel conceptEditModel) {
        assertCanModify(conceptId);
        // A rename relocates the conceptIri, which for a working copy IS its NKD twin's IRI — so a rename
        // orphans it from the twin. The generic edit path treats that as chosen divergence and severs the
        // working copy to a draft. The sync path passes false: it owns its own sever decision (a name sync
        // relocates the IRI too, but "accept ALL" must stay a working copy).
        return editConcept(conceptId, conceptEditModel, true);
    }

    // Package-private (not private) so a spy in tests can stub this seam directly; the sync path calls it
    // with severWorkingCopyOnRename=false.
    ConceptMetadataModel editConcept(Long conceptId, ConceptEditModel conceptEditModel,
                                     boolean severWorkingCopyOnRename) {
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

        ConceptEditor.EditResult editResult = performConceptEdit(conceptId, aggregateIri, conceptEditModel, model, graphName);

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
            updateMetadataFromEditResult(metadata, conceptEditModel, editResult, severWorkingCopyOnRename);
            outboxRelayTrigger.nudgeAfterCommit();
            return saveAndReturnMetadata(metadata, editResult.newConceptIRI,
                    contentChanged(toRemove, toAdd));
        }

        // Direct (outbox-disabled) path: the editor already applied its delta to `model`. Apply the merged
        // delta (editor ∪ reconcile link removals) so any dropped-link edge removal reaches TDB2 in the
        // same write. Idempotent — re-applying the editor's own triples is a no-op.
        model.remove(new ArrayList<>(toRemove));
        model.add(new ArrayList<>(toAdd));
        saveUpdatedModelToTDB2(graphName, model);
        updateMetadataFromEditResult(metadata, conceptEditModel, editResult, severWorkingCopyOnRename);
        return saveAndReturnMetadata(metadata, editResult.newConceptIRI,
                contentChanged(toRemove, toAdd));
    }

    private boolean contentChanged(Set<Statement> toRemove, Set<Statement> toAdd) {
        return !toRemove.isEmpty() || !toAdd.isEmpty();
    }

    @Override
    @Transactional
    @CacheEvict(cacheNames = WorkingCopyDeviationServiceImpl.LOCAL_CONCEPT_PROJECTION_CACHE, allEntries = true)
    public GetConceptDto syncWorkingCopy(Long conceptId, List<String> fieldsToAccept) {
        // sync calls the private editConcept overload (self-invocation bypasses the proxy), so the evict
        // must be declared here too — a sync changes the local concept and must not leave stale deviation.
        ConceptMetadataEntity metadata = fetchAndValidateMetadata(conceptId, outboxConfig.isEnabled());
        if (!Boolean.TRUE.equals(metadata.getIsPublished())) {
            throw new OntologyValidationException(
                    "Pojem není pracovní kopií publikovaného pojmu v NKD — není co synchronizovat.");
        }

        // ONE read of the NKD twin, used for BOTH the deviation and the values written. Reading twice
        // would decide the (irreversible) sever against one snapshot of NKD and write another, so a
        // change landing between the reads could sever a concept whose accepted set was in fact complete.
        Optional<OntologyDetailModel.ConceptDetailModel> nkdOpt =
                nkdSparqlClient.fetchPublishedConcept(metadata.getConceptIri());
        if (nkdOpt.isEmpty()) {
            throw new OntologyValidationException("Publikovaný pojem nebyl v NKD nalezen.");
        }
        OntologyDetailModel.ConceptDetailModel nkdConcept = nkdOpt.get();

        // The request carries field names only; every accepted value is re-derived here, never trusted
        // from the client.
        GetConceptDto current = getConceptDetail(metadata.getSlug());
        PublishedConceptDeviationModel deviation = deviationComparator.compareConceptDetails(
                current.getConceptDetail(), nkdConcept, SnapshotOrigin.WORKING_COPY,
                metadata.getConceptIri());
        if (deviation.getStatus() != PublishedConceptDeviationModel.DeviationStatus.HAS_DEVIATIONS) {
            throw new OntologyValidationException(
                    "Pojem se neliší od publikovaného pojmu v NKD, nebo NKD není dostupné — není co synchronizovat.");
        }

        Set<String> deviatingKeys = syncFields.deviatingSyncableKeys(deviation);
        Set<String> accepted = new LinkedHashSet<>(fieldsToAccept);
        validateAcceptedKeys(accepted, deviatingKeys);
        // Going private requires a valid provision; accepting the flag pulls the twin's provisions in.
        Set<String> effectiveAccepted = resolvePublicPrivateSync(accepted, nkdConcept);

        String slug = metadata.getSlug();   // an edit never changes the slug, only conceptIri/name
        ConceptEditModel editModel = buildEditModelFromAcceptedFields(metadata, effectiveAccepted, nkdConcept);
        // false: a name/identifier sync relocates the IRI too, but sync owns the sever decision below —
        // accepting ALL deviating fields must leave the concept a faithful, still-tracked working copy.
        editConcept(conceptId, editModel, false);

        // Sever iff the user took only SOME of what deviates: they have chosen to diverge, so the concept
        // stops being a working copy. Accepting everything leaves it a faithful copy, still tracked.
        // Uses the EFFECTIVE accepted set: a privacy provision co-synced by accepting public/private is
        // part of that one coupled decision, so it counts as accepted (a private twin whose only other
        // deviation is its own required provision stays a faithful working copy, not severed).
        // Re-read after the edit: editConcept may have relocated the IRI (a name/identifier sync renames),
        // and this must flip the flag on the post-edit row, in the same transaction as the RDF delta.
        Set<String> acceptedForSever = intersect(effectiveAccepted, deviatingKeys);
        if (acceptedForSever.size() < deviatingKeys.size()) {
            ConceptMetadataEntity postEdit = fetchAndValidateMetadata(conceptId);
            postEdit.setIsPublished(false);
            conceptMetadataRepository.save(postEdit);
            log.info("Working copy {} severed: {} of {} deviating field(s) accepted → now a draft",
                    postEdit.getConceptIri(), acceptedForSever.size(), deviatingKeys.size());
        }

        return getConceptDetail(slug);
    }

    /**
     * Surfaces this concept's tracked local copies (and their {@code snapshotId}, which the UPDATE/REMOVE
     * action URLs need) on concept detail, mirroring the ontology-detail path: the snapshot row IS the
     * cache, so this never calls NKD; cold/stale rows surface as {@code PENDING} and fire the async warmer.
     *
     * <p>The read never writes and never breaks the detail — a snapshot problem must not cost the user
     * their concept page.
     */
    private void surfaceLinkSnapshots(GetConceptDto result, ConceptMetadataEntity metadata) {
        try {
            List<NkdConceptSnapshotEntity> rows = nkdSnapshotService.findForConcept(metadata.getId());
            if (rows.isEmpty()) {
                return;
            }
            LinkSnapshotAssembler.Result assembled = LinkSnapshotAssembler.assemble(
                    rows, nkdConfig.getSnapshot().getDeviationTtl(), Instant.now());

            List<LinkSnapshotDto> forThisConcept = assembled.byOwnerConcept()
                    .getOrDefault(metadata.getConceptIri(), List.of());
            if (!forThisConcept.isEmpty()) {
                result.setLinkSnapshots(forThisConcept);
            }
            if (assembled.needsWarming()) {
                nkdSnapshotWarmer.warmGraph(metadata.getGraphName());
            }
        } catch (Exception e) {
            log.warn("Failed to surface link snapshots for concept {}: {}",
                    metadata.getConceptIri(), e.getMessage());
        }
    }

    /** Every accepted key must be both known and actually deviating; {@code typ} is never acceptable. */
    private void validateAcceptedKeys(Set<String> accepted, Set<String> deviatingKeys) {
        if (accepted.contains(WorkingCopySyncFields.TYPE_KEY)) {
            throw new OntologyValidationException(
                    "Typ pojmu nelze synchronizovat — ISMD nepodporuje převod mezi typy pojmů.");
        }
        Set<String> unknown = accepted.stream()
                .filter(k -> !syncFields.syncableKeys().contains(k))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (!unknown.isEmpty()) {
            throw new OntologyValidationException("Neznámé nebo nesynchronizovatelné vlastnosti: " + unknown);
        }
        Set<String> notDeviating = accepted.stream()
                .filter(k -> !deviatingKeys.contains(k))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (!notDeviating.isEmpty()) {
            throw new OntologyValidationException("Tyto vlastnosti se neliší od NKD: " + notDeviating);
        }
    }

    /**
     * Public/private carries a validity condition: a concept is private only if it also has a valid privacy
     * provision (see {@code updateDataClassification}, which silently drops the neveřejný marker otherwise).
     * When the user accepts {@code veřejnost-údaje} and the NKD twin is private, this:
     * <ol>
     *   <li>verifies the twin actually carries a valid provision — 400 if not, so a private classification
     *       can never land as an invalid (silently-public) shape;</li>
     *   <li>co-syncs {@code ustanovení-dokládající-neveřejnost-údaje} so the accepted "make it private" is
     *       self-consistent — the flag and its required provision are written together.</li>
     * </ol>
     * Going public needs neither. Returns the effective accepted set (with the provision key added when
     * required); the user's original {@code accepted} set is left untouched so the sever arithmetic is
     * unaffected by the auto-added key.
     */
    private static Set<String> intersect(Set<String> a, Set<String> b) {
        Set<String> out = new LinkedHashSet<>(a);
        out.retainAll(b);
        return out;
    }

    private Set<String> resolvePublicPrivateSync(
            Set<String> accepted, OntologyDetailModel.ConceptDetailModel nkd) {
        if (!accepted.contains(WorkingCopySyncFields.IS_PUBLIC_KEY)) {
            return accepted;
        }
        Boolean nkdPublic = WorkingCopySyncFields.publicFromTypes(nkd.getTypes());
        if (!Boolean.FALSE.equals(nkdPublic)) {
            return accepted;   // going public (or no marker) — no provision needed
        }
        // Going private: the twin MUST carry a valid provision, or the private shape would be invalid.
        List<String> nkdProvisions = nkd.getPrivacyProvisions();
        boolean hasValidProvision = nkdProvisions != null
                && nkdProvisions.stream().anyMatch(p -> p != null && !p.trim().isEmpty());
        if (!hasValidProvision) {
            throw new OntologyValidationException(
                    "Nelze synchronizovat neveřejnost údaje: publikovaný pojem v NKD neobsahuje platné "
                            + "ustanovení dokládající neveřejnost.");
        }
        Set<String> effective = new LinkedHashSet<>(accepted);
        effective.add("ustanovení-dokládající-neveřejnost-údaje");
        return effective;
    }

    /**
     * An edit model of the concept's own type carrying <strong>only</strong> the accepted fields, so the
     * normal edit path's null early-returns leave everything else untouched.
     *
     * <p><strong>Except data classification.</strong> {@code updateDataClassification} has no null guard:
     * it strips the veřejný/neveřejný type unconditionally and re-adds it only for a non-null
     * {@code isPublic}, so leaving it null would silently DELETE the classification. Both it and
     * {@code privacyProvisions} are therefore carried through at their CURRENT local values unless the user
     * accepted them — for this field, null means "drop it", not "don't touch it".
     */
    private ConceptEditModel buildEditModelFromAcceptedFields(
            ConceptMetadataEntity metadata, Set<String> accepted, OntologyDetailModel.ConceptDetailModel nkd) {
        ConceptEditModel editModel = newEditModelFor(metadata.getConceptType());
        editModel.setConceptType(metadata.getConceptType().name());

        carryCurrentDataClassification(editModel, metadata);
        accepted.forEach(key -> syncFields.apply(key, editModel, nkd));
        return editModel;
    }

    private ConceptEditModel newEditModelFor(ConceptType conceptType) {
        return switch (conceptType) {
            case TRIDA -> new ClassConceptEditModel();
            case VLASTNOST -> new PropertyConceptEditModel();
            case VZTAH -> new RelationshipConceptEditModel();
            case KONCEPT -> throw new OntologyValidationException(
                    "Pojem typu KONCEPT nelze editovat, a tedy ani synchronizovat.");
        };
    }

    /**
     * Seeds the edit model with the concept's current classification, read from the graph the same way
     * {@code updateDataClassification} reads it, so a sync that does not accept these fields is a no-op on
     * them instead of a deletion. An accepted key overwrites the seed afterwards.
     *
     * <p>This graph read is separate from the one {@link #editConcept} performs, which is safe on the
     * outbox path: both run inside the caller's transaction, and {@code editConcept} takes the concept
     * row lock before its own read, so a concurrent edit of this concept cannot land between them.
     * Do not reuse this read-then-edit shape where that lock is not held.
     */
    private void carryCurrentDataClassification(
            ConceptEditModel editModel, ConceptMetadataEntity metadata) {
        Model model = jenaTDB2Repository.fetchGraph(metadata.getGraphName());
        Resource concept = model.getResource(metadata.getConceptIri());
        Boolean isPublic = currentIsPublic(model, concept);
        List<String> provisions = currentPrivacyProvisions(model, concept);

        if (editModel instanceof ClassConceptEditModel c) {
            c.setIsPublic(isPublic);
            c.setPrivacyProvisions(provisions);
        } else if (editModel instanceof PropertyConceptEditModel p) {
            p.setIsPublic(isPublic);
            p.setPrivacyProvisions(provisions);
        } else if (editModel instanceof RelationshipConceptEditModel r) {
            r.setIsPublic(isPublic);
            r.setPrivacyProvisions(provisions);
        }
    }

    /** True/false from the veřejný/neveřejný rdf:type; null when the concept carries neither. */
    private Boolean currentIsPublic(Model model, Resource concept) {
        if (concept.hasProperty(RDF.type, model.getResource(OFN_NAMESPACE_LEGAL + VEREJNY_UDAJ))) {
            return Boolean.TRUE;
        }
        if (concept.hasProperty(RDF.type, model.getResource(OFN_NAMESPACE_LEGAL + NEVEREJNY_UDAJ))) {
            return Boolean.FALSE;
        }
        return null;
    }

    private List<String> currentPrivacyProvisions(Model model, Resource concept) {
        Property provisionProperty = model.createProperty(OFN_NAMESPACE_LEGAL + USTANOVENI_NEVEREJNOST);
        List<String> provisions = new ArrayList<>();
        StmtIterator it = model.listStatements(concept, provisionProperty, (RDFNode) null);
        try {
            while (it.hasNext()) {
                RDFNode object = it.next().getObject();
                provisions.add(object.isURIResource() ? object.asResource().getURI() : object.toString());
            }
        } finally {
            it.close();
        }
        return provisions;
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
    // Deliberately NOT @Transactional: PG reads interleave with Fuseki, NKD and RPP calls (10s
    // timeouts), so a request-wide transaction would pin a pool connection across them. Entities
    // read after their repository call returns are join-fetched instead.
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

        // The canonical deviation projection is defined over this graph ALONE, so it is computed here,
        // before the cross-graph merge below mutates rawModel in place. Doing it in this order saves
        // both a second Fuseki read of the same graph and a defensive copy of it.
        OntologyDetailModel.ConceptDetailModel canonicalLocal = Boolean.TRUE.equals(metadataEntity.getIsPublished())
                ? workingCopyDeviationService.canonicalLocalConcept(conceptIri, rawModel)
                : null;

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

        PublishedConceptDeviationModel conceptDeviation = checkPublishedConcept(metadataModel, canonicalLocal);
        result.setPublishedConceptDeviationModel(conceptDeviation);

        surfaceLinkSnapshots(result, metadataEntity);

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

        // Adding a concept modifies the vocabulary: the child FK write does not dirty the parent row,
        // so bump it explicitly. (Concept row is written above, ontology second — the standard order.)
        metadataTouchService.touchOntology(savedEntity.getOntologyMetadata());

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

        // Reject the whole create (HTTP 400) if any supplied value is invalid, rather than
        // dropping it silently. Runs the same rule set as the edit path, so identical input
        // fails identically on both verbs.
        List<ConceptInputValidator.InvalidInput> invalid = ConceptInputValidator.validate(createModel);
        if (!invalid.isEmpty()) {
            String detail = invalid.stream()
                    .map(ConceptInputValidator.InvalidInput::toString)
                    .collect(Collectors.joining("; "));
            throw new ConceptValidationException("Neplatné hodnoty při vytváření pojmu: " + detail);
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

    /**
     * Defence in depth: the caller may only edit/delete a concept it owns. {@code ConceptController} already
     * gates both operations with {@code @PreAuthorize canModifyConcept}, but that check is controller-resident
     * — a service-to-service caller (the diagram layer is the first) inherits none of it. Asserting here means
     * the guarantee holds at the layer that actually performs the write.
     *
     * <p>Enforced only when a request context is present. A caller running outside one (the outbox relay, a
     * scheduler, a warmer) has no user to check and is trusted by construction; requiring authentication here
     * would break those paths instead of protecting them.
     */
    private void assertCanModify(Long conceptId) {
        SecurityUser currentUser;
        try {
            currentUser = SecurityUtils.getCurrentUser();
        } catch (IllegalStateException e) {
            return;   // no authenticated context — a non-request caller, not a cross-tenant reach
        }
        if (currentUser.isAdmin()) {
            return;
        }
        ConceptMetadataEntity metadata = fetchAndValidateMetadata(conceptId);
        if (!Objects.equals(metadata.getUserId(), currentUser.getUserId())) {
            log.warn("User {} attempted to modify concept {} owned by {}",
                    currentUser.getUserId(), conceptId, metadata.getUserId());
            throw new AccessDeniedException("Nemáte oprávnění upravovat tento pojem.");
        }
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
     *
     * <p>{@link NkdLinkDetector} calls a target "external" when its IRI is not prefixed by the owner
     * graph's scheme, which is true of a <em>working copy</em> — a locally-owned concept whose own IRI is
     * in NKD. Such a target is external-looking and published, but it is ours, so both paths below
     * subtract the locally-owned IRIs first: it must never be rejected, and never snapshotted as a copy
     * of someone else's concept. The detector stays IO-free; the ownership lookup belongs here.
     */
    private OwnerChangeSet reconcileNkdLinks(ConceptMetadataEntity owner, Model model) {
        String ownerIri = owner.getConceptIri();
        String graphScheme = owner.getGraphName();
        OwnerChangeSet ownerChangeSet = new OwnerChangeSet();

        List<String> domainRangeTargets =
                nkdLinkDetector.forbiddenDomainRangeTargets(ownerIri, graphScheme, model);
        List<NkdLinkDetector.LinkTarget> detectedTargets =
                nkdLinkDetector.allowedTargets(ownerIri, owner.getConceptType(), graphScheme, model);

        Set<String> locallyOwned = locallyOwnedAmong(domainRangeTargets, detectedTargets);

        List<String> foreignDomainRangeTargets = domainRangeTargets.stream()
                .filter(iri -> !locallyOwned.contains(iri))
                .toList();
        if (!foreignDomainRangeTargets.isEmpty()) {
            Set<String> publishedForbidden = publishedAmong(foreignDomainRangeTargets);
            if (!publishedForbidden.isEmpty()) {
                throw new OntologyValidationException(
                        "Definiční obor / obor hodnot nesmí odkazovat na publikovaný pojem v NKD: "
                                + publishedForbidden);
            }
        }

        List<NkdLinkDetector.LinkTarget> allowed = detectedTargets.stream()
                .filter(t -> !locallyOwned.contains(t.targetIri()))
                .toList();
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

    /**
     * Which of the reconcile candidates are concepts we own locally (working copies). One batch lookup
     * over the union of both candidate sets, so the reject path and the snapshot path share a single query.
     */
    private Set<String> locallyOwnedAmong(List<String> domainRangeTargets,
                                          List<NkdLinkDetector.LinkTarget> detectedTargets) {
        Set<String> candidates = new HashSet<>(domainRangeTargets);
        detectedTargets.forEach(t -> candidates.add(t.targetIri()));
        if (candidates.isEmpty()) {
            return Set.of();
        }
        return conceptMetadataRepository.findByConceptIriIn(new ArrayList<>(candidates)).stream()
                .map(ConceptMetadataEntity::getConceptIri)
                .collect(Collectors.toSet());
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

    private ConceptEditor.EditResult performConceptEdit(Long conceptId, String conceptIri, ConceptEditModel conceptEditModel, Model model, String graphName) {
        try {
            ConceptEditor.EditResult editResult = conceptEditor.editConcept(conceptIri, conceptEditModel, model, graphName,
                    candidateIri -> isConceptIriTakenByOther(candidateIri, conceptId));
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

    private void updateMetadataFromEditResult(ConceptMetadataEntity metadata, ConceptEditModel conceptEditModel,
                                              ConceptEditor.EditResult editResult, boolean severWorkingCopyOnRename) {
        if (editResult.iriChanged) {
            metadata.setConceptIri(editResult.newConceptIRI);

            // A working copy is tracked by shared identity with its NKD twin: its conceptIri IS the twin's
            // IRI. Relocating the IRI orphans it from the twin (deviation checks would forever report
            // CONCEPT_NOT_FOUND_IN_NKD), so a rename severs it to an ordinary draft — same rule the sync
            // path applies when a user accepts only some deviating fields.
            if (severWorkingCopyOnRename && Boolean.TRUE.equals(metadata.getIsPublished())) {
                metadata.setIsPublished(false);
                log.info("Working copy renamed to {} → severed from NKD, now a draft",
                        editResult.newConceptIRI);
            }
        }

        if (conceptEditModel.getNameModel() != null && conceptEditModel.getNameModel().getName() != null) {
            metadata.setConceptName(getNameForMetadata(conceptEditModel.getNameModel()));
        }

        if (conceptEditModel.getInTezaurus() != null) {
            metadata.setInTezaurus(conceptEditModel.getInTezaurus());
        }
    }

    /**
     * Persist the metadata row at the end of an edit. When {@code contentChanged}, {@code updatedAt} is
     * stamped explicitly so the column means "the concept last changed" — including RDF-only changes.
     * A no-op edit leaves it unchanged.
     */
    private ConceptMetadataModel saveAndReturnMetadata(ConceptMetadataEntity metadata, String conceptIRI,
                                                       boolean contentChanged) {
        try {
            // Explicit touch: an RDF-only edit dirties no mapped column, so a plain save() would be a
            // no-op and updatedAt would never move. Also propagates to the parent ontology.
            metadataTouchService.touchConceptAndOntology(metadata);
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

    /**
     * Rejects the request when an owned concept already claims this IRI.
     *
     * <p>Matches only owned concepts — NKD snapshot copies live in a separate table, so a
     * referenced NKD concept never triggers a collision.
     */
    private void rejectIfConceptIriTaken(String conceptUri) {
        if (conceptMetadataRepository.findByConceptIri(conceptUri).isPresent()) {
            log.info("Rejecting concept creation, IRI already exists: {}", conceptUri);
            throw new ConceptValidationException(
                    "Pojem se stejným názvem již v ontologii existuje: " + conceptUri);
        }
    }

    /**
     * Tests whether an owned concept other than {@code conceptId} already claims this IRI.
     *
     * <p>Matches only owned concepts — NKD snapshot copies live in a separate table, so a
     * referenced NKD concept never counts as a collision.
     */
    private boolean isConceptIriTakenByOther(String conceptUri, Long conceptId) {
        return conceptMetadataRepository.findByConceptIri(conceptUri)
                .filter(existing -> !existing.getId().equals(conceptId))
                .isPresent();
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

    /**
     * @param canonicalLocal the canonical local projection when the caller already computed it off a
     *                       graph it had in hand; {@code null} makes the deviation service read it
     */
    private PublishedConceptDeviationModel checkPublishedConcept(ConceptMetadataModel conceptMetadata,
                                                                 OntologyDetailModel.ConceptDetailModel canonicalLocal) {
        if (Boolean.FALSE.equals(conceptMetadata.getIsPublished())) {
            return null;
        }
        return workingCopyDeviationService.deviationForWithLocal(
                conceptMetadata.getConceptIri(), canonicalLocal);
    }
}