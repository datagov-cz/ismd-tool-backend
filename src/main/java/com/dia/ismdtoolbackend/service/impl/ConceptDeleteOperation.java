package com.dia.ismdtoolbackend.service.impl;

import com.dia.ismdtoolbackend.entity.ConceptMetadataEntity;
import com.dia.ismdtoolbackend.entity.OntologyMetadataEntity;
import com.dia.ismdtoolbackend.repository.ConceptMetadataRepository;
import com.dia.ismdtoolbackend.repository.JenaTDB2Repository;
import com.dia.ismdtoolbackend.service.NkdSnapshotService;
import com.dia.ismdtoolbackend.outbox.OutboxRelayTrigger;
import com.dia.ismdtoolbackend.outbox.OutboxWriter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.jena.ontology.OntologyException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** Deletes a concept together with the properties and relationships that point at it. */
@Slf4j
@Component
@RequiredArgsConstructor
class ConceptDeleteOperation {

    private final ConceptMetadataRepository conceptMetadataRepository;
    private final JenaTDB2Repository jenaTDB2Repository;
    private final MetadataTouchService metadataTouchService;
    private final NkdSnapshotService nkdSnapshotService;
    private final OutboxWriter outboxWriter;
    private final OutboxRelayTrigger outboxRelayTrigger;
    private final ConceptAccessGuard accessGuard;
    private final InitialGraphGuard initialGraphGuard;

    void deleteConcept(Long conceptId) {
        accessGuard.assertCanModify(conceptId);
        // Take a row lock FIRST (see ConceptMetadataRepository.findWithLockById) so a concurrent
        // edit/delete of the same concept can't enqueue an out-of-order same-aggregate outbox row.
        Optional<ConceptMetadataEntity> conceptMetadataOpt = conceptMetadataRepository.findWithLockById(conceptId);
        if (conceptMetadataOpt.isEmpty()) {
            log.error("conceptId {} not found", conceptId);
            throw new OntologyException("Metadata pojmu s id " + conceptId + "nebyla nalezena.");
        }

        String graphName = conceptMetadataOpt.get().getGraphName();
        initialGraphGuard.requireInitialGraphApplied(graphName);
        String conceptUri = conceptMetadataOpt.get().getConceptIri();

        // Neither the graph nor the concept's triples are required to exist: the queued delete is a
        // no-op for RDF that is already gone, so a concept with missing RDF is still removable.
        List<String> relatedConceptUris = jenaTDB2Repository.findRelatedConceptUris(conceptUri, graphName);
        relatedConceptUris.add(conceptUri);
        List<ConceptMetadataEntity> relatedConceptEntities = findRelatedConceptEntities(relatedConceptUris);

        // NKD local-copy cascade: drop the PG snapshot rows for the deleted concepts. The copy lives
        // only in PG, so there is nothing to add to the TDB2 delete-sweep.
        List<Long> deletedConceptIds = relatedConceptEntities.stream()
                .map(ConceptMetadataEntity::getId)
                .toList();
        nkdSnapshotService.cascadeConceptDeletion(deletedConceptIds, graphName);

        // Enqueue the TDB2 deletion (keyed on the concept being deleted), committed atomically with
        // the PG metadata delete below.
        outboxWriter.enqueueDeleteConcepts(graphName, conceptUri, relatedConceptUris);
        OntologyMetadataEntity parent = conceptMetadataOpt.get().getOntologyMetadata();
        conceptMetadataRepository.deleteAll(relatedConceptEntities);
        metadataTouchService.touchOntology(parent);
        outboxRelayTrigger.nudgeAfterCommit();
    }

    List<ConceptMetadataEntity> findRelatedConceptEntities(List<String> conceptUris) {
        List<ConceptMetadataEntity> relatedConcepts = new ArrayList<>();
        for (String conceptUri : conceptUris) {
            conceptMetadataRepository.findByConceptIri(conceptUri).ifPresent(relatedConcepts::add);
        }
        return relatedConcepts;
    }
}
