package com.dia.ismdtoolbackend.service.impl;

import com.dia.ismdtoolbackend.exception.OntologyInputException;
import com.dia.ismdtoolbackend.exception.OntologyProcessingException;
import org.springframework.dao.DataIntegrityViolationException;
import com.dia.ismdtoolbackend.exception.OntologyNotFoundException;
import com.dia.ismdtoolbackend.entity.ConceptMetadataEntity;
import com.dia.ismdtoolbackend.entity.OntologyMetadataEntity;
import com.dia.ismdtoolbackend.exception.ConceptValidationException;
import com.dia.ismdtoolbackend.models.concept.ConceptCreateModel;
import com.dia.ismdtoolbackend.models.concept.ConceptMetadataModel;
import com.dia.ismdtoolbackend.models.concept.PropertyConceptModel;
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
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.vocabulary.RDF;
import com.dia.ismdtoolbackend.service.snapshot.NkdLookup;
import com.dia.ismdtoolbackend.utility.validation.ConceptInputView;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    private final NkdLinkReconciler nkdLinkReconciler;

    ConceptMetadataModel createConcept(ConceptCreateModel createModel, String userId) {
        return createConcept(createModel, userId, nkdLinkReconciler.live());
    }

    /**
     * What NKD holds about the concept's link targets, asked now: runs before the create's transaction, so
     * NKD latency never holds a database connection.
     */
    NkdLookup prefetchNkd(ConceptCreateModel createModel) {
        return prefetchNkd(List.of(createModel));
    }

    /** As above for a batch: one published check, and each published target fetched once. */
    NkdLookup prefetchNkd(List<? extends ConceptCreateModel> createModels) {
        Map<String, List<String>> referencedByGraph = new LinkedHashMap<>();
        for (ConceptCreateModel createModel : createModels) {
            referencedByGraph.computeIfAbsent(createModel.getOntologyGraphName(), graph -> new ArrayList<>())
                    .addAll(referencedIris(createModel));
        }
        return nkdLinkReconciler.prefetchPublished(referencedByGraph);
    }

    /** The IRIs the concept links to. A Vlastnost's datatype is its range. */
    private static List<String> referencedIris(ConceptCreateModel createModel) {
        ConceptIriNormalizer.normalize(createModel);
        ConceptInputView view = ConceptInputView.of(createModel);
        List<String> referenced = new ArrayList<>();
        if (view != null) {
            view.references().values().forEach(referenced::addAll);
            if (view.exactMatch() != null) {
                referenced.addAll(view.exactMatch());
            }
        }
        if (createModel instanceof PropertyConceptModel property) {
            referenced.add(property.getDataType());
        }
        return referenced;
    }

    /** The create, reading NKD only through {@code nkd}. */
    ConceptMetadataModel createConcept(ConceptCreateModel createModel, String userId, NkdLookup nkd) {
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
        nkdLinkReconciler.rejectPublishedDomainRange(conceptUri, createModel.getConceptTypeEnum(), ontologyGraphName,
                conceptResource.getModel(), nkd);

        // Enqueue the concept's triples (empty remove set), committed atomically with the metadata
        // below. If the PG tx fails, the outbox row rolls back with it, so nothing reaches TDB2.
        outboxWriter.enqueueUpsert(ontologyGraphName, conceptUri, java.util.Set.of(),
                conceptResource.getModel().listStatements().toSet());
        ConceptMetadataEntity savedEntity = saveMetadata(createModel, userId, conceptUri);
        nkdLinkReconciler.takeNkdCopies(savedEntity, conceptResource.getModel(), nkd);
        outboxRelayTrigger.nudgeAfterCommit();
        return conceptMetadataMapper.toDto(savedEntity);
    }

    Resource createConceptResource(ConceptCreateModel createModel) {
        try {
            Resource conceptResource = conceptCreator.createSingleConcept(createModel);
            log.debug("Successfully transformed concept to Jena Resource: {}", conceptResource.getURI());
            return conceptResource;
        } catch (OntologyInputException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to transform concept to Jena Resource", e);
            throw new OntologyProcessingException("Nepodařilo se transformovat pojem.", e);
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
        } catch (DataIntegrityViolationException e) {
            // A concurrent create took the IRI or slug between rejectIfConceptIriTaken and this insert.
            log.info("Rejecting concept creation, IRI or slug taken concurrently: {}", conceptUri);
            throw new ConceptValidationException(
                    "Pojem se stejným názvem již v ontologii existuje: " + conceptUri);
        } catch (Exception e) {
            log.error("Failed to save concept metadata for {}", conceptUri, e);
            throw new OntologyProcessingException("Nepodařilo se uložit metadata pojmu.", e);
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
                    return new OntologyNotFoundException("Slovník s názvem " + ontologyGraphName + " nebyl nalezen.");
                });

        return ConceptMetadataFactory.create(createModel, conceptIri, userId, ontologyMetadata,
                slug -> conceptMetadataRepository.findBySlug(slug).isPresent(), new HashSet<>());
    }
}
