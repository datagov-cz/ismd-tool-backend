package com.dia.ismdtoolbackend.service.impl;

import com.dia.ismdtoolbackend.entity.ConceptMetadataEntity;
import com.dia.ismdtoolbackend.entity.OntologyMetadataEntity;
import com.dia.ismdtoolbackend.exception.ConceptValidationException;
import com.dia.ismdtoolbackend.models.concept.ConceptCreateModel;
import com.dia.ismdtoolbackend.models.concept.ConceptMetadataModel;
import com.dia.ismdtoolbackend.ofn.OfnWriteGate;
import com.dia.ismdtoolbackend.mapper.ConceptMetadataMapper;
import com.dia.ismdtoolbackend.repository.ConceptMetadataRepository;
import com.dia.ismdtoolbackend.repository.JenaTDB2Repository;
import com.dia.ismdtoolbackend.repository.OntologyMetadataRepository;
import com.dia.ismdtoolbackend.utility.creator.ConceptMetadataFactory;
import com.dia.ismdtoolbackend.outbox.OutboxRelayTrigger;
import com.dia.ismdtoolbackend.outbox.OutboxWriter;
import com.dia.ismdtoolbackend.utility.creator.ConceptCreator;
import com.dia.ismdtoolbackend.utility.validation.ConceptCreateValidator;
import com.dia.ismdtoolbackend.utility.validation.ConceptIriNormalizer;
import com.dia.ismdtoolbackend.utility.validation.ConceptNamespaceRule;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.jena.ontology.OntologyException;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.vocabulary.RDF;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/** Creates a concept: builds its RDF, queues the write and saves its metadata row. */
@Slf4j
@Component
@RequiredArgsConstructor
class ConceptCreateOperation {

    private final ConceptMetadataRepository conceptMetadataRepository;
    private final OntologyMetadataRepository ontologyMetadataRepository;
    private final MetadataTouchService metadataTouchService;
    private final ConceptMetadataMapper conceptMetadataMapper;
    private final ConceptCreator conceptCreator;
    private final OutboxWriter outboxWriter;
    private final OutboxRelayTrigger outboxRelayTrigger;
    private final InitialGraphGuard initialGraphGuard;
    private final OfnWriteGate ofnWriteGate;
    private final JenaTDB2Repository jenaTDB2Repository;

    ConceptMetadataModel createConcept(ConceptCreateModel createModel, String userId) {
        log.info("Creating concept: type={}, name={}, namespace={}, userId={}",
                createModel.getConceptType(), createModel.getNameModel(),
                createModel.getNamespace(), userId);

        ConceptIriNormalizer.normalize(createModel);
        ConceptCreateValidator.validate(createModel, userId);

        Resource conceptResource = createConceptResource(createModel);
        String conceptUri = conceptResource.getURI();
        String ontologyGraphName = createModel.getOntologyGraphName();
        ConceptNamespaceRule.requireInVocabulary(conceptUri, ontologyGraphName);

        rejectIfConceptIriTaken(conceptUri, ontologyGraphName);

        initialGraphGuard.requireInitialGraphApplied(ontologyGraphName);
        ofnWriteGate.requireWritableConcepts(conceptResource.getModel(), ontologyGraphName, List.of(conceptUri));

        // Enqueue the concept's triples (empty remove set), committed atomically with the metadata
        // below. If the PG tx fails, the outbox row rolls back with it, so nothing reaches TDB2.
        outboxWriter.enqueueUpsert(ontologyGraphName, conceptUri, java.util.Set.of(),
                conceptResource.getModel().listStatements().toSet());
        ConceptMetadataEntity savedEntity = saveMetadata(createModel, userId, conceptUri);
        outboxRelayTrigger.nudgeAfterCommit();
        return conceptMetadataMapper.toDto(savedEntity);
    }

    Resource createConceptResource(ConceptCreateModel createModel) {
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
     * Rejects the request when an owned concept already claims this IRI, or when the graph already holds
     * triples about it without a concept row (a concept an upload rejected but kept because another concept
     * references it). Creating over such triples would merge them into the new concept, since a create only
     * inserts.
     *
     * <p>The row check matches only owned concepts — NKD snapshot copies live in a separate table, so a
     * referenced NKD concept never triggers a collision.
     */
    void rejectIfConceptIriTaken(String conceptUri, String graphName) {
        if (conceptMetadataRepository.findByConceptIri(conceptUri).isPresent()) {
            log.info("Rejecting concept creation, IRI already exists: {}", conceptUri);
            throw new ConceptValidationException(
                    "Pojem se stejným názvem již v ontologii existuje: " + conceptUri);
        }
        if (jenaTDB2Repository.conceptFoundInGraph(conceptUri, graphName)) {
            log.info("Rejecting concept creation, graph {} already holds triples of {}", graphName, conceptUri);
            throw new ConceptValidationException("Slovník již obsahuje data pojmu " + conceptUri
                    + " (např. pojem odmítnutý při importu, na který odkazuje jiný pojem). Zvolte jiný název.");
        }
    }

    ConceptMetadataEntity saveMetadata(ConceptCreateModel createModel,
                                                 String userId,
                                                 String conceptUri) {
        ConceptMetadataEntity entity = createMetadataEntity(createModel, userId, conceptUri);
        ConceptMetadataEntity savedEntity;
        try {
            savedEntity = conceptMetadataRepository.save(entity);
        } catch (Exception e) {
            log.error("Failed to save concept metadata for {}", conceptUri, e);
            throw new OntologyException("Nepodařilo se uložit metadata pojmu.");
        }

        // Adding a concept modifies the vocabulary: the child FK write does not dirty the parent row,
        // so bump it explicitly. (Concept row is written above, ontology second — the standard order.)
        metadataTouchService.touchOntology(savedEntity.getOntologyMetadata());

        log.debug("Saved concept metadata: id={}, name={}, type={}, iri={}",
                savedEntity.getId(), savedEntity.getConceptName(),
                savedEntity.getConceptType(), savedEntity.getConceptIri());

        return savedEntity;
    }

    ConceptMetadataEntity createMetadataEntity(ConceptCreateModel createModel,
                                                       String userId,
                                                       String conceptIri) {
        String ontologyGraphName = createModel.getOntologyGraphName();
        OntologyMetadataEntity ontologyMetadata = ontologyMetadataRepository
                .findByGraphName(ontologyGraphName)
                .orElseThrow(() -> {
                    log.error("Ontology metadata not found for graph: {}", ontologyGraphName);
                    return new OntologyException("Slovník s názvem " + ontologyGraphName + " nebyl nalezen.");
                });

        return ConceptMetadataFactory.create(createModel, conceptIri, userId, ontologyMetadata,
                slug -> conceptMetadataRepository.findBySlug(slug).isPresent(), new HashSet<>());
    }
}
