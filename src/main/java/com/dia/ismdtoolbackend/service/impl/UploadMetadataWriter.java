package com.dia.ismdtoolbackend.service.impl;

import com.dia.ismdtoolbackend.entity.ConceptMetadataEntity;
import com.dia.ismdtoolbackend.entity.OntologyMetadataEntity;
import com.dia.ismdtoolbackend.enums.ConceptType;
import com.dia.ismdtoolbackend.exception.OntologyAlreadyExistsException;
import com.dia.ismdtoolbackend.mapper.OntologyMetadataMapper;
import com.dia.ismdtoolbackend.models.OntologyMetadataModel;
import com.dia.ismdtoolbackend.models.UserModel;
import com.dia.ismdtoolbackend.repository.ConceptMetadataRepository;
import com.dia.ismdtoolbackend.repository.OntologyMetadataRepository;
import com.dia.ismdtoolbackend.utility.exporter.turtle.OFNTypeNormalizer;
import com.dia.utility.UtilityMethods;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.jena.ontology.OntModel;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.ResIterator;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.vocabulary.RDF;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static com.dia.constants.VocabularyConstants.*;
import static com.dia.ismdtoolbackend.service.impl.UploadConceptRoleResolver.POJEM_GENERIC;

/** Writes the Postgres metadata rows of an uploaded vocabulary and its concepts. */
@Component
@RequiredArgsConstructor
@Slf4j
class UploadMetadataWriter {

    private final OntologyMetadataMapper ontologyMetadataMapper;
    private final OntologyMetadataRepository ontologyMetadataRepository;
    private final ConceptMetadataRepository conceptMetadataRepository;
    private final UploadConceptRoleResolver roleResolver;

    void checkSlugUniqueness(String graphName) {
        String slug = UtilityMethods.extractNameFromIRI(graphName);
        Optional<OntologyMetadataEntity> existingBySlug = ontologyMetadataRepository.findBySlug(slug);
        if (existingBySlug.isPresent()) {
            OntologyMetadataModel existingMetadata = ontologyMetadataMapper.toDto(existingBySlug.get());
            log.info("Ontology already exists with slug: {} (graph name: {})", slug, graphName);
            throw new OntologyAlreadyExistsException(
                    "Slovník se stejným IRI již v Nástroji existuje: " + graphName,
                    existingMetadata
            );
        }
    }

    /** Whether a committed vocabulary row already points at this graph. */
    boolean graphHasMetadata(String graphName) {
        return ontologyMetadataRepository.findByGraphName(graphName).isPresent();
    }

    OntologyMetadataModel createOntologyMetadataEntity(String graphName, String userId) {
        String slug = UtilityMethods.extractNameFromIRI(graphName);

        OntologyMetadataModel ontologyMetadataModel = new OntologyMetadataModel();
        ontologyMetadataModel.setSlug(slug);
        ontologyMetadataModel.setGraphName(graphName);
        ontologyMetadataModel.setUser(new UserModel(userId));
        ontologyMetadataModel.setIsPublished(false);

        log.debug("Ontology metadata entity graphName: {}, userId: {}", ontologyMetadataModel.getGraphName(), userId);
        OntologyMetadataEntity ontologyMetadataEntity = ontologyMetadataMapper.toEntity(ontologyMetadataModel);
        OntologyMetadataEntity savedOntologyMetadataEntity = ontologyMetadataRepository.save(ontologyMetadataEntity);
        log.debug("Ontology metadata saved: {}", savedOntologyMetadataEntity);
        return ontologyMetadataMapper.toDto(savedOntologyMetadataEntity);
    }

