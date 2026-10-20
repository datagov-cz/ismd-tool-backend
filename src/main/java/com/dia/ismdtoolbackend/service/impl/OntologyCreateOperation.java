package com.dia.ismdtoolbackend.service.impl;

import com.dia.ismdtoolbackend.controller.dto.OntologyCreateWithConceptsRequestDto;
import com.dia.ismdtoolbackend.controller.dto.OntologyCreateWithConceptsResponseDto;
import com.dia.ismdtoolbackend.controller.dto.OntologyIriCheckRequestDto;
import com.dia.ismdtoolbackend.controller.dto.OntologyIriCheckResponseDto;
import com.dia.ismdtoolbackend.entity.ConceptMetadataEntity;
import com.dia.ismdtoolbackend.entity.OntologyMetadataEntity;
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
import com.dia.ismdtoolbackend.models.*;
import com.dia.ismdtoolbackend.mapper.OntologyMetadataMapper;
import com.dia.ismdtoolbackend.outbox.OutboxEntryRepository;
import com.dia.ismdtoolbackend.outbox.OutboxOperation;
import com.dia.ismdtoolbackend.outbox.OutboxStatus;
import com.dia.ismdtoolbackend.outbox.OutboxTriples;
import com.dia.ismdtoolbackend.utility.creator.ConceptMetadataFactory;
import com.dia.ismdtoolbackend.outbox.OutboxRelayTrigger;
import com.dia.ismdtoolbackend.outbox.OutboxWriter;
import com.dia.ismdtoolbackend.repository.*;
import com.dia.utility.URIGenerator;
import com.dia.utility.UtilityMethods;
import com.dia.ismdtoolbackend.utility.creator.OntologyModelFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.jena.ontology.OntologyException;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.vocabulary.DCTerms;
import org.apache.jena.vocabulary.SKOS;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import static com.dia.constants.ExportConstants.Common.DEFAULT_LANG;
import org.springframework.stereotype.Component;

/** Creates a vocabulary, alone or together with its first concepts, and checks IRI availability. */
@Slf4j
@Component
@RequiredArgsConstructor
class OntologyCreateOperation {

    private final OntologyMetadataRepository ontologyMetadataRepository;
    private final ConceptMetadataRepository conceptMetadataRepository;
    private final OntologyMetadataMapper ontologyMetadataMapper;
    private final OutboxWriter outboxWriter;
    private final OutboxEntryRepository outboxRepository;
    private final OutboxRelayTrigger outboxRelayTrigger;
    private final OntologyMetadataEnricher metadataEnricher;

    OntologyMetadataModel createOntology(OntologyCreateModel ontologyCreateModel, String userId) {
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
                    metadataEnricher.enrichMetadataFromModel(model, existingEntity, initial);
                } finally {
                    initial.close();
                }
            } else {
                metadataEnricher.enrichMetadataFromRDF(model, existingEntity);
            }
            return model;
        }

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
            metadataEnricher.enrichMetadataFromModel(result, metadataEntity, graphModel);
            return result;
        } finally {
            graphModel.close();
        }
    }

    OntologyCreateWithConceptsResponseDto createWithConcepts(OntologyCreateWithConceptsRequestDto request, String userId) {
        validateOntologyCreateModel(request.ontology());
        if (userId == null || userId.isBlank()) throw new OntologyValidationException("ID uživatele je povinné.");
        String graph = generateOntologyIri(request.ontology().getNameModel(), request.ontology().getNamespace());
        if (!UtilityMethods.isValidIRI(graph)) throw new OntologyValidationException("Neplatné IRI slovníku: " + graph);
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
        Model graphModel = OntologyModelFactory.create(graph, request.ontology());
        try {
            ConceptCreator creator = new ConceptCreator();
            for (ConceptCreateModel concept : prepared.concepts()) {
                Resource resource = creator.createSingleConcept(concept);
                graphModel.add(resource.getModel());
                resource.getModel().close();
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
            result.setName(metadataEnricher.extractMultilingualValue(vocabulary, SKOS.prefLabel));
            result.setPopis(metadataEnricher.extractMultilingualValue(vocabulary, DCTerms.description));
            result.setConceptCount(prepared.concepts().size());

            outboxWriter.enqueueCreateGraph(graph, graphModel);
            outboxRelayTrigger.nudgeAfterCommit();
            return new OntologyCreateWithConceptsResponseDto(result, prepared.conceptIris());
        } finally {
            graphModel.close();
        }
    }

    void saveDraftConceptMetadata(List<ConceptCreateModel> concepts, OntologyMetadataEntity ontology, String userId) {
        Set<String> slugs = new HashSet<>();
        List<ConceptMetadataEntity> entities = new ArrayList<>();
        for (ConceptCreateModel concept : concepts) {
            ConceptMetadataEntity entity = ConceptMetadataFactory.create(concept, concept.getIdentifier(), userId,
                    ontology, slug -> conceptMetadataRepository.findBySlug(slug).isPresent(), slugs);
            entities.add(entity);
        }
        conceptMetadataRepository.saveAll(entities);
    }

    Optional<OntologyMetadataEntity> findLocalCollision(String graph) {
        return ontologyMetadataRepository.findByGraphName(graph)
                .or(() -> ontologyMetadataRepository.findBySlug(UtilityMethods.extractNameFromIRI(graph)));
    }

    OntologyIriCheckResponseDto checkIri(OntologyIriCheckRequestDto request) {
        validateOntologyName(request.nameModel());
        String iri = generateOntologyIri(request.nameModel(), request.namespace());
        boolean valid = UtilityMethods.isValidIRI(iri);
        boolean available = valid && findLocalCollision(iri).isEmpty();
        return new OntologyIriCheckResponseDto(iri, valid, available);
    }

    String generateOntologyIri(NameModel nameModel, String namespace) {
        return new URIGenerator().generateVocabularyURIFromGivenNamespace(getNameForUriGeneration(nameModel), namespace);
    }

    void validateOntologyName(NameModel nameModel) {
        Map<String, String> name = nameModel != null ? nameModel.getName() : null;
        if (name == null || name.isEmpty()) {
            throw new OntologyValidationException("Název slovníku je povinný.");
        }
        if (isBlank(name.get(DEFAULT_LANG))) {
            throw new OntologyValidationException("Název slovníku musí obsahovat českou variantu (cs).");
        }
    }

    void validateOntologyCreateModel(OntologyCreateModel model) {
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

    static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    OntologyMetadataEntity createOntologyMetadata(String ontologyIRI, String userId) {
        OntologyMetadataEntity metadataEntity = new OntologyMetadataEntity();
        String slug = UtilityMethods.extractNameFromIRI(ontologyIRI);
        metadataEntity.setSlug(slug);
        metadataEntity.setGraphName(ontologyIRI);
        metadataEntity.setUserId(userId);
        metadataEntity.setIsPublished(false);

        return ontologyMetadataRepository.save(metadataEntity);
    }

    String getNameForUriGeneration(com.dia.ismdtoolbackend.models.NameModel nameModel) {
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
