package com.dia.ismdtoolbackend.service.impl;

import com.dia.ismdtoolbackend.entity.ConceptMetadataEntity;
import com.dia.ismdtoolbackend.exception.ConceptValidationException;
import com.dia.ismdtoolbackend.models.concept.ConceptEditModel;
import com.dia.ismdtoolbackend.models.concept.ConceptMetadataModel;
import com.dia.ismdtoolbackend.mapper.ConceptMetadataMapper;
import com.dia.ismdtoolbackend.repository.ConceptMetadataRepository;
import com.dia.ismdtoolbackend.repository.JenaTDB2Repository;
import com.dia.ismdtoolbackend.service.snapshot.OwnerChangeSet;
import com.dia.ismdtoolbackend.utility.creator.ConceptMetadataFactory;
import com.dia.ismdtoolbackend.outbox.OutboxRelayTrigger;
import com.dia.ismdtoolbackend.outbox.OutboxWriter;
import com.dia.ismdtoolbackend.utility.validation.ConceptIriNormalizer;
import com.dia.ismdtoolbackend.utility.editor.ConceptEditor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.jena.ontology.OntologyException;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.Statement;
import org.apache.jena.vocabulary.RDF;
import java.util.HashSet;
import java.util.Set;
import org.springframework.stereotype.Component;

/** Edits a concept: applies the edit to its graph, reconciles NKD links, queues the delta and updates metadata. */
@Slf4j
@Component
@RequiredArgsConstructor
class ConceptEditOperation {

    private final ConceptMetadataRepository conceptMetadataRepository;
    private final JenaTDB2Repository jenaTDB2Repository;
    private final MetadataTouchService metadataTouchService;
    private final ConceptMetadataMapper conceptMetadataMapper;
    private final ConceptEditor conceptEditor;
    private final OutboxWriter outboxWriter;
    private final OutboxRelayTrigger outboxRelayTrigger;
    private final ConceptAccessGuard accessGuard;
    private final InitialGraphGuard initialGraphGuard;
    private final NkdLinkReconciler nkdLinkReconciler;

    ConceptMetadataModel editConcept(Long conceptId, ConceptEditModel conceptEditModel) {
        accessGuard.assertCanModify(conceptId);
        // A rename relocates the conceptIri, which for a working copy IS its NKD twin's IRI — so a rename
        // orphans it from the twin. The generic edit path treats that as chosen divergence and severs the
        // working copy to a draft. The sync path passes false: it owns its own sever decision (a name sync
        // relocates the IRI too, but "accept ALL" must stay a working copy).
        return editConcept(conceptId, conceptEditModel, true);
    }

    // The sync path calls this with severWorkingCopyOnRename=false.
    ConceptMetadataModel editConcept(Long conceptId, ConceptEditModel conceptEditModel,
                                     boolean severWorkingCopyOnRename) {
        log.info("Editing concept: ID={}, type={}",
                conceptId, conceptEditModel.getConceptType());

        ConceptIriNormalizer.normalize(conceptEditModel);

        // Take a row lock FIRST so two concurrent edits of the same concept are
        // serialized — see ConceptMetadataRepository.findWithLockById. The lock must be the first DB
        // read of the critical section (read graph → compute delta → enqueue), so it spans the whole
        // window in which a concurrent edit could enqueue an out-of-order same-aggregate outbox row.
        ConceptMetadataEntity metadata = accessGuard.fetchAndValidateMetadata(conceptId, true);
        String graphName = metadata.getGraphName();
        initialGraphGuard.requireInitialGraphApplied(graphName);

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
        OwnerChangeSet snapshotDelta = nkdLinkReconciler.reconcileNkdLinks(metadata, model);
        Set<Statement> toRemove = new HashSet<>(editResult.statementsToRemove);
        toRemove.addAll(snapshotDelta.toRemove);
        Set<Statement> toAdd = new HashSet<>(editResult.statementsToAdd);
        toAdd.addAll(snapshotDelta.toAdd);

        // Enqueue the merged change set (editor delta ∪ NKD copy delta), NOT a whole-graph PUT,
        // committed atomically with the metadata update below.
        outboxWriter.enqueueUpsert(graphName, aggregateIri, toRemove, toAdd);
        updateMetadataFromEditResult(metadata, conceptEditModel, editResult, severWorkingCopyOnRename);
        outboxRelayTrigger.nudgeAfterCommit();
        return saveAndReturnMetadata(metadata, editResult.newConceptIRI,
                contentChanged(toRemove, toAdd));
    }

    boolean contentChanged(Set<Statement> toRemove, Set<Statement> toAdd) {
        return !toRemove.isEmpty() || !toAdd.isEmpty();
    }

    Model fetchAndValidateGraph(String graphName) {
        Model model = jenaTDB2Repository.fetchGraph(graphName);
        if (model.isEmpty()) {
            log.error("Graph {} is empty or not found", graphName);
            throw new OntologyException("Slovník " + graphName + " je prázdný nebo nebyl nalezen.");
        }
        return model;
    }

    void validateConceptInGraph(String conceptIRI, String graphName, Model model) {
        Resource conceptResource = model.getResource(conceptIRI);
        if (conceptResource == null || !model.containsResource(conceptResource)) {
            log.error("Concept {} not found in graph {}", conceptIRI, graphName);
            throw new OntologyException("Pojem s IRI " + conceptIRI + " nebyl nalezen ve slovníku.");
        }
    }

    ConceptEditor.EditResult performConceptEdit(Long conceptId, String conceptIri, ConceptEditModel conceptEditModel, Model model, String graphName) {
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

    void updateMetadataFromEditResult(ConceptMetadataEntity metadata, ConceptEditModel conceptEditModel,
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
            metadata.setConceptName(ConceptMetadataFactory.nameForMetadata(conceptEditModel.getNameModel()));
        }

        if (conceptEditModel.getInTezaurus() != null) {
            metadata.setInTezaurus(conceptEditModel.getInTezaurus());
        }
    }

    /**
     * Persist the metadata row at the end of an edit. When {@code contentChanged}, the concept and its
     * parent ontology are touched so {@code updatedAt} means "the concept last changed" — including
     * RDF-only changes. A no-op edit leaves both timestamps unchanged.
     */
    ConceptMetadataModel saveAndReturnMetadata(ConceptMetadataEntity metadata, String conceptIRI,
                                                       boolean contentChanged) {
        try {
            if (contentChanged) {
                // Explicit touch: an RDF-only edit dirties no mapped column, so a plain save() would be a
                // no-op and updatedAt would never move. Also propagates to the parent ontology.
                metadataTouchService.touchConceptAndOntology(metadata);
            }
            ConceptMetadataEntity savedMetadata = conceptMetadataRepository.save(metadata);
            log.info("Metadata updated successfully for concept: {}", conceptIRI);
            return conceptMetadataMapper.toDto(savedMetadata);
        } catch (Exception e) {
            log.error("Failed to update concept metadata", e);
            throw new OntologyException("Nepodařilo se aktualizovat metadata pojmu: " + e.getMessage());
        }
    }

    /**
     * Tests whether an owned concept other than {@code conceptId} already claims this IRI.
     *
     * <p>Matches only owned concepts — NKD snapshot copies live in a separate table, so a
     * referenced NKD concept never counts as a collision.
     */
    boolean isConceptIriTakenByOther(String conceptUri, Long conceptId) {
        return conceptMetadataRepository.findByConceptIri(conceptUri)
                .filter(existing -> !existing.getId().equals(conceptId))
                .isPresent();
    }
}