    boolean extractAndSaveConceptMetadata(OntModel model, String graphName, String userId, Long ontologyMetadataId, List<String> publishedConceptIris) {
        log.info("Extracting concept metadata from ontology: {}", graphName);

        OntologyMetadataEntity ontologyMetadata = ontologyMetadataRepository.findById(ontologyMetadataId)
                .orElseThrow(() -> new IllegalStateException("Ontology metadata not found with id: " + ontologyMetadataId));

        boolean ontologyPublished = publishedConceptIris.contains(graphName);
        if (ontologyPublished) {
            log.info("Ontology {} is published in NKD, setting isPublished = true", graphName);
            ontologyMetadata.setIsPublished(true);
            ontologyMetadataRepository.save(ontologyMetadata);
        }

        Resource pojemResource = model.createResource(POJEM_GENERIC);
        ResIterator conceptIterator = model.listResourcesWithProperty(RDF.type, pojemResource);
        List<ConceptMetadataEntity> conceptEntities = new ArrayList<>();
        List<String> rejected = new ArrayList<>();

        while (conceptIterator.hasNext()) {
            Resource conceptResource = conceptIterator.next();

            if (shouldSkipConcept(conceptResource, graphName)) {
                continue;
            }

            String conceptIri = conceptResource.getURI();

            UploadConceptRoleResolver.RoleResolution role = roleResolver.resolveRole(conceptResource);
            if (role.conceptType() == null) {
                rejected.add("Concept with IRI (" + conceptIri + ") rejected, " + role.reason());
                continue;
            }
            ConceptType conceptType = role.conceptType();

            String conceptName = UtilityMethods.extractNameFromIRI(conceptIri);
            String slug = generateConceptSlug(graphName, conceptName);

            ConceptMetadataEntity conceptEntity = new ConceptMetadataEntity();
            conceptEntity.setSlug(slug);
            conceptEntity.setConceptName(conceptName);
            conceptEntity.setConceptType(conceptType);
            conceptEntity.setGraphName(graphName);
            conceptEntity.setConceptIri(conceptIri);
            conceptEntity.setUserId(userId);
            conceptEntity.setIsPublished(false);
            conceptEntity.setOntologyMetadata(ontologyMetadata);

            conceptEntities.add(conceptEntity);
        }

        List<ConceptMetadataEntity> publishedConcepts = conceptEntities.stream()
                .filter(concept -> publishedConceptIris.contains((concept.getConceptIri())))
                .toList();

        if (!publishedConcepts.isEmpty()) {
            log.info("Setting isPublished = true for {} concepts", publishedConcepts.size());
            for (ConceptMetadataEntity conceptMetadataEntity : publishedConcepts) {
                conceptMetadataEntity.setIsPublished(true);
                log.info("IsPublished set for concept {}", conceptMetadataEntity.getConceptIri());
            }
        }

        if (!rejected.isEmpty()) {
            log.warn("{} concept(s) were rejected from ontology {}:{}{}",
                    rejected.size(), graphName,
                    System.lineSeparator(),
                    String.join(System.lineSeparator(), rejected));
        }

        if (!conceptEntities.isEmpty()) {
            conceptMetadataRepository.saveAll(conceptEntities);
            log.info("Saved {} concept metadata entries for ontology: {}", conceptEntities.size(), graphName);
        } else {
            log.info("No concepts found in uploaded ontology: {}", graphName);
        }

        return ontologyPublished;
    }

    private boolean shouldSkipConcept(Resource conceptResource, String graphName) {
        if (!conceptResource.isURIResource()) {
            return true;
        }

        String conceptIri = conceptResource.getURI();

        // Alien-concept guard: a concept whose IRI is not under this vocabulary's
        // namespace is not owned by this upload (e.g. an embedded legislative
        // reference). Don't claim it with a Postgres ownership row — it stays a
        // referenced concept, resolved later via its own vocabulary / NKD. Mirrors
        // the resolution invariant STRSTARTS(conceptIri, graphName).
        if (!OFNTypeNormalizer.isOwnedConcept(conceptIri, graphName)) {
            log.debug("Skipping alien concept not owned by {}: {}", graphName, conceptIri);
            return true;
        }

        // Excluded-concept guard: an owned concept that still lacks skos:inScheme after
        // normalization is one the user chose to EXCLUDE. It must not get an ownership
        // row — it would be unresolvable (no inScheme) and falsely claimed. Its triples
        // remain in the graph as inert context.
        Property skosInScheme = conceptResource.getModel().createProperty(SKOS_NS + "inScheme");
        if (!conceptResource.hasProperty(skosInScheme)) {
            log.debug("Skipping excluded concept (no skos:inScheme) in {}: {}", graphName, conceptIri);
            return true;
        }

        Optional<ConceptMetadataEntity> existing = conceptMetadataRepository.findByConceptIri(conceptIri);
        if (existing.isPresent()) {
            log.debug("Concept already exists: {}", conceptIri);
            return true;
        }

        return false;
    }

    private String generateConceptSlug(String graphName, String conceptName) {
        String baseSlug = UtilityMethods.extractNameFromIRI(graphName) + "-" + conceptName;
        String slug = baseSlug;
        int counter = 1;

        while (conceptMetadataRepository.findBySlug(slug).isPresent()) {
            slug = baseSlug + "-" + counter;
            counter++;
        }

        return slug;
    }
}
